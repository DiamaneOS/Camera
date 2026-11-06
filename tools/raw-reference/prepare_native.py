#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Prepare private, hash-pinned inputs for the isolated native processor benchmark."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import burst

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('run', type=Path)
p.add_argument('output', type=Path)
p.add_argument('--exposure', type=float, default=1)
a = p.parse_args()
data, legacy = burst.load_metadata(a.run / 'result.json')
frames = data['frames']
if data.get('status') != 'ok' or data.get('failures') or not 2 <= len(frames) <= 8:
    raise ValueError('requires a successful fixed-exposure burst of 2 to 8 frames')
if any(f['iso'] != frames[0]['iso'] or abs(f['exposureNs'] / frames[0]['exposureNs'] - 1) > .01 for f in frames):
    raise ValueError('burst exposure or sensitivity varies')
scores = [burst.gyro_score(f, data['gyro']) for f in frames]
reference = min(range(len(frames)), key=lambda i: scores[i] if scores[i] is not None else abs(i - len(frames) // 2))
readers = [burst.Dng(a.run / f['file']) for f in frames]
if len({(r.width, r.height, r.tags[33422]) for r in readers}) != 1:
    raise ValueError('inconsistent sensor frames')
cfa = int(data['static']['cfa'])
if cfa not in range(4) or tuple(readers[0].tags[33422]) != burst.CFA[cfa]:
    raise ValueError('inconsistent CFA metadata')
a.output.mkdir(parents=True, exist_ok=False)
plan = {'width': readers[0].width, 'height': readers[0].height, 'cfa': cfa, 'reference': reference,
        'exposure': a.exposure, 'color': burst.color_matrix(readers[reference]).ravel().tolist(),
        'metadataSha256': hashlib.sha256((a.run / 'result.json').read_bytes()).hexdigest(),
        'legacyRationalsQuoted': legacy, 'frames': []}
for i, (frame, reader) in enumerate(zip(frames, readers)):
    name = f'frame-{i:02d}.bin'
    pixels = reader.read().astype('<u2', copy=False)
    pixels.tofile(a.output / name)
    dynamic = frame.get('dynamicBlackLevel')
    white = frame.get('dynamicWhiteLevel', data['static']['whiteLevel'])
    black, noise = [], []
    for y in range(2):
        for x in range(2):
            site = y * 2 + x
            black.append(burst.number(dynamic[site] if dynamic else data['static']['blackLevelPattern'][site]))
            noise.extend(burst.number(v) for v in frame['noiseProfile'][site])
    plan['frames'].append({'file': name, 'stride': reader.width * 2, 'calibration': black + [white] + noise,
                           'sha256': hashlib.sha256((a.output / name).read_bytes()).hexdigest()})
(a.output / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
print(json.dumps({'frames': len(frames), 'width': plan['width'], 'height': plan['height'], 'reference': reference,
                  'totalBytes': sum((a.output / f['file']).stat().st_size for f in plan['frames'])}))
