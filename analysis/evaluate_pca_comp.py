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

from ctypes import *
from functools import reduce

import struct

g_basename='data1small'

g_firstframe=0
g_lastframe=1
g_sprime = 25 # 3000
g_verbose = True # False
g_nbits = 8  # nbits for quantized inv. enc. mat.

if len(sys.argv) > 1:
    g_sprime = int(sys.argv[1])
if len(sys.argv) > 2:
    g_nbits = int(sys.argv[2])
if len(sys.argv) > 3:
    g_basename = sys.argv[3]

g_datafn = f'data/{g_basename}.npy'
g_encfn  = f'data/{g_basename}-encoding.npy'

g_data = None
g_data_shape_orig = (0,0,0)
g_enc = None
g_inv = None

def basic_stats(d):
    dmean = np.mean(d)
    dstd  = np.std(d)
    dminv = np.min(d)
    dmaxv = np.max(d)
    return (dmean, dstd, dminv, dmaxv)

def getscalingfactorfp16(inv):
    naccums = 128*128
    pixmaxv = (1 << 8) - 1

    invminv = np.min(inv)
    invmaxv = np.max(inv)

    f16max=65504.0
    f16min=6.1035e-5

    f = m.fabs((f16max/naccums/pixmaxv)  / invminv)

    s_inv = inv * f
    s_invminv = np.min(s_inv)
    s_invmaxv = np.max(s_inv)

    print(f"scalingfactor={f}")
    print(f"inv scaling: ({invminv},{invmaxv}) => ({s_invminv}, {s_invmaxv})")
    return f


def getquantizationfactor(inv, nbits=12):
    invminv = np.min(inv)
    invmaxv = np.max(inv)

    imax=(1<<nbits) - 1

    d = (invmaxv-invminv)/imax

    q_inv = inv / d
    q_invminv = int(np.min(q_inv))
    q_invmaxv = int(np.max(q_inv))

    print(f"inv{inv.shape} quantization ({invminv},{invmaxv}) => ({q_invminv}, {q_invmaxv}) d={d} nbits={nbits}")
    return d


def getquantizationvector(inv, nbits=12):
    invminv = np.min(inv)
    invmaxv = np.max(inv)

    imax=(1<<nbits) - 1

    qv = []
    for i in range(0, inv.shape[1]):
        c = inv[:,i]
        d = (np.max(c) - np.min(c))/imax
        qv.append(d)
        q_inv = c / d
        q_invminv = int(np.min(q_inv))
        q_invmaxv = int(np.max(q_inv))
        #print(f"c{i} ({invminv:.5e},{invmaxv:.5e}) => ({q_invminv}, {q_invmaxv}) d={d:.5e}")
    return qv

def loadfiles(sprime, datafn, encfn, verbose):
    """Load images and pre computed matrixes."""

    data = None
    try:
        d = np.load(datafn)
    except:
        print(f"Unable to load {datafn}")
        sys.exit(1)

    data_shape_orig = d.shape
    data = np.reshape(d, (d.shape[0], d.shape[1]*d.shape[2]))
    data = data.astype('float64')

    enc = None
    try:
        enc = np.load(encfn)
    except:
        print(f"Unable load {encfn}")
        sys.exit(1)

    renc = enc[:sprime,:]
    inv = np.linalg.pinv(renc)

    if verbose:
        (datamean, datastd, dataminv, datamaxv) = basic_stats(data)
        datanbits = int(m.ceil(m.log(datamaxv,2.0)))

        (rencmean, rencstd, rencminv, rencmaxv) = basic_stats(renc)
        (invmean, invstd, invminv, invmaxv) = basic_stats(inv)

        print(f'data: {data.shape} {data.dtype}')
        print(f'      (mean,std,minv,maxv,nbits)=({datamean:.4f}, {datastd:.4f}, {int(dataminv)}, {int(datamaxv)}, {datanbits})')
        print(f'reduced_encoding: {renc.shape} {renc.dtype}')
        print(f'      (mean,std,minv,maxv)=({rencmean}, {rencstd}, {rencminv}, {rencmaxv})')
        print(f'inv_encoding: {inv.shape} {inv.dtype}')
        print(f'      (mean,std,minv,maxv)=({invmean}, {invstd}, {invminv}, {invmaxv})')
        print('')

    if not (data.shape[1] == enc.shape[1] and data.shape[1] == inv.shape[0]):
        print('Shape mismatch')
        sys.exit(1)

    return (data, renc, inv, data_shape_orig)


(g_data, g_redenc, g_invenc, g_data_shape_orig) = loadfiles(g_sprime, g_datafn, g_encfn, g_verbose)

g_compratio = g_data_shape_orig[0]/g_sprime
g_w = g_data_shape_orig[1]
g_h = g_data_shape_orig[2]


#g_scalingfactor = getscalingfactorfp16(g_invenc)

g_quantized_d = getquantizationfactor(g_invenc, g_nbits)

g_qvec = getquantizationvector(g_invenc, g_nbits)

def evaluatePCA(d, rem, iem, sprime, dataprec, invprec):
    data = np.array([d]).astype(dataprec)
    invsprime = iem.astype(invprec)

    weighting_matrix = np.matmul(data, invsprime)

    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    return (mse, data - data_approx)

def evaluatePCA_mixed(d, rem, iem, sprime, dataprec, invprec, redprec, qd):
    data = np.array([d]).astype(dataprec)
    iem = iem/qd
    invsprime = iem.astype(invprec)

    #print(f"quantized inv: {np.min(invsprime)}  {np.max(invsprime)}")

    # element-wise matrix multiply
    weighting_matrix = (data * invsprime.T).T #  data, invprec
    #print(f'min max {np.min(weighting_matrix)} {np.max(weighting_matrix)}')
    weighting_matrix = weighting_matrix.astype(redprec)
    # reduction in higher precision
    weighting_matrix = np.sum(weighting_matrix, axis=0)
    weighting_matrix *= qd

    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    return (mse, data - data_approx)


def evaluatePCA_qvec(d, rem, iem, sprime, dataprec, invprec, redprec, qv):
    data = np.array([d]).astype(dataprec)

    for s in range(0, sprime):
        iem[:,s] /= qv[s]

    invsprime = iem.astype(invprec)

    #print(f"quantized inv: {np.min(invsprime)}  {np.max(invsprime)}")

    # element-wise matrix multiply
    weighting_matrix = (data * invsprime.T).T #  data, invprec
    #print(f'min max {np.min(weighting_matrix)} {np.max(weighting_matrix)}')
    weighting_matrix = weighting_matrix.astype(redprec)
    # reduction in higher precision
    weighting_matrix = np.sum(weighting_matrix, axis=0)

    for s in range(0, sprime):
        weighting_matrix[s] *= qv[s]

    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    return (mse, data - data_approx)


def save_sintdata(fn, data):
    with open(fn, "wb") as f:
        for d in data:
            p = struct.pack("<q", d)  # q for 64-bit, i for 32-bit. < for little endian
            f.write(p)


def evaluate_pca(data, fstart, fend, sprime, rem, iem, cr, w, h, nbits):
    msef64array = []
    msef32array = []
    msef16array = []
    msef16marray = []
    mseqvarray = []
    for fno in range(fstart, fend):
        (msef64, recf64) = evaluatePCA(data[fno], rem, iem, sprime, 'float64', 'float64')
        (msef32, recf32) = evaluatePCA(data[fno], rem, iem, sprime, 'float32', 'float32')
        (msef16, recf16) = evaluatePCA(data[fno], rem, iem, sprime, 'float16', 'float16')
        (msef16m, recf16m) = evaluatePCA_mixed(data[fno], rem, iem, sprime, 'int16', 'int32', 'float32', g_quantized_d)
        (mseqv, recqv) = evaluatePCA_qvec(data[fno], rem, iem, sprime, 'int16', 'int32', 'float32', g_qvec)
        #  print(f'{fno}: {msef64:.3f} {msef32:.3f} {msef16:.3f} {msef16s:.3}')
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
    print(f'[stats] nbits={nbits} mem={nbits*sprime*w*h/8/1024}KB')

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

def evaluatePCA_scaling(d, rem, iem, sprime, dataprec, invprec, scaling=1.0):
    data = np.array([d]).astype(dataprec)
    iem = iem * scaling
    invsprime = iem.astype(invprec)

    weighting_matrix = np.matmul(data, invsprime)
    weighting_matrix /= scaling

    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    return (mse, data - data_approx)
