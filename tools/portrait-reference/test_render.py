# SPDX-License-Identifier: MIT
import importlib.util
from pathlib import Path
import unittest
import numpy as np

spec = importlib.util.spec_from_file_location('render', Path(__file__).with_name('render.py'))
render = importlib.util.module_from_spec(spec)
spec.loader.exec_module(render)


class RenderTests(unittest.TestCase):
    def test_foreground_remains_unchanged(self):
        rng = np.random.default_rng(1)
        image = rng.random((64, 80, 3), dtype=np.float32)
        result, _ = render.render(image, np.ones((32, 40), np.float32), blur=3)
        np.testing.assert_allclose(result, image, atol=1e-6)

    def test_constant_background_has_no_colour_shift(self):
        image = np.empty((64, 80, 3), np.float32)
        image[:] = [.1, .3, .7]
        result, _ = render.render(image, np.zeros((32, 40), np.float32), blur=3)
        np.testing.assert_allclose(result, image, atol=1e-5)

    def test_foreground_colour_does_not_bleed_into_background(self):
        image = np.zeros((96, 96, 3), np.float32)
        image[:] = [.05, .05, .05]
        image[24:72, 24:72] = [1, 0, 0]
        mask = np.zeros((96, 96), np.float32)
        mask[24:72, 24:72] = 1
        result, alpha = render.render(image, mask, blur=8)
        background = alpha < .001
        self.assertLess(float(result[..., 0][background].max()), .08)


if __name__ == '__main__':
    unittest.main()
