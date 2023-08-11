#!/usr/bin/env python

from skimage.io import imread, imsave
import numpy as np
import math as m
import sys

print(len(sys.argv))
if len(sys.argv) < 2:
    print('Usage: npy2png.py filename')
    sys.exit(1)

datafn=sys.argv[1]
print(f"datafn={datafn}")

images = np.load(datafn)['images']
print(f"images.shape={images.shape}")

imgpxs = images[0].astype(np.uint16)
imsave('check.png', imgpxs)

