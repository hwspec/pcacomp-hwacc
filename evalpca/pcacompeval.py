#!/usr/bin/env python

def compute_enc(images, npc):
    nimgs, h, w = images.shape
    flattened = images.reshape(nimgs, -1).T
    
