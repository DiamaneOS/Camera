from pathlib import Path
from PIL import Image
import subprocess, json, time
import numpy as np
from fixture_geometry import warp, luma

root = Path(__file__).parent
work = root / 'native-fixtures'
work.mkdir(exist_ok=True)
h, w = 384, 512
yy, xx = np.indices((h, w), dtype=np.float32)
colour = np.stack((80+.08*xx+12*np.sin(xx/15), 90+.06*yy+10*np.cos(yy/13), 70+.04*xx+8*np.sin((xx+yy)/17)), axis=-1)
colour[xx > 260+.12*yy] += 65
colour[30:350, 81:83] = [235, 30, 25]
results = []
for grey in [False, True]:
    for large in [False, True]:
        shifts = [(0, 0), (8.5, -4.25), (-12.75, 8.75), (24.25, 12.5)] if large else [(0, 0), (.5, -.5), (-.75, .25), (1.5, .75)]
        for sigma in [5., 25., 55.]:
            for moving in [False, True]:
                base = np.repeat(luma(colour)[..., None], 3, axis=2) if grey else colour.copy()
                base[30:350, 121:123] = 235
                frames, truths, paths = [], [], []
                for index, (dx, dy) in enumerate(shifts):
                    truth = base.copy()
                    if moving:
                        left = 170+index*12
                        truth[160:200, left:left+40] = [230]*3 if grey else [230, 55, 35]
                    truth, _ = warp(truth, dx, dy)
                    rng = np.random.default_rng(471+index)
                    y = rng.normal(0, sigma, (h, w))
                    co = rng.normal(0, sigma*.12, (h, w))
                    cg = rng.normal(0, sigma*.075, (h, w))
                    noise = np.stack((y+co-cg, y+cg, y-co-cg), axis=-1)
                    frame = np.rint(truth+noise).clip(0, 255).astype(np.uint8)
                    path = work / ('input-' + str(index) + '.ppm')
                    Image.fromarray(frame).save(path)
                    frames.append(frame); truths.append(truth); paths.append(path)
                path = work / 'output.ppm'
                began = time.monotonic()
                result = subprocess.run([str(root/'jpegburst_cli'), str(path), *map(str, paths)], capture_output=True)
                assert result.returncode == 0, result.stderr.decode()
                stats = json.loads(result.stdout)
                output = np.array(Image.open(path).convert('RGB'), dtype=float)
                region = np.s_[32:-32, 32:-32]
                before = float(np.mean((frames[0][region].astype(float)-truths[0][region])**2))
                after = float(np.mean((output[region]-truths[0][region])**2))
                def grey_contrast(rgb):
                    field = luma(rgb.astype(float))
                    return float(field[50:330, 121:123].mean() - .5*(field[50:330, 117:119].mean()+field[50:330, 125:127].mean()))
                grey_retention = grey_contrast(output)/grey_contrast(frames[0])
                # A different random noise pedestal in nearby background is not
                # lost line detail. Compare both line signals against the same
                # known clean pedestal, retaining the observed local contrast
                # separately. Clipped noisy reference remains the detail anchor.
                truth_y = luma(truths[0].astype(float))
                pedestal = .5*(truth_y[50:330, 117:119].mean()+truth_y[50:330, 125:127].mean())
                signal_retention = float((luma(output)[50:330, 121:123].mean()-pedestal)/
                    (luma(frames[0].astype(float))[50:330, 121:123].mean()-pedestal))
                colour_retention = None
                if not grey:
                    original = frames[0][50:330, 81:83].mean(axis=(0, 1))
                    actual = output[50:330, 81:83].mean(axis=(0, 1))
                    colour_retention = float((actual[0]-actual[1])/(original[0]-original[1]))
                errors = [float(np.linalg.norm(np.array(measured[:2])+np.array(known)))
                    if measured[2] is not None and measured[2] <= .1 else None
                    for measured, known in zip(stats['registration'], shifts[1:])]
                motion_ratio = None
                if moving:
                    roi = np.s_[148:212, 158:258]
                    old = float(np.mean((frames[0][roi].astype(float)-truths[0][roi])**2))
                    new = float(np.mean((output[roi]-truths[0][roi])**2))
                    motion_ratio = new/old
                row = {'greyScene': grey, 'largeCameraMotion': large, 'sigma': sigma, 'movingForeground': moving,
                    'mseRatio': after/before, 'thinGreyContrastVersusReference': grey_retention,
                    'thinColourContrastVersusReference': colour_retention, 'thinGreySignalVersusReference': signal_retention, 'acceptedRegistrationErrorsPixels': errors,
                    'motionRegionMseRatio': motion_ratio, 'hostSeconds': time.monotonic()-began, **stats}
                results.append(row)
                print(json.dumps(row), flush=True)
out = {'cases': results, 'scope': 'Native host prototype, same deterministic synthetic phantom family as Python. Reference fallback is explicit; no phone performance or production acceptance.'}
(root/'native-general-results.json').write_text(json.dumps(out, indent=2)+'\n')
failed = [r for r in results if r['mseRatio'] > 1.05 or r['thinGreySignalVersusReference'] < .98 or
    (r['thinColourContrastVersusReference'] is not None and r['thinColourContrastVersusReference'] < .98) or
    max([v for v in r['acceptedRegistrationErrorsPixels'] if v is not None] or [0]) > .5 or
    (r['motionRegionMseRatio'] is not None and r['motionRegionMseRatio'] > 1.10) or
    (not r['greyScene'] and (r['effectiveFrames'] < 2.5 or r['mseRatio'] > .65))]
print('Native general fidelity:', len(results)-len(failed), '/', len(results), 'passed')
assert not failed, 'Native merger failed a proposed fidelity gate'
