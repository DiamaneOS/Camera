package app.grapheneos.camera.capturer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class RawColorTransformTest {
    private static final double[] COLOR = {.7, -.2, -.05, -.3, 1.1, .1, -.05, .1, .5};
    private static final double[] NEUTRAL = {.5, 1, .6};

    private void checkNeutral(float[] matrix) {
        for (int y = 0; y < 3; y++) {
            double value = 0;
            for (int x = 0; x < 3; x++) value += matrix[y * 3 + x] * NEUTRAL[x];
            assertEquals(1, value, .001);
        }
    }

    @Test public void inverseColourPathPreservesCapturedNeutral() {
        RawColorTransform.Profile profile = new RawColorTransform.Profile(COLOR, null, null, null, null, null, null, 21, 0);
        checkNeutral(RawColorTransform.compute(profile, NEUTRAL));
    }

    @Test public void forwardPathHonoursIndividualCalibrationAndWhite() {
        double[] forward = {.7, .1, .16422, .2, .7, .1, .05, .1, .67521};
        double[] calibration = {1.1, 0, 0, 0, 1, 0, 0, 0, .9};
        RawColorTransform.Profile profile = new RawColorTransform.Profile(COLOR, null, forward, null, calibration, null, null, 21, 0);
        checkNeutral(RawColorTransform.compute(profile, NEUTRAL));
    }

    @Test public void captureMappingAppliesGainsBeforeColourMixing() {
        double[] correction = {.8, .2, 0, -.1, 1.2, -.1, 0, -.2, 1.2};
        float[] result = RawColorTransform.captureMatrix(correction, new double[] {2, 1, 4});
        double[] neutral = {.5, 1, .25};
        for (int y = 0; y < 3; y++) {
            double value = 0;
            for (int x = 0; x < 3; x++) value += result[y * 3 + x] * neutral[x];
            assertEquals(1, value, .00001);
        }
        assertThrows(IllegalArgumentException.class, () -> RawColorTransform.captureMatrix(correction, new double[] {0, 1, 4}));
    }

    @Test public void malformedCalibrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RawColorTransform.Profile(new double[8], null, null, null, null, null, null, 21, 0));
        RawColorTransform.Profile profile = new RawColorTransform.Profile(COLOR, null, null, null, null, null, null, 21, 0);
        assertThrows(IllegalArgumentException.class, () -> RawColorTransform.compute(profile, new double[] {0, 1, .6}));
        assertThrows(IllegalArgumentException.class, () -> RawColorTransform.compute(profile, new double[] {Double.NaN, 1, .6}));
    }
}
