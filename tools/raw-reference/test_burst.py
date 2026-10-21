# SPDX-License-Identifier: MIT
import importlib.util
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

import numpy as np

spec = importlib.util.spec_from_file_location('burst', Path(__file__).with_name('burst.py'))
burst = importlib.util.module_from_spec(spec)
spec.loader.exec_module(burst)


class BurstTests(unittest.TestCase):
    def test_phase_shift_direction(self):
        rng = np.random.default_rng(1)
        a = rng.random((160, 192), dtype=np.float32)
        b = np.roll(a, (6, -4), (0, 1))
        np.testing.assert_allclose(burst.phase_shift(a, b), (6, -4), atol=.1)

    def test_warp_does_not_wrap(self):
        a = np.arange(100, dtype=np.float32).reshape(10, 10)
        out, valid = burst.warp(a, (np.zeros_like(a), np.ones_like(a) * 3))
        np.testing.assert_allclose(out[:, :6], a[:, 3:9])
        self.assertFalse(valid[:, -3:].any())

    def test_demosaic_keeps_measured_sites_for_every_pattern(self):
        rng = np.random.default_rng(2)
        raw = rng.random((32, 40), dtype=np.float32)
        for cfa, pattern in enumerate(burst.CFA):
            rgb = burst.demosaic(raw, cfa)
            for y in range(2):
                for x in range(2):
                    np.testing.assert_array_equal(rgb[y::2, x::2, pattern[2 * y + x]], raw[y::2, x::2])

    def test_demosaic_constant_colour(self):
        raw = np.empty((32, 40), dtype=np.float32)
        colours = np.array([.2, .3, .4], dtype=np.float32)
        for y in range(2):
            for x in range(2):
                raw[y::2, x::2] = colours[burst.CFA[0][2 * y + x]]
        rgb = burst.demosaic(raw, 0)
        np.testing.assert_allclose(rgb[3:-3, 3:-3], np.broadcast_to(colours, rgb[3:-3, 3:-3].shape), atol=1e-6)

    def test_black_level_and_white(self):
        levels = [61, 62, 63, 64]
        raw = np.tile(np.array(levels).reshape(2, 2), (10, 10))
        out = burst.normalize(raw, {'dynamicBlackLevel': levels, 'dynamicWhiteLevel': 1023}, {})
        np.testing.assert_array_equal(out, 0)
        with self.assertRaises(ValueError):
            burst.normalize(raw, {'dynamicBlackLevel': [1023] * 4, 'dynamicWhiteLevel': 1023}, {})

    def test_burst_reduces_flat_noise_and_keeps_signal(self):
        rng = np.random.default_rng(3)
        y, x = np.indices((192, 256))
        truth = np.full((192, 256), .2, dtype=np.float32)
        truth[:70] += .08 * ((x[:70] // 12 + y[:70] // 12) % 2)
        raw = [(truth + rng.normal(0, .01, truth.shape)) * 959 + 64 for _ in range(8)]
        meta = [dict(timestampNs=i * 100, exposureNs=50, noiseProfile=[[0, .0001]] * 4) for i in range(8)]
        merged, reference, report = burst.merge(meta, lambda i: raw[i],
                                               dict(blackLevelPattern=[64] * 4, whiteLevel=1023, cfa=0), {}, 0)
        roi = np.s_[100:160, 70:180]
        self.assertLess(merged[roi].std() / reference[roi].std(), .55)
        self.assertLess(abs(merged[roi].mean() - .2), .002)
        self.assertGreater(report['meanEffectiveFrames'], 5)

    def test_motion_falls_back_to_reference(self):
        shape = (128, 160)
        target = np.full(shape, .2, dtype=np.float32)
        alternate = target.copy()
        alternate[40:70, 50:90] = .8
        # Exercise merge rejection independently of the alignment estimator, which is not
        # identifiable on an otherwise completely uniform synthetic image.
        original_align = burst.align
        burst.align = lambda a, b: ((np.zeros_like(a), np.zeros_like(a)), {})
        try:
            raw = [target * 959 + 64, alternate * 959 + 64]
            meta = [dict(timestampNs=0, exposureNs=1, noiseProfile=[[0, 1e-6]] * 4)] * 2
            merged, _, _ = burst.merge(meta, lambda i: raw[i],
                                       dict(blackLevelPattern=[64] * 4, whiteLevel=1023, cfa=0), {}, 0)
            self.assertLess(float(merged[46:64, 56:84].max()), .201)
        finally:
            burst.align = original_align

    def test_invalid_dng_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'bad.dng'
            p.write_bytes(b'II*\x00\xff\xff\xff\xff')
            with self.assertRaises(ValueError):
                burst.Dng(p)

    def test_calibrated_neutral_maps_to_srgb_white(self):
        d = SimpleNamespace(tags={50721: [.7, -.2, -.05, -.3, 1.1, .1, -.05, .1, .5],
                                  50728: [.5, 1, .6]})
        rgb = burst.color_matrix(d) @ np.array(d.tags[50728])
        np.testing.assert_allclose(rgb, 1, atol=.001)

    def test_legacy_rationals_do_not_change_strings_or_source_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.json'
            original = '{"neutral":[1/2,3/4],"literal":"example 1/2, unchanged"}'
            path.write_text(original)
            result, count = burst.load_metadata(path)
            self.assertEqual(count, 2)
            self.assertEqual(result['neutral'], ['1/2', '3/4'])
            self.assertEqual(result['literal'], 'example 1/2, unchanged')
            self.assertEqual(path.read_text(), original)

    def test_forward_matrix_and_individual_calibration_preserve_neutral(self):
        d = SimpleNamespace(tags={50721: [.7, -.2, -.05, -.3, 1.1, .1, -.05, .1, .5],
                                  50723: [1.1, 0, 0, 0, 1, 0, 0, 0, .9],
                                  50728: [.5, 1, .6],
                                  50964: [.7, .1, .16422, .2, .7, .1, .05, .1, .67521]})
        rgb = burst.color_matrix(d) @ np.array(d.tags[50728])
        np.testing.assert_allclose(rgb, 1, atol=.001)


if __name__ == '__main__':
    unittest.main()
