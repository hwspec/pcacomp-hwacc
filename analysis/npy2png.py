#!/usr/bin/env python

from skimage.io import imread, imsave
import numpy as np

images = np.load('testdata.npz')['images']
print(images.shape)
print(f'max={np.max(images)}')

imgpxs = images[0].astype(np.uint16)
imsave('check.png', imgpxs)

