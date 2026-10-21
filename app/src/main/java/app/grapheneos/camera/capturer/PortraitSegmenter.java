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
import java.util.Arrays;

/** CPU subject selection. Model and runtime are pinned separately, with their licence notices. */
public final class PortraitSegmenter implements AutoCloseable {
    private static final int SIDE = 512;
    private final ByteBuffer model;
    private final Interpreter interpreter;
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIDE * SIDE * 4 * 4).order(ByteOrder.nativeOrder());
    private final ByteBuffer output = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder());
    private final float[] inputValues = new float[SIDE * SIDE * 4];

    public static final class Result {
        public final int width;
        public final int height;
        public final float[] confidence;
        public final long inferenceMs;

        Result(int width, int height, float[] confidence, long inferenceMs) {
            this.width = width;
            this.height = height;
            this.confidence = confidence;
            this.inferenceMs = inferenceMs;
        }
    }

    public PortraitSegmenter(ByteBuffer model) {
        this.model = model;
        Interpreter.Options options = new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true).setCancellable(true);
        interpreter = new Interpreter(model, options);
        if (interpreter.getInputTensorCount() != 1 || interpreter.getOutputTensorCount() != 1 ||
            !Arrays.equals(interpreter.getInputTensor(0).shape(), new int[] {1, SIDE, SIDE, 4}) ||
            !Arrays.equals(interpreter.getOutputTensor(0).shape(), new int[] {1, SIDE, SIDE, 1}) ||
            interpreter.getInputTensor(0).dataType() != DataType.FLOAT32 ||
            interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32) {
            interpreter.close();
            throw new IllegalArgumentException("unsupported Portrait model tensors");
        }
    }

    public synchronized Result segment(Bitmap source, float subjectX, float subjectY) {
        if (!Float.isFinite(subjectX) || !Float.isFinite(subjectY) || subjectX < 0 || subjectX > 1 ||
            subjectY < 0 || subjectY > 1 || source.getWidth() <= 0 || source.getHeight() <= 0 ||
            (long) source.getWidth() * source.getHeight() > 32_000_000) {
            throw new IllegalArgumentException("invalid Portrait image or subject");
        }
        // The pinned v1 four-channel MediaPipe graph uses STRETCH, not FIT.
        // Keep the mask square and map it back to source geometry in the renderer.
        int width = SIDE, height = SIDE, left = 0, top = 0;
        Bitmap resized = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[SIDE * SIDE];
        try {
            Canvas canvas = new Canvas(resized);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(source, null, new RectF(left, top, left + width, top + height),
                              new Paint(Paint.FILTER_BITMAP_FLAG));
            resized.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
        } finally {
            resized.recycle();
        }
        int seedX = Math.min(left + width - 1, left + (int) (subjectX * width));
        int seedY = Math.min(top + height - 1, top + (int) (subjectY * height));
        input.clear();
        output.clear();
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                int pixel = pixels[y * SIDE + x];
                int offset = (y * SIDE + x) * 4;
                inputValues[offset] = Color.red(pixel) / 255f;
                inputValues[offset + 1] = Color.green(pixel) / 255f;
                inputValues[offset + 2] = Color.blue(pixel) / 255f;
                inputValues[offset + 3] = x == seedX && y == seedY ? 1f : 0f;
            }
        }
        input.asFloatBuffer().put(inputValues);
        input.rewind();
        long start = SystemClock.elapsedRealtimeNanos();
        interpreter.run(input, output);
        long ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000;
        output.rewind();
        float[] confidence = new float[width * height];
        output.asFloatBuffer().get(confidence);
        for (float foreground : confidence) {
            if (!Float.isFinite(foreground) || foreground < 0 || foreground > 1) {
                throw new IllegalStateException("invalid Portrait confidence");
            }
        }
        // The pinned v1 ends with LOGISTIC; these are already probabilities.
        return new Result(width, height, confidence, ms);
    }

    public void cancel() { interpreter.setCancelled(true); }
    @Override public synchronized void close() { interpreter.close(); }
}
