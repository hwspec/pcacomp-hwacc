#!/usr/bin/env python

import numpy as np
import math as m
from scipy import linalg as la

dpath='data'
bname='data1small'
datafn = f'{dpath}/{bname}.npz'
print(f'datafn={datafn}')

data = np.load(datafn)["data"]
print(f'data.shape={data.shape}')
data = np.reshape(data, (data.shape[0], data.shape[1] * data.shape[2]))
print(f'data.shape={data.shape}')

# the covariance matrix
cov = np.cov(data)

# eigenvalues (encoding) and eigenvectors of cov (weighting)
encoding, weighting = la.eigh(cov)
print(f"encoding.shape={encoding.shape}")
print(f"weighting.shape={weighting.shape}")

idx = np.argsort(encoding)[::-1]
encoding = encoding[idx]
weighting = weighting[:,idx]

encoding = np.matmul(weighting.T, data)

encodingfn=f'{dpath}/{bname}-encoding.npy'
weightingfn=f'{dpath}/{bname}-weighting.npy'
print(f"Saving {encodingfn} and {weightingfn}")
np.save(encodingfn, encoding)
np.save(weightingfn, weightingfn)



