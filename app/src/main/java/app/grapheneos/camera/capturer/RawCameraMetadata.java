package app.grapheneos.camera.capturer;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.util.Pair;
import android.util.Rational;

/** Whitelisted calibration only. No identifiers, vendor metadata, or calibration blobs are read. */
public final class RawCameraMetadata {
    private static final int[][] PATTERNS = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};

    public static RawColorTransform.Profile profile(CameraCharacteristics camera) {
        Number first = camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Number second = camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        return new RawColorTransform.Profile(
            matrix(camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
            matrix(camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
            matrix(camera.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)),
            matrix(camera.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)),
            matrix(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
            matrix(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
            null, first == null ? 0 : first.intValue(), second == null ? 0 : second.intValue());
    }

    public static float[] color(CameraCharacteristics camera, CaptureResult result) {
        Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (neutral == null || neutral.length != 3) throw new IllegalArgumentException("missing RAW neutral");
        return RawColorTransform.compute(profile(camera), new double[] {
            neutral[0].doubleValue(), neutral[1].doubleValue(), neutral[2].doubleValue()});
    }

    public static float[] captureColor(CaptureResult result) {
        ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        android.hardware.camera2.params.RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (transform == null || gains == null) return null;
        double even = gains.getGreenEven(), odd = gains.getGreenOdd();
        if (Math.abs(even - odd) > .05 * Math.max(even, odd)) return null;
        return RawColorTransform.captureMatrix(matrix(transform), new double[] {gains.getRed(), (even + odd) * .5, gains.getBlue()});
    }

    /** Optional remaining map, including when the HAL already applies partial shading. */
    public static RawShadingData.Map shading(CameraCharacteristics camera, CaptureResult result, int width, int height) {
        android.hardware.camera2.params.LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (map == null) return null;
        android.util.Size pixels = camera.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        android.graphics.Rect active = camera.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        // A resized/cropped RAW stream needs a separately verified sensor-coordinate mapping.
        // Preserve the current fallback rather than guessing that geometry.
        if (pixels == null || pixels.getWidth() != width || pixels.getHeight() != height || active == null) return null;
        int rows = map.getRowCount(), columns = map.getColumnCount();
        if (rows < 1 || rows > 64 || columns < 1 || columns > 64) return null;
        float[] values = new float[rows * columns * 4];
        int at = 0;
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) for (int channel = 0; channel < 4; channel++) {
            float value = map.getGainFactor(channel, x, y);
            if (!Float.isFinite(value) || value < 1 || value > 32) return null;
            values[at++] = value;
        }
        return new RawShadingData.Map(columns, rows, active.left, active.top, active.right, active.bottom, values);
    }

    // Four black levels, white, and four slope/offset pairs, all in Bayer row order.
    public static float[] calibration(CameraCharacteristics camera, CaptureResult result) {
        Integer cfa = camera.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa == null || cfa < 0 || cfa > 3) throw new IllegalArgumentException("unsupported RAW colour arrangement");
        float[] black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        BlackLevelPattern staticBlack = camera.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        Integer white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (white == null) white = camera.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        Pair<Double, Double>[] noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (white == null || noise == null || noise.length != 4 || (black == null && staticBlack == null)) {
            throw new IllegalArgumentException("missing RAW calibration or noise profile");
        }
        float[] sites=new float[4];double[] coefficients=new double[8];
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
            int site=y*2+x;
            sites[site]=black!=null?black[site]:staticBlack.getOffsetForIndex(x,y);
            coefficients[site*2]=noise[site].first;coefficients[site*2+1]=noise[site].second;
        }
        return RawCalibration.encode(sites,white,coefficients);
    }

    private static double[] matrix(ColorSpaceTransform transform) {
        if (transform == null) return null;
        double[] values = new double[9];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) values[y * 3 + x] = transform.getElement(x, y).doubleValue();
        return values;
    }
}
