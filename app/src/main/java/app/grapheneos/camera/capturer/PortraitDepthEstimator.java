package app.grapheneos.camera.capturer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Pinned offline Portrait depth. Relative inverse depth is not metric distance. */
public final class PortraitDepthEstimator implements AutoCloseable {
    private static final int SIDE = 518;
    private final ByteBuffer model;
    private final Interpreter interpreter;
    private boolean closed;

    public static final class Result {
        public final int width, height;
        public final float[] values;
        public final long inferenceMs;
        Result(int width, int height, float[] values, long inferenceMs) {
            this.width = width; this.height = height; this.values = values; this.inferenceMs = inferenceMs;
        }
    }

    public PortraitDepthEstimator(ByteBuffer model) {
        if (!model.isDirect() || model.remaining() != 98_928_904) throw new IllegalArgumentException("invalid depth model size");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(model.duplicate());
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!hash.toString().equals("0bb8192ef8affcb956f6b1577b3ba3207238357f1fc2516211e0e2b8ff86381c")) throw new IllegalArgumentException("depth model hash mismatch");
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        this.model = model;
        interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true).setCancellable(true));
        if (interpreter.getInputTensorCount() != 1 || interpreter.getOutputTensorCount() != 1 ||
            !Arrays.equals(interpreter.getInputTensor(0).shape(), new int[]{1, SIDE, SIDE, 3}) ||
            !Arrays.equals(interpreter.getOutputTensor(0).shape(), new int[]{1, SIDE, SIDE, 1}) ||
            interpreter.getInputTensor(0).dataType() != DataType.FLOAT32 || interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
            interpreter.close(); throw new IllegalArgumentException("unsupported depth model tensors");
        }
    }

    public Result estimate(Bitmap source) {
        if (source.getWidth() < 2 || source.getHeight() < 2 || (long) source.getWidth() * source.getHeight() > 16_000_000) throw new IllegalArgumentException("invalid depth image");
        float scale = (float) SIDE / Math.max(source.getWidth(), source.getHeight());
        int width = Math.max(1, Math.round(source.getWidth() * scale)), height = Math.max(1, Math.round(source.getHeight() * scale));
        int left = (SIDE - width) / 2, top = (SIDE - height) / 2;
        Bitmap resized = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[SIDE * SIDE];
        try {
            Canvas canvas = new Canvas(resized); canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(source, null, new RectF(left, top, left + width, top + height), new Paint(Paint.FILTER_BITMAP_FLAG));
            resized.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
        } finally { resized.recycle(); }
        ByteBuffer input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder());
        float[] rgb = new float[SIDE * SIDE * 3];
        for (int i = 0; i < pixels.length; ++i) {
            rgb[i * 3] = Color.red(pixels[i]) / 255f;
            rgb[i * 3 + 1] = Color.green(pixels[i]) / 255f;
            rgb[i * 3 + 2] = Color.blue(pixels[i]) / 255f;
        }
        input.asFloatBuffer().put(rgb);
        input.rewind(); long started = SystemClock.elapsedRealtime(); interpreter.run(input, output);
        long elapsed = SystemClock.elapsedRealtime() - started; output.rewind();
        float[] fullDepth = new float[SIDE * SIDE];
        output.asFloatBuffer().get(fullDepth);
        float[] values = new float[width * height];
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
            float value = fullDepth[y * SIDE + x];
            if (!Float.isFinite(value) || value < 0 || value > 10_000) throw new IllegalStateException("invalid inverse depth");
            if (x >= left && x < left + width && y >= top && y < top + height) values[(y - top) * width + x - left] = value;
        }
        return new Result(width, height, values, elapsed);
    }

    public synchronized void cancel() { if (!closed) interpreter.setCancelled(true); }
    @Override public synchronized void close() { if (!closed) { closed = true; interpreter.close(); } }
}
