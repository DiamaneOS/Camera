#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Offline RAW reference. All image data and results belong in a private output directory."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
import struct
import time

import numpy as np
from PIL import Image


CFA = ((0, 1, 1, 2), (1, 0, 2, 1), (1, 2, 0, 1), (2, 1, 1, 0))


def load_metadata(path):
    text = Path(path).read_text()
    try:
        return json.loads(text), 0
    except json.JSONDecodeError:
        # Early probe builds emitted Android Rational values as bare p/q tokens.
        # Repair in memory only, skipping complete JSON strings. Never edit source evidence.
        count = 0
        def quote(match):
            nonlocal count
            if match.group('rational') is None:
                return match.group()
            count += 1
            return '"' + match.group() + '"'
        repaired = re.sub(r'"(?:\\.|[^"\\])*"|(?P<rational>-?\d+/[1-9]\d*)(?![\d.])', quote, text)
        if not count:
            raise
        return json.loads(repaired), count


class Dng:
    """Read only the uncompressed monochrome Bayer layout produced by platform DngCreator.

    Whitelist pixel/calibration tags. Never export other TIFF tags, EXIF, or location metadata.
    This host reader is not part of the Android application.
    """
    TAGS = {256, 257, 258, 259, 262, 273, 277, 278, 279, 33421, 33422,
            50714, 50717, 50721, 50722, 50723, 50724, 50727, 50728, 50778, 50779, 50964, 50965}
    TYPES = {1: ('B', 1), 3: ('H', 2), 4: ('I', 4), 5: ('I', 8), 9: ('i', 4), 10: ('i', 8)}

    def __init__(self, path):
        self.path = Path(path)
        size = self.path.stat().st_size
        self.tags = {}
        with self.path.open('rb') as f:
            h = f.read(8)
            if len(h) != 8 or h[:2] not in (b'II', b'MM'):
                raise ValueError('not a TIFF DNG')
            self.endian = '<' if h[:2] == b'II' else '>'
            if struct.unpack(self.endian + 'H', h[2:4])[0] != 42:
                raise ValueError('unsupported TIFF version')
            off = struct.unpack(self.endian + 'I', h[4:])[0]
            if not 8 <= off <= size - 2:
                raise ValueError('invalid TIFF directory')
            f.seek(off)
            n = struct.unpack(self.endian + 'H', f.read(2))[0]
            if n > 1024 or off + 2 + n * 12 + 4 > size:
                raise ValueError('invalid TIFF tag count')
            entries = f.read(n * 12)
            for i in range(n):
                entry = entries[i * 12:(i + 1) * 12]
                tag, typ, count = struct.unpack(self.endian + 'HHI', entry[:8])
                if tag not in self.TAGS:
                    continue
                if tag in self.tags or typ not in self.TYPES or not 0 < count <= 65536:
                    raise ValueError('invalid pixel/calibration tag')
                code, unit = self.TYPES[typ]
                length = count * unit
                if length <= 4:
                    data = entry[8:8 + length]
                else:
                    pos = struct.unpack(self.endian + 'I', entry[8:])[0]
                    if pos > size or length > size - pos:
                        raise ValueError('TIFF value outside file')
                    f.seek(pos)
                    data = f.read(length)
                values = struct.unpack(self.endian + code * (length // struct.calcsize(code)), data)
                if typ in (5, 10):
                    if any(v == 0 for v in values[1::2]):
                        raise ValueError('zero calibration denominator')
                    values = tuple(a / b for a, b in zip(values[::2], values[1::2]))
                self.tags[tag] = values
        self.width = int(self.one(256))
        self.height = int(self.one(257))
        if min(self.width, self.height) < 4 or self.width * self.height > 64_000_000:
            raise ValueError('unsupported image dimensions')
        if self.width % 2 or self.height % 2:
            raise ValueError('Bayer dimensions must be even')
        if (self.one(258), self.one(259), self.one(262), self.one(277)) != (16, 1, 32803, 1):
            raise ValueError('requires uncompressed 16-bit Bayer DNG')
        if self.tags.get(33421) != (2, 2):
            raise ValueError('requires a 2x2 Bayer pattern')
        if self.tags.get(33422) not in CFA:
            raise ValueError('unsupported Bayer pattern')
        offsets, counts = self.tags[273], self.tags[279]
        rows = int(self.one(278))
        if rows <= 0 or len(offsets) != math.ceil(self.height / rows) or len(offsets) != len(counts):
            raise ValueError('invalid RAW strips')
        for i, (offset, count) in enumerate(zip(offsets, counts)):
            expected = min(rows, self.height - i * rows) * self.width * 2
            if count != expected or offset > size or count > size - offset:
                raise ValueError('invalid RAW strip size or offset')

    def one(self, tag):
        value = self.tags.get(tag, ())
        if len(value) != 1:
            raise ValueError(f'missing or invalid pixel tag {tag}')
        return value[0]

    def read(self):
        result = np.empty((self.height, self.width), dtype=np.uint16)
        rows = int(self.one(278))
        with self.path.open('rb') as f:
            for i, (offset, count) in enumerate(zip(self.tags[273], self.tags[279])):
                f.seek(offset)
                block = np.frombuffer(f.read(count), dtype=self.endian + 'u2')
                result[i * rows:min((i + 1) * rows, self.height)] = block.reshape(-1, self.width)
        return result


def number(value):
    if isinstance(value, str) and '/' in value:
        a, b = value.split('/')
        return float(a) / float(b)
    return float(value)


def normalize(raw, metadata, static):
    black = metadata['dynamicBlackLevel'] if metadata.get('dynamicBlackLevel') is not None else static['blackLevelPattern']
    white = number(metadata['dynamicWhiteLevel'] if metadata.get('dynamicWhiteLevel') is not None else static['whiteLevel'])
    black = np.array([number(x) for x in black], dtype=np.float32).reshape(2, 2)
    if not np.isfinite(black).all() or not np.isfinite(white) or np.any(black >= white):
        raise ValueError('invalid black/white level')
    out = np.empty(raw.shape, dtype=np.float32)
    for y in range(2):
        for x in range(2):
            out[y::2, x::2] = (raw[y::2, x::2].astype(np.float32) - black[y, x]) / (white - black[y, x])
    return out


def half(image):
    h, w = image.shape
    return image[:h // 2 * 2, :w // 2 * 2].reshape(h // 2, 2, w // 2, 2).mean((1, 3))


def box(image, radius):
    if radius == 0:
        return image.copy()
    size = 2 * radius + 1
    p = np.pad(image, ((radius, radius), (radius, radius)), mode='reflect')
    # Float64 accumulation avoids precision loss in large dark images.
    integral = np.pad(p, ((1, 0), (1, 0))).cumsum(0, dtype=np.float64).cumsum(1)
    out = integral[size:, size:] - integral[:-size, size:] - integral[size:, :-size] + integral[:-size, :-size]
    return (out / (size * size)).astype(np.float32)


def guide(raw):
    return np.maximum(half(raw), 0)


def phase_shift(reference, alternate):
    """Translation to sample alternate in reference coordinates, without circular image warps."""
    a, b = reference, alternate
    scale = 1
    while max(a.shape) > 512:
        a, b = half(a), half(b)
        scale *= 2
    window = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1]))
    fa = np.fft.rfft2((a - a.mean()) * window)
    fb = np.fft.rfft2((b - b.mean()) * window)
    cross = fb * fa.conj()
    floor = max(float(np.abs(cross).mean()) * .1, 1e-12)
    corr = np.fft.irfft2(cross / np.maximum(np.abs(cross), floor), s=a.shape)
    y, x = np.unravel_index(corr.argmax(), corr.shape)
    shift = []
    for axis, index in enumerate((y, x)):
        left, mid, right = [float(corr[(y + q) % a.shape[0], x]) if axis == 0
                            else float(corr[y, (x + q) % a.shape[1]]) for q in (-1, 0, 1)]
        denominator = left - 2 * mid + right
        sub = 0.5 * (left - right) / denominator if abs(denominator) > 1e-12 else 0
        index = index if index <= a.shape[axis] // 2 else index - a.shape[axis]
        shift.append((index + np.clip(sub, -0.5, 0.5)) * scale)
    return np.array(shift, dtype=np.float32)


def expand_grid(grid, ys, xs, shape):
    horizontal = np.stack([np.interp(np.arange(shape[1]), xs, row) for row in grid])
    return np.stack([np.interp(np.arange(shape[0]), ys, horizontal[:, x])
                     for x in range(shape[1])], axis=1).astype(np.float32)


def align(reference, alternate, tile=64, radius=3):
    """Global translation followed by local tile translations and subpixel refinement.

    Flow uses Bayer-cell coordinates, so warping never mixes different colour sites.
    """
    initial = phase_shift(reference, alternate)
    if np.max(np.abs(initial)) > min(reference.shape) / 8:
        initial[:] = 0
    a, b = half(reference), half(alternate)
    initial /= 2
    # A repeated pattern or mostly flat/noisy image can produce a spurious phase peak.
    # Compare against zero motion on the same overlap before accepting that peak.
    iy, ix = np.rint(initial).astype(int)
    margin = max(abs(iy), abs(ix)) + 1
    target = a[margin:-margin, margin:-margin]
    shifted = b[margin + iy:b.shape[0] - margin + iy, margin + ix:b.shape[1] - margin + ix]
    if target.size and np.mean((target - shifted) ** 2) > np.mean((target - b[margin:-margin, margin:-margin]) ** 2):
        initial[:] = 0
    noise_floor = max(float(np.median(np.abs(a - box(a, 1))) / .6745) ** 2, 1e-8)
    step = max(8, tile // 2)
    ys = np.arange(step // 2, a.shape[0], step)
    xs = np.arange(step // 2, a.shape[1], step)
    dy, dx = np.empty((len(ys), len(xs))), np.empty((len(ys), len(xs)))
    rejected = 0
    for iy, cy in enumerate(ys):
        for ix, cx in enumerate(xs):
            y0, y1 = max(0, cy - step // 2), min(a.shape[0], cy + step // 2)
            x0, x1 = max(0, cx - step // 2), min(a.shape[1], cx + step // 2)
            target = a[y0:y1, x0:x1]

            def cost(y, x):
                if y0 + y < 0 or x0 + x < 0 or y1 + y > b.shape[0] or x1 + x > b.shape[1]:
                    return float('inf')
                delta = target - b[y0 + y:y1 + y, x0 + x:x1 + x]
                return float(np.mean(np.minimum(delta * delta, 0.01)))

            centre = np.rint(initial).astype(int)
            options = [(cost(y, x), y, x)
                       for y in range(centre[0] - radius, centre[0] + radius + 1)
                       for x in range(centre[1] - radius, centre[1] + radius + 1)]
            best, y, x = min(options)
            if not np.isfinite(best) or float(target.var()) < 3 * noise_floor:
                dy[iy, ix], dx[iy, ix] = initial
                rejected += 1
                continue
            values = []
            for axis in (0, 1):
                left = cost(y - 1, x) if axis == 0 else cost(y, x - 1)
                right = cost(y + 1, x) if axis == 0 else cost(y, x + 1)
                denominator = left - 2 * best + right
                sub = 0.5 * (left - right) / denominator if np.isfinite(denominator) and denominator > 1e-12 else 0
                values.append(np.clip(sub, -0.5, 0.5))
            dy[iy, ix], dx[iy, ix] = y + values[0], x + values[1]
    # Small local median rejects isolated matches on repeated text/flat patches.
    for field in (dy, dx):
        p = np.pad(field, 1, mode='edge')
        filtered = np.median(np.stack([p[y:y + field.shape[0], x:x + field.shape[1]]
                                      for y in range(3) for x in range(3)]), axis=0)
        field[:] = filtered
    flow = (expand_grid(dy * 2, ys * 2, xs * 2, reference.shape),
            expand_grid(dx * 2, ys * 2, xs * 2, reference.shape))
    return flow, {'globalShiftCells': (initial * 2).tolist(), 'flatOrInvalidTiles': rejected,
                  'tiles': int(dy.size), 'medianShiftCells': [float(np.median(dy) * 2), float(np.median(dx) * 2)]}


def warp(image, flow):
    y, x = np.indices(image.shape, dtype=np.float32)
    y += flow[0]
    x += flow[1]
    valid = (y >= 0) & (y <= image.shape[0] - 1) & (x >= 0) & (x <= image.shape[1] - 1)
    y = np.clip(y, 0, image.shape[0] - 1)
    x = np.clip(x, 0, image.shape[1] - 1)
    yi, xi = y.astype(np.int32), x.astype(np.int32)
    yn, xn = np.minimum(yi + 1, image.shape[0] - 1), np.minimum(xi + 1, image.shape[1] - 1)
    fy, fx = y - yi, x - xi
    output = ((1 - fy) * ((1 - fx) * image[yi, xi] + fx * image[yi, xn])
              + fy * ((1 - fx) * image[yn, xi] + fx * image[yn, xn]))
    return output.astype(np.float32), valid


def gyro_score(frame, gyro):
    start = int(frame['timestampNs'])
    duration = int(frame['exposureNs'])
    samples = [sum(number(v) ** 2 for v in s[1:4]) for s in gyro.get('samples', [])
               if start <= int(s[0]) <= start + duration]
    return math.sqrt(sum(samples) / len(samples)) * duration / 1e9 if samples else None


def merge(frames, read, static, gyro, reference=None):
    scores = [gyro_score(f, gyro) for f in frames]
    if reference is None:
        reference = min(range(len(frames)), key=lambda i: scores[i] if scores[i] is not None else abs(i - len(frames) // 2))
    ref = normalize(read(reference), frames[reference], static)
    ref_guide = guide(ref)
    accumulator, weight = ref.copy(), np.ones(ref.shape, dtype=np.float32)
    report = []
    for i, metadata in enumerate(frames):
        if i == reference:
            continue
        image = normalize(read(i), metadata, static)
        flow, diagnostics = align(ref_guide, guide(image))
        fractions = []
        for y in range(2):
            for x in range(2):
                plane, valid = warp(image[y::2, x::2], flow)
                target = ref[y::2, x::2]
                # Noise model is in normalized sensor units. Conservative 3-sigma gating keeps
                # static noise samples and falls back to the reference at moving boundaries.
                colour = CFA[int(static['cfa'])][2 * y + x]
                profile_index = 0 if colour == 0 else 3 if colour == 2 else (1 if y == 0 else 2)
                profile = metadata.get('noiseProfile')
                ref_profile = frames[reference].get('noiseProfile')
                if not profile or not ref_profile:
                    raise ValueError('a measured sensor noise profile is required')
                s, o = map(number, profile[profile_index])
                rs, ro = map(number, ref_profile[profile_index])
                variance = np.maximum((s + rs) * np.maximum(target, 0) + o + ro, 1e-8)
                delta = plane - target
                # Low-pass residual detects scene motion without treating fine sensor noise as it.
                residual = box(delta, 1)
                motion = np.minimum(1, (3 * 3 * variance / 9) / np.maximum(residual * residual, 1e-12))
                robust = np.minimum(1, 9 * variance / np.maximum(delta * delta, 1e-12))
                w = (valid & (plane < 0.995) & (target < 0.995)) * motion * robust
                accumulator[y::2, x::2] += plane * w
                weight[y::2, x::2] += w
                fractions.append(float(np.mean(w)))
        diagnostics['frame'] = i
        diagnostics['meanAlternateWeight'] = sum(fractions) / 4
        report.append(diagnostics)
        print(f'merged frame {i + 1}/{len(frames)}', flush=True)
    return accumulator / weight, ref, {'referenceIndex': reference, 'gyroBlurScores': scores,
                                       'alignment': report, 'meanEffectiveFrames': float(weight.mean())}


def convolution(image, kernel):
    radius = len(kernel) // 2
    p = np.pad(image, radius, mode='reflect')
    result = np.zeros_like(image)
    for y, row in enumerate(kernel):
        for x, k in enumerate(row):
            if k:
                result += np.float32(k) * p[y:y + image.shape[0], x:x + image.shape[1]]
    return result


def demosaic(raw, cfa):
    """Malvar-He-Cutler linear filters, with measured colour sites preserved exactly."""
    g = np.array([[0, 0, -1, 0, 0], [0, 0, 2, 0, 0], [-1, 2, 4, 2, -1],
                  [0, 0, 2, 0, 0], [0, 0, -1, 0, 0]], dtype=np.float32) / 8
    h = np.array([[0, 0, .5, 0, 0], [0, -1, 0, -1, 0], [-1, 4, 5, 4, -1],
                  [0, -1, 0, -1, 0], [0, 0, .5, 0, 0]], dtype=np.float32) / 8
    cross = np.array([[0, 0, -1.5, 0, 0], [0, 2, 0, 2, 0], [-1.5, 0, 6, 0, -1.5],
                      [0, 2, 0, 2, 0], [0, 0, -1.5, 0, 0]], dtype=np.float32) / 8
    rgb = np.empty((*raw.shape, 3), dtype=np.float32)
    green, horizontal, vertical, opposite = (convolution(raw, k) for k in (g, h, h.T, cross))
    pattern = np.array(CFA[cfa]).reshape(2, 2)
    for y in range(2):
        for x in range(2):
            colour = pattern[y, x]
            for c in range(3):
                if c == colour:
                    source = raw
                elif c == 1:
                    source = green
                elif colour != 1:
                    source = opposite
                else:
                    source = horizontal if pattern[y, 1 - x] == c else vertical
                rgb[y::2, x::2, c] = source[y::2, x::2]
    return rgb


def color_matrix(dng):
    # DNG ColorMatrix maps XYZ into camera space. Adapt the captured neutral into D65
    # before the standard XYZ -> linear sRGB transform. Do not mistake an identity
    # CaptureResult correction matrix for sensor calibration.
    neutral = np.array(dng.tags[50728], dtype=np.float64)
    matrix = np.array(dng.tags[50721], dtype=np.float64).reshape(3, 3)
    calibration1 = np.array(dng.tags.get(50723, np.eye(3).ravel()), dtype=np.float64).reshape(3, 3)
    calibration2 = np.array(dng.tags.get(50724, calibration1.ravel()), dtype=np.float64).reshape(3, 3)
    analog = np.diag(np.array(dng.tags.get(50727, [1, 1, 1]), dtype=np.float64))
    fraction = 0
    if 50722 in dng.tags:
        second = np.array(dng.tags[50722], dtype=np.float64).reshape(3, 3)
        temperatures = {17: 2856, 20: 5503, 21: 6504, 22: 7504, 23: 5003, 24: 3200}
        t1 = temperatures.get(int(dng.one(50778)))
        t2 = temperatures.get(int(dng.one(50779)))
        if t1 is None or t2 is None or t1 == t2:
            raise ValueError('unsupported dual-illuminant calibration')
        fraction = .5
        first = matrix.copy()
        # Estimate the captured neutral's CCT and interpolate calibration matrices in
        # reciprocal temperature. Using the tungsten matrix for every scene is incorrect.
        for _ in range(16):
            matrix = first * (1 - fraction) + second * fraction
            calibration = calibration1 * (1 - fraction) + calibration2 * fraction
            white = np.linalg.solve(analog @ calibration @ matrix, neutral)
            xy = white[:2] / white.sum()
            denominator = xy[1] - .1858
            if abs(denominator) < 1e-8:
                raise ValueError('invalid estimated white chromaticity')
            n = (xy[0] - .332) / denominator
            cct = float(np.clip(-449 * n ** 3 + 3525 * n ** 2 - 6823.3 * n + 5520.33, 2000, 12000))
            target = float(np.clip((1 / cct - 1 / t1) / (1 / t2 - 1 / t1), 0, 1))
            if abs(target - fraction) < 1e-5:
                fraction = target
                break
            fraction = (fraction + target) / 2
        matrix = first * (1 - fraction) + second * fraction
    if not np.isfinite(matrix).all() or not np.isfinite(neutral).all() or np.any(neutral <= 0):
        raise ValueError('invalid DNG colour calibration')
    calibration = calibration1 * (1 - fraction) + calibration2 * fraction
    individual_to_reference = np.linalg.inv(analog @ calibration)
    inverse = np.linalg.inv(matrix) @ individual_to_reference
    source_white = inverse @ neutral
    if np.any(source_white <= 0):
        raise ValueError('invalid calibrated white')
    source_white /= source_white[1]
    bradford = np.array([[.8951, .2664, -.1614], [-.7502, 1.7135, .0367], [.0389, -.0685, 1.0296]])
    d65 = np.array([.95047, 1, 1.08883])
    if 50964 in dng.tags:
        # Follow DNG's reference-camera transform when a ForwardMatrix is supplied.
        # It maps white-balanced reference camera values into XYZ D50. This preserves
        # the device's intended colour rendering beyond a generic inverse ColorMatrix.
        forward1 = np.array(dng.tags[50964], dtype=np.float64).reshape(3, 3)
        forward2 = np.array(dng.tags.get(50965, forward1.ravel()), dtype=np.float64).reshape(3, 3)
        forward = forward1 * (1 - fraction) + forward2 * fraction
        d50 = np.array([.96422, 1, .82521])
        sums = forward.sum(axis=1)
        if np.any(np.abs(sums) < 1e-8):
            raise ValueError('invalid ForwardMatrix')
        forward = np.diag(d50 / sums) @ forward
        reference_neutral = individual_to_reference @ neutral
        if np.any(reference_neutral <= 0):
            raise ValueError('invalid reference camera neutral')
        inverse = forward @ np.diag(1 / reference_neutral) @ individual_to_reference
        source_white = d50
    adaptation = np.linalg.inv(bradford) @ np.diag((bradford @ d65) / (bradford @ source_white)) @ bradford
    xyz_to_rgb = np.array([[3.2404542, -1.5371385, -.4985314], [-.969266, 1.8760108, .041556],
                           [.0556434, -.2040259, 1.0572252]])
    result = xyz_to_rgb @ adaptation @ inverse
    result /= (result @ neutral)[1]
    return result.astype(np.float32)


def finish(raw, cfa, matrix, exposure=1.0):
    rgb = demosaic(raw, cfa) @ matrix.T
    rgb = np.maximum(rgb * exposure, 0)
    # A fixed, bounded global shoulder for the first reference. Local tone mapping and
    # denoise tuning follow measured ROI comparisons, rather than hiding merge defects.
    rgb = rgb / (1 + rgb)
    rgb = np.where(rgb <= .0031308, rgb * 12.92, 1.055 * np.power(rgb, 1 / 2.4) - .055)
    return Image.fromarray(np.round(np.clip(rgb, 0, 1) * 255).astype(np.uint8))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--exposure', type=float, default=1.0)
    parser.add_argument('--reference', type=int)
    args = parser.parse_args()
    if not math.isfinite(args.exposure) or not .125 <= args.exposure <= 32:
        parser.error('exposure must be between .125 and 32')
    root = args.run.resolve()
    data, legacy_rationals = load_metadata(root / 'result.json')
    frames = data['frames']
    if data.get('status') != 'ok' or data.get('failures') or not 2 <= len(frames) <= 15:
        raise ValueError('requires a complete successful burst of 2 to 15 frames')
    if args.reference is not None and not 0 <= args.reference < len(frames):
        parser.error('reference frame outside burst')
    first = frames[0]
    for frame in frames:
        if not math.isclose(number(frame['exposureNs']), number(first['exposureNs']), rel_tol=.01) or frame['iso'] != first['iso']:
            raise ValueError('requires a burst at fixed exposure and sensitivity')
    readers = []
    for frame in frames:
        path = (root / frame['file']).resolve()
        if path.parent != root:
            raise ValueError('frame path outside burst directory')
        readers.append(Dng(path))
    if len({(r.width, r.height, r.tags[33422]) for r in readers}) != 1:
        raise ValueError('inconsistent burst dimensions or CFA')
    cfa = int(data['static']['cfa'])
    if not 0 <= cfa <= 3 or tuple(readers[0].tags[33422]) != CFA[cfa]:
        raise ValueError('DNG and capture CFA disagree')
    args.output.mkdir(parents=True, exist_ok=False)
    start = time.monotonic()
    merged, reference, report = merge(frames, lambda i: readers[i].read(), data['static'], data['gyro'], args.reference)
    print('finishing merged and reference images', flush=True)
    matrix = color_matrix(readers[report['referenceIndex']])
    for name, raw in [('merged', merged), ('reference', reference)]:
        np.save(args.output / f'{name}-linear.npy', raw)
        image = finish(raw, cfa, matrix, args.exposure)
        image.save(args.output / f'{name}.png')
        image.save(args.output / f'{name}.jpg', quality=95, subsampling=0)
    report.update({'algorithm': 'offline-reference-v1', 'frames': len(frames), 'size': data['rawSize'],
                   'exposureGain': args.exposure, 'seconds': time.monotonic() - start,
                   'sourceSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   'metadataSha256': hashlib.sha256((root / 'result.json').read_bytes()).hexdigest(),
                   'legacyRationalsQuoted': legacy_rationals,
                   'inputSha256': [hashlib.sha256(r.path.read_bytes()).hexdigest() for r in readers],
                   'limits': ['global and local translation, no rotation model',
                              'fixed global shoulder, local tone mapping not yet implemented',
                              'host reference, no device performance or quality acceptance']})
    (args.output / 'metrics.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ['referenceIndex', 'meanEffectiveFrames', 'seconds']}))


if __name__ == '__main__':
    main()
