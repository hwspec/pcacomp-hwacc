#!/usr/bin/env python

# this code evaluates the accuracy of PCA compression on different
# precisions, different restrictions/workarounds due to hardware
# specifications.
#

from skimage.io import imread, imsave
import math as m
import numpy as np
import sys, os, time

from ctypes import *
from functools import reduce

#datafn = 'data/sampleimages.npz'
datafn = 'data/crop_data1.npz'

firstframe=1
lastframe=10

S = 1 # 3000
if len(sys.argv) > 1:
    S = int(sys.argv[1])
print(f'S={S}')

verbose = True # False

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

    print(f"invminv={invminv} invmaxv={invmaxv}")
    print(f"f16normalized: {s_invminv} {s_invmaxv}")
    print(f"f={f}")
    
    return f

def loadfiles(sprime):
    """Load images and pre computed matrixes."""

    data = None
    try:
        images = np.load(datafn)['images']
        print(f"images.shape={images.shape}")
        #d = np.array([images[0]])
        d = np.array(images)
        data = np.reshape(d, (d.shape[0], d.shape[1]*d.shape[2]))
    except:
        print(f"Unable load {datafn}")
        sys.exit(1)
    data = data.astype('float64')

    enc = None
    encfn = f'data/enc{sprime}.npz'
    try:
        enc = np.load(encfn)['mat']
    except:
        print(f"Unable load {encfn}")
        sys.exit(1)

    inv = np.load('data/inv%d.npz'%S)['mat']

    scalingfactor = getscalingfactorfp16(inv)
    
    if verbose:
        (datamean, datastd, dataminv, datamaxv) = basic_stats(data)
        datanbits = int(m.ceil(m.log(datamaxv,2.0)))
        (invmean, invstd, invminv, invmaxv) = basic_stats(inv)

        print(f'data: {data.shape} {data.dtype}')
        print(f'datastats: (mean,std,minv,maxv,nbits)=({datamean}, {datastd}, {dataminv}, {datamaxv}, {datanbits})')
        print(f'reduced_encoding_matrix.shape={enc.shape} {enc.dtype}')
        print(f'inv_encoding_matrix.shape={inv.shape} {inv.dtype}')
        print(f'invstats: (mean,std,minv,maxv)=({invmean}, {invstd}, {invminv}, {invmaxv})')
        print('')
    
    if not (data.shape[1] == enc.shape[1] and data.shape[1] == inv.shape[0]):
        print('Shape mismatch')
        sys.exit(1)

    return (data, enc, inv)
    

(data, rem, iem) = loadfiles(S)

def evaluatePCA(d, rem, iem, sprime, dataprec, invprec):
    data = np.array([d]).astype(dataprec)
    invsprime = iem[:, :sprime].astype(invprec)
    # print(f"{data.shape} {invsprime.shape}")
    weighting_matrix = np.matmul(data, invsprime)
    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    #imsave(f'orig.png', data.reshape(128,128))
    #imsave(f'approx.png', data_approx.reshape(128,128))

    return np.sum((data - data_approx)**2) / (data.shape[1])

def evaluatePCA_f16trick(d, rem, iem, sprime):
    data = np.array([d]).astype('float32')
    invsprime = iem[:, :sprime].astype('float32')
    # print(f"{data.shape} {invsprime.shape}")
    weighting_matrix = np.matmul(data, invsprime)
    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)
    return np.sum((data - data_approx)**2) / (data.shape[1])

def evaluatePCA_scaling(d, rem, iem, sprime, dataprec, invprec, scaling=1):
    data = np.array([d]).astype(dataprec)
    tmp = iem[:, :sprime] * scaling
    #print(np.max(np.abs(tmp)), np.min(np.abs(tmp)))
    tmp = tmp.astype(invprec)
    #print(np.max(np.abs(tmp)), np.min(np.abs(tmp)))
    invsprime = tmp
    # print(f"{data.shape} {invsprime.shape}")
    weighting_matrix = np.matmul(data, invsprime)
    weighting_matrix /= scaling
    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    return np.sum((data - data_approx)**2) / (data.shape[1])

def compare_prec():
    #print('fno: full  f32  f32xf16 f16xf32  f16x16')
#    print('fno: full  f32  f16  f16s')
    msef64array = []
    msef32array = []
    msef16array = []
    msef16sarray = []
    for fno in range(firstframe, lastframe+1):
        msef64 = evaluatePCA(data[fno], rem, iem, S, 'float64', 'float64')
        msef32 = evaluatePCA(data[fno], rem, iem, S, 'float32', 'float32')
        # msef32f16 = evaluatePCA(data[fno], rem, iem, S, 'float32', 'float16')
        msef16 = evaluatePCA(data[fno], rem, iem, S, 'float16', 'float16')

        msef16s = evaluatePCA_scaling(data[fno], rem, iem, S, 'float16', 'float16', scaling=1000000)
        # print(f'{fno}: {msef64:.3f} {msef32:.3f} {msef32f16:.3f} {msef16s:.3f} ')
#        print(f'{fno}: {msef64:.3f} {msef32:.3f} {msef16:.3f} {msef16s:.3f}')
        msef64array.append(msef64)
        msef32array.append(msef32)
        msef16array.append(msef16)
        msef16sarray.append(msef16s)

    print(f'[stats]')
    def print_prec_stats(d, label):
        (tmpmean, tmpstd, tmpminv, tmpmaxv) = basic_stats(d)
        print(f"{label:3s}: {tmpmean} {tmpstd} {tmpminv} {tmpmaxv}")
        
    print_prec_stats(msef64array, 'f64')
    print_prec_stats(msef32array, 'f32')
    print_prec_stats(msef16array, 'f16')
    print_prec_stats(msef16sarray, 'f16s')
        
compare_prec()
        
sys.exit(0)
