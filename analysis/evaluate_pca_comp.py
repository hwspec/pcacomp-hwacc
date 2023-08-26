#!/usr/bin/env python

# This code evaluates the accuracy of PCA compression on different
# precisions, different restrictions/workarounds due to hardware
# specifications.
#
# This code requires two data files in a sub directory named 'data':
#   image frame data : {basename}.npy
#   encoding data    : {basename}-encoding.npy
#
# compute-pca-encoding.py can generate the encoding data from image frame
#
# Usage: evavalute-pca-comp.py [S] [basename]
# By default, S=1 and basneme='data1small'
#

#from skimage.io import imread, imsave
import matplotlib.pyplot as plt
import math as m
import numpy as np
import sys, os, time
import copy

from ctypes import *
from functools import reduce

import struct

from pcacomp import *

g_basename='data1small'

g_firstframe=0
g_lastframe=3
g_sprime = 25 # 3000
g_verbose = False #
g_nbits = 7  # nbits for quantized inv. enc. mat. without signbit # double check

if len(sys.argv) > 1:
    g_sprime = int(sys.argv[1])
if len(sys.argv) > 2:
    g_nbits = int(sys.argv[2]) - 1  # -1 because g_nbits doesn't include the sign bit
if len(sys.argv) > 3:
    g_basename = sys.argv[3]

g_datafn = f'data/{g_basename}.npy'
g_encfn  = f'data/{g_basename}-encoding.npy'

g_data = None
g_data_shape_orig = (0,0,0)
g_enc = None
g_inv = None


(g_data, g_redenc, g_invenc, g_data_shape_orig) = loadfiles(g_sprime, g_datafn, g_encfn, g_verbose)

g_compratio = g_data_shape_orig[0]/g_sprime
g_w = g_data_shape_orig[1]
g_h = g_data_shape_orig[2]


#g_scalingfactor = getscalingfactorfp16(g_invenc)

g_quantized_d = getquantizationfactor(g_invenc, g_nbits)

g_qvec = getquantizationvector(g_invenc, g_nbits)


def evaluate_pca(data, fstart, fend, sprime, rem, iem, cr, w, h, nbits):
    msef64array = []
    msef32array = []
    msef16array = []
    msef16marray = []
    mseqvarray = []
    iemcopy = copy.deepcopy(iem)
    for fno in range(fstart, fend):
        iem = copy.deepcopy(iemcopy) # hate. lack of const...
        (msef64, recf64) = evaluatePCA(data[fno], rem, iem, sprime, 'float64', 'float64')
        (msef32, recf32) = evaluatePCA(data[fno], rem, iem, sprime, 'float32', 'float32')
        (msef16, recf16) = evaluatePCA(data[fno], rem, iem, sprime, 'float16', 'float16')
        (msef16m, recf16m) = evaluatePCA_mixed(data[fno], rem, iem, sprime, 'int16', 'int32', 'float32', g_quantized_d)
        (mseqv, recqv) = evaluatePCA_qvec(data[fno], rem, iem, sprime, 'int16', 'int32', 'float32', g_qvec)
        #print(f'fno{fno}: {msef64:.3f} {msef32:.3f} {msef16:.3f} {msef16m:.3} {mseqv:.3f}')
        msef64array.append(msef64)
        msef32array.append(msef32)
        msef16array.append(msef16)
        msef16marray.append(msef16m)
        mseqvarray.append(mseqv)
        if (fno == 0):
            def genpng(fn, a):
                plt.imshow(a)
                plt.colorbar()
                plt.savefig(fn)
                plt.clf()
            genpng(f'png/s{sprime}-fno{fno}-orig.png', data[fno].reshape(w,h))
            genpng(f'png/s{sprime}-fno{fno}-recf64.png', recf64.reshape(w,h))
            genpng(f'png/s{sprime}-fno{fno}-recf32.png', recf32.reshape(w,h))
            genpng(f'png/s{sprime}-fno{fno}-recf16.png', recf16.reshape(w,h))
            genpng(f'png/s{sprime}-fno{fno}-recf16m.png', recf16m.reshape(w,h))
            genpng(f'pngs{sprime}-fno{fno}-recqvec.png', recqv.reshape(w,h))

    print('')
    print(f'[stats] nbits={nbits+1} mem={(nbits+1)*sprime*w*h/8/1024}KB') # +1 because of the sign bit

    def print_prec_stats(d, label):
        (tmpmean, tmpstd, tmpminv, tmpmaxv) = basic_stats(d)
        print(f"{label:5s} {sprime:6d} {cr:8.1f}  {tmpmean:.4f} {tmpstd:.4f} {tmpminv:.4f} {tmpmaxv:.4f}")

    print(f"dtype sprime cratio    mean    stddiv    min    max")
    print_prec_stats(msef64array, 'f64')
#    print_prec_stats(msef32array, 'f32')
    print_prec_stats(msef16array, 'f16')
    print_prec_stats(msef16marray, 'f16m')
    print_prec_stats(mseqvarray, 'qvec')

evaluate_pca(g_data, g_firstframe, g_lastframe, g_sprime, g_redenc, g_invenc, g_compratio, g_w, g_h, g_nbits)

sys.exit(0)

#
# unused
#

#def evaluatePCA_scaling(d, rem, iem, sprime, dataprec, invprec, scaling=1.0):
#    data = np.array([d]).astype(dataprec)
#    iem = iem * scaling
#    invsprime = iem.astype(invprec)
#
#    weighting_matrix = np.matmul(data, invsprime)
#    weighting_matrix /= scaling
#
#    # recovery always back to float64
#    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
#    data_approx = np.clip(data_approx, 0, np.inf)
#
#    mse = np.sum((data - data_approx)**2) / (data.shape[1])
#    return (mse, data - data_approx)
