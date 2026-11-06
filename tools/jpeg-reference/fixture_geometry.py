"""Deterministic, generated test scene geometry; no photographs."""
import numpy as np


def warp(image, dx, dy):
    h, w = image.shape[:2]
    yy, xx = np.indices((h, w), dtype=np.float32)
    xx += dx
    yy += dy
    valid = (xx >= 0) & (xx <= w-1) & (yy >= 0) & (yy <= h-1)
    xx = xx.clip(0, w-1)
    yy = yy.clip(0, h-1)
    x0, y0 = xx.astype(np.int32), yy.astype(np.int32)
    x1, y1 = np.minimum(x0+1, w-1), np.minimum(y0+1, h-1)
    u, v = xx-x0, yy-y0
    if image.ndim == 3:
        u, v = u[..., None], v[..., None]
    return ((1-v)*((1-u)*image[y0, x0]+u*image[y0, x1]) +
            v*((1-u)*image[y1, x0]+u*image[y1, x1])).astype(np.float32), valid


def luma(rgb):
    return (rgb[..., 0] + 2*rgb[..., 1] + rgb[..., 2])/4
