#!/usr/bin/env python

#
# this code evaluates the accuracy of PCA compression on different
# precisions, different restrictions/workarounds due to hardware
# specifications.
#

from skimage.io import imread, imsave
import numpy as np
import sys, os, time

from ctypes import *
from functools import reduce

firstframe=1
lastframe=1

S = 1 # 3000
if len(sys.argv) > 1:
    S = int(sys.argv[1])
print(f'S={S}')

verbose = True # False

def loadfiles(sprime):
    """Load images and pre computed matrixes."""

    data = None
    fn = 'data/sampleimages.npz'
    try:
        images = np.load(fn)['images']
        print(f"images.shape={images.shape}")
        #d = np.array([images[0]])
        d = np.array(images)
        data = np.reshape(d, (d.shape[0], d.shape[1]*d.shape[2]))
    except:
        print(f"Unable load {fn}")
        sys.exit(1)

    enc = None
    fn = f'data/enc{sprime}.npz'
    try:
        enc = np.load(fn)['mat']
    except:
        print(f"Unable load {fn}")
        sys.exit(1)

    inv = np.load('data/inv%d.npz'%S)['mat']


    if verbose:
        print(f'data: {data.shape} {data.dtype}')
        print(f'reduced_encoding_matrix.shape={enc.shape} {enc.dtype}')
        print(f'inv_encoding_matrix.shape={inv.shape} {inv.dtype}')
    
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
    print('fno: full  f32  f16  f16s')
    for fno in range(firstframe, lastframe+1):
        msef64 = evaluatePCA(data[fno], rem, iem, S, 'float64', 'float64')
        msef32 = evaluatePCA(data[fno], rem, iem, S, 'float32', 'float32')
        #        msef32f16 = evaluatePCA(data[fno], rem, iem, S, 'float32', 'float16')
        msef16 = evaluatePCA(data[fno], rem, iem, S, 'float16', 'float16')

        msef16f16s = evaluatePCA_scaling(data[fno], rem, iem, S, 'float16', 'float16', scaling=1000000)
        #        print(f'{fno}: {msef64:.3f} {msef32:.3f} {msef32f16:.3f} {msef16f16s:.3f} ')
        print(f'{fno}: {msef64:.3f} {msef32:.3f} {msef16:.3f} {msef16f16s:.3f} ')

compare_prec()
        
sys.exit(0)

