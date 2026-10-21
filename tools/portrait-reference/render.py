#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Inspect a private Portrait mask and render a background-defocus reference."""
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image


def resize(a, shape):
    if a.ndim == 3:
        return np.stack([resize(a[..., c], shape) for c in range(a.shape[2])], axis=-1)
    return np.asarray(Image.fromarray(a.astype(np.float32)).resize((shape[1], shape[0]), Image.Resampling.BILINEAR))


def box(a, radius):
    if a.ndim == 3:
        return np.stack([box(a[..., c], radius) for c in range(a.shape[2])], axis=-1)
    if radius < 1:
        return a.copy()
    size = radius * 2 + 1
    p = np.pad(a, radius, mode='reflect')
    s = np.pad(p, ((1, 0), (1, 0))).cumsum(0, dtype=np.float64).cumsum(1)
    return ((s[size:, size:] - s[:-size, size:] - s[size:, :-size] + s[:-size, :-size]) / size ** 2).astype(np.float32)


def guided_coefficients(guide, mask, radius=5, epsilon=.001):
    mi, mp = box(guide, radius), box(mask, radius)
    variance = box(guide * guide, radius) - mi * mi
    covariance = box(guide * mask, radius) - mi * mp
    a = covariance / np.maximum(variance + epsilon, epsilon)
    b = mp - a * mi
    return box(a, radius), box(b, radius)


def render(image, mask, blur=8):
    if image.ndim != 3 or image.shape[2] != 3 or not np.isfinite(image).all() or not np.isfinite(mask).all():
        raise ValueError('invalid image or mask')
    if min(mask.shape) < 2 or not 1 <= blur <= 40:
        raise ValueError('invalid mask size or defocus radius')
    scale = min(1.0, 1024 / max(image.shape[:2]))
    shape = (max(2, round(image.shape[0] * scale)), max(2, round(image.shape[1] * scale)))
    small = resize(image, shape)
    luminance = small @ np.array([.2126, .7152, .0722], dtype=np.float32)
    a, b = guided_coefficients(luminance, resize(mask, shape))
    coarse_alpha = np.clip(a * luminance + b, 0, 1)
    full_luma = image @ np.array([.2126, .7152, .0722], dtype=np.float32)
    alpha = np.clip(resize(a, image.shape[:2]) * full_luma + resize(b, image.shape[:2]), 0, 1)
    # Compress only the low-confidence tails. The uncertain edge remains a continuous matte.
    alpha = np.clip((alpha - .03) / .94, 0, 1)
    weight = 1 - coarse_alpha
    numerator = small * weight[..., None]
    denominator = weight
    for _ in range(3):
        numerator, denominator = box(numerator, blur), box(denominator, blur)
    # Normalized background convolution excludes foreground colour from the blur, avoiding
    # a bright/dark subject halo spilling onto the background.
    background = numerator / np.maximum(denominator[..., None], .0001)
    background = resize(background, image.shape[:2])
    result = image * alpha[..., None] + background * (1 - alpha[..., None])
    return np.clip(result, 0, 1), alpha


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('image', type=Path)
    p.add_argument('run', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--blur', type=int, default=8)
    args = p.parse_args()
    metadata = json.loads((args.run / 'result.json').read_text())
    if metadata.get('status') != 'ok':
        raise ValueError('Portrait inference did not succeed')
    width, height = metadata['maskWidth'], metadata['maskHeight']
    mask = np.fromfile(args.run / 'mask.f32', dtype='<f4')
    if mask.size != width * height or width * height > 1_000_000:
        raise ValueError('mask size mismatch')
    mask = mask.reshape(height, width)
    image = np.asarray(Image.open(args.image).convert('RGB'), dtype=np.float32) / 255
    result, alpha = render(image, mask, args.blur)
    args.output.mkdir(parents=True, exist_ok=False)
    for name, values in [('portrait', result), ('alpha', alpha)]:
        Image.fromarray(np.round(values * 255).astype(np.uint8)).save(args.output / f'{name}.png')
    print(json.dumps({'foregroundFraction': float((mask > .5).mean()),
                      'inferenceMs': metadata['inferenceMs'], 'blurRadiusAt1024': args.blur}))


if __name__ == '__main__':
    main()
