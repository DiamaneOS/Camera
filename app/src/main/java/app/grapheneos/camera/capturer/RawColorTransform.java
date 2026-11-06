package app.grapheneos.camera.capturer;

/** DNG reference-camera colour transform using capture neutral and public sensor calibration. */
public final class RawColorTransform {
    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final double[] BRADFORD = {.8951, .2664, -.1614, -.7502, 1.7135, .0367, .0389, -.0685, 1.0296};
    private static final double[] XYZ_TO_RGB = {3.2404542, -1.5371385, -.4985314, -.969266, 1.8760108, .041556, .0556434, -.2040259, 1.0572252};
    private static final double[] D50 = {.96422, 1, .82521};
    private static final double[] D65 = {.95047, 1, 1.08883};

    public static final class Profile {
        private final double[] color1, color2, forward1, forward2, calibration1, calibration2, analog;
        private final int illuminant1, illuminant2;

        public Profile(double[] color1, double[] color2, double[] forward1, double[] forward2,
                       double[] calibration1, double[] calibration2, double[] analog,
                       int illuminant1, int illuminant2) {
            this.color1 = checked(color1, 9);
            this.color2 = color2 == null ? null : checked(color2, 9);
            this.forward1 = forward1 == null ? null : checked(forward1, 9);
            this.forward2 = forward2 == null ? this.forward1 : checked(forward2, 9);
            this.calibration1 = calibration1 == null ? IDENTITY.clone() : checked(calibration1, 9);
            this.calibration2 = calibration2 == null ? this.calibration1 : checked(calibration2, 9);
            this.analog = analog == null ? new double[] {1, 1, 1} : checked(analog, 3);
            for (double value : this.analog) if (value <= 0) throw new IllegalArgumentException("invalid analog balance");
            this.illuminant1 = illuminant1;
            this.illuminant2 = illuminant2;
        }
    }

    /** Public Camera2 mapping: white-balance gains followed by the reported linear-sRGB matrix. */
    public static float[] captureMatrix(double[] correction, double[] gains) {
        double[] matrix = checked(correction, 9), balance = checked(gains, 3);
        for (double gain : balance) if (gain <= 0) throw new IllegalArgumentException("invalid capture gains");
        float[] output = new float[9];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            double value = matrix[y * 3 + x] * balance[x];
            if (!Double.isFinite(value) || Math.abs(value) > 32) throw new IllegalArgumentException("unstable capture transform");
            output[y * 3 + x] = (float) value;
        }
        return output;
    }

    public static float[] compute(Profile profile, double[] capturedNeutral) {
        double[] neutral = checked(capturedNeutral, 3);
        double maximum = Math.max(neutral[0], Math.max(neutral[1], neutral[2]));
        for (int c = 0; c < 3; c++) {
            if (neutral[c] <= 0) throw new IllegalArgumentException("invalid captured neutral");
            neutral[c] /= maximum;
        }
        double fraction = 0;
        if (profile.color2 != null) {
            double first = temperature(profile.illuminant1), second = temperature(profile.illuminant2);
            if (first == second) throw new IllegalArgumentException("duplicate calibration illuminants");
            fraction = .5;
            for (int iteration = 0; iteration < 16; iteration++) {
                double[] color = interpolate(profile.color1, profile.color2, fraction);
                double[] calibration = interpolate(profile.calibration1, profile.calibration2, fraction);
                double[] xyz = vector(inverse(multiply(multiply(diagonal(profile.analog), calibration), color)), neutral);
                double sum = xyz[0] + xyz[1] + xyz[2];
                if (!(sum > 0)) throw new IllegalArgumentException("invalid estimated white");
                double x = xyz[0] / sum, y = xyz[1] / sum;
                if (Math.abs(y - .1858) < 1e-8) throw new IllegalArgumentException("invalid white chromaticity");
                double n = (x - .332) / (y - .1858);
                double cct = clamp(-449 * n * n * n + 3525 * n * n - 6823.3 * n + 5520.33, 2000, 12000);
                double target = clamp((1 / cct - 1 / first) / (1 / second - 1 / first), 0, 1);
                if (Math.abs(target - fraction) < 1e-5) { fraction = target; break; }
                fraction = (fraction + target) / 2;
            }
        }
        double[] calibration = interpolate(profile.calibration1, profile.calibration2, fraction);
        double[] individualToReference = inverse(multiply(diagonal(profile.analog), calibration));
        double[] cameraToXyz;
        double[] sourceWhite;
        if (profile.forward1 != null) {
            double[] forward = interpolate(profile.forward1, profile.forward2, fraction);
            for (int y = 0; y < 3; y++) {
                double sum = forward[y * 3] + forward[y * 3 + 1] + forward[y * 3 + 2];
                if (Math.abs(sum) < 1e-8) throw new IllegalArgumentException("invalid ForwardMatrix");
                for (int x = 0; x < 3; x++) forward[y * 3 + x] *= D50[y] / sum;
            }
            double[] referenceNeutral = vector(individualToReference, neutral);
            for (int c = 0; c < 3; c++) {
                if (referenceNeutral[c] <= 0) throw new IllegalArgumentException("invalid reference neutral");
                referenceNeutral[c] = 1 / referenceNeutral[c];
            }
            cameraToXyz = multiply(multiply(forward, diagonal(referenceNeutral)), individualToReference);
            sourceWhite = D50;
        } else {
            cameraToXyz = multiply(inverse(interpolate(profile.color1, profile.color2, fraction)), individualToReference);
            sourceWhite = vector(cameraToXyz, neutral);
            if (sourceWhite[1] <= 0) throw new IllegalArgumentException("invalid source white");
            double y = sourceWhite[1];
            for (int c = 0; c < 3; c++) sourceWhite[c] /= y;
        }
        double[] sourceCone = vector(BRADFORD, sourceWhite), targetCone = vector(BRADFORD, D65);
        for (int c = 0; c < 3; c++) {
            if (sourceCone[c] <= 0) throw new IllegalArgumentException("invalid adapted white");
            targetCone[c] /= sourceCone[c];
        }
        double[] adaptation = multiply(multiply(inverse(BRADFORD), diagonal(targetCone)), BRADFORD);
        double[] result = multiply(multiply(XYZ_TO_RGB, adaptation), cameraToXyz);
        double green = vector(result, neutral)[1];
        if (!(green > 0)) throw new IllegalArgumentException("invalid colour normalization");
        float[] output = new float[9];
        for (int i = 0; i < 9; i++) {
            double value = result[i] / green;
            if (!Double.isFinite(value) || Math.abs(value) > 32) throw new IllegalArgumentException("unstable colour transform");
            output[i] = (float) value;
        }
        return output;
    }

    private static double temperature(int illuminant) {
        switch (illuminant) {
            case 1: case 4: case 9: return 5500;
            case 2: return 4150;
            case 3: case 17: return 2856;
            case 10: case 21: return 6504;
            case 11: case 22: return 7504;
            case 18: return 4874;
            case 19: return 6774;
            case 20: return 5503;
            case 23: return 5003;
            case 24: return 3200;
            default: throw new IllegalArgumentException("unsupported calibration illuminant");
        }
    }

    private static double clamp(double value, double lo, double hi) { return Math.max(lo, Math.min(hi, value)); }
    private static double[] checked(double[] values, int count) {
        if (values == null || values.length != count) throw new IllegalArgumentException("invalid calibration dimensions");
        double[] result = values.clone();
        for (double value : result) if (!Double.isFinite(value) || Math.abs(value) > 32) throw new IllegalArgumentException("invalid calibration value");
        return result;
    }
    private static double[] interpolate(double[] a, double[] b, double fraction) {
        if (b == null) return a.clone();
        double[] result = new double[9];
        for (int i = 0; i < 9; i++) result[i] = a[i] * (1 - fraction) + b[i] * fraction;
        return result;
    }
    private static double[] diagonal(double[] values) { return new double[] {values[0], 0, 0, 0, values[1], 0, 0, 0, values[2]}; }
    private static double[] vector(double[] matrix, double[] value) {
        double[] result = new double[3];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) result[y] += matrix[y * 3 + x] * value[x];
        return result;
    }
    private static double[] multiply(double[] a, double[] b) {
        double[] result = new double[9];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int k = 0; k < 3; k++) result[y * 3 + x] += a[y * 3 + k] * b[k * 3 + x];
        return result;
    }
    private static double[] inverse(double[] a) {
        double[] result = {a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
                           a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
                           a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3]};
        double determinant = a[0] * result[0] + a[1] * result[3] + a[2] * result[6];
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-10) throw new IllegalArgumentException("singular calibration matrix");
        for (int i = 0; i < 9; i++) result[i] /= determinant;
        return result;
    }
}
