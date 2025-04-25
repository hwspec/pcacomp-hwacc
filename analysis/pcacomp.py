# This python module includes functions for evaluating the accuracy of
# PCA compression on different precisions, different
# restrictions/workarounds due to hardware specifications.

import matplotlib.pyplot as plt
import math as m
import numpy as np
import sys, os, time

from ctypes import *
from functools import reduce

import struct
import copy

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

    #print(f"inv{inv.shape} quantization ({invminv},{invmaxv}) => ({q_invminv}, {q_invmaxv}) d={d} nbits={nbits}")
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


def evaluatePCA(d, rem, iem, sprime, dataprec, invprec):
    data = np.array([d]).astype(dataprec)
    invsprime = iem.astype(invprec)

    weighting_matrix = np.matmul(data, invsprime)
    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)
    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    #return (mse, data - data_approx)
    return (mse,  data_approx, data - data_approx)

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
    return (mse, data_approx, data - data_approx)


def evaluatePCA_qvec(d, rem, iem, sprime, dataprec, invprec, redprec, qv):
    data = np.array([d]).astype(dataprec)

    iemcopy = copy.deepcopy(iem)

    for s in range(0, sprime):
        iemcopy[:,s] /= qv[s]

    invsprime = iemcopy.astype(invprec)

    # element-wise matrix multiply
    weighting_matrix = (data * invsprime.T).T #  data, invprec
    #print(f'min max {np.min(weighting_matrix)} {np.max(weighting_matrix)}')
    weighting_matrix = weighting_matrix.astype(redprec)
    # reduction in higher precision
    weighting_matrix = np.sum(weighting_matrix, axis=0)

    if False:
        print()
        print(f"data: {np.min(d)} {np.max(d)}")
        print(f"quantized inv: {np.min(invsprime)}  {np.max(invsprime)}")
        print(f"compdata: {np.min(weighting_matrix)}  {np.max(weighting_matrix)}")

    for s in range(0, sprime):
        weighting_matrix[s] *= qv[s]
    # recovery always back to float64
    data_approx = np.matmul(weighting_matrix, rem, dtype=np.float64)
    data_approx = np.clip(data_approx, 0, np.inf)

    if False:
        print(f"approx: {np.min(data_approx)}  {np.max(data_approx)}")
    mse = np.sum((data - data_approx)**2) / (data.shape[1])
    return (mse, data_approx, data - data_approx)


def save_sintdata(fn, data):
    with open(fn, "wb") as f:
        for d in data:
            p = struct.pack("<q", d)  # q for 64-bit, i for 32-bit. < for little endian
            f.write(p)
