package app.grapheneos.camera.capturer;

import java.nio.ByteBuffer;

/** Bounded native RAW processing task with cancellation and deferred handle destruction. */
public final class RawNativeCore {
    static { System.loadLibrary("camera_processing"); }
    private static native long create();
    private static native void cancel(long handle);
    private static native void destroy(long handle);
    private static native int[] process(long handle, ByteBuffer[] frames, int[] strides, float[] calibration,
                                        int width, int height, int cfa, int reference, float exposure,
                                        float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation, int[] periodicGrid, boolean edgeAwareDemosaic, boolean frequencyDenoise, boolean steadyPhotoFinish, ByteBuffer rawShading);

    private static native int[] portrait(long handle, int[] source, int width, int height,
                                          float[] mask, int maskWidth, int maskHeight, int radius,
                                          float[] depth, int depthWidth, int depthHeight, float subjectX, float subjectY);

    private static native float[] refineMask(long handle, int[] source, int width, int height, float[] mask, int maskWidth, int maskHeight);

    private static native int[] denoise(long handle,int[] source,int width,int height);
    private static native long createJpegMerge(long task, int width, int height);
    private static native void addJpegMerge(long task, long merger, int[] source);
    private static native int[] finishJpegMerge(long task, long merger, double[] stats, float gain);
    private static native void destroyJpegMerge(long merger);

    public static final class JpegMerger implements AutoCloseable {
        private final Task owner;
        private final long task;
        private long merger;
        private JpegMerger(Task owner, long task, long merger) {
            this.owner = owner; this.task = task; this.merger = merger;
        }
        public synchronized void add(int[] pixels) {
            if (merger == 0 || owner.isCancelled()) throw new IllegalStateException("JPEG merger unavailable");
            addJpegMerge(task, merger, pixels);
        }
        /** gain: sensor gain the frames held back, restored in linear light (1 to 4). */
        public synchronized int[] finish(double[] stats, float gain) {
            if (merger == 0 || owner.isCancelled()) throw new IllegalStateException("JPEG merger unavailable");
            try { return finishJpegMerge(task, merger, stats, gain); }
            finally { close(); }
        }
        @Override public synchronized void close() {
            if (merger == 0) return;
            destroyJpegMerge(merger); merger = 0;
            owner.endRunning();
        }
    }

    public static final class Task implements AutoCloseable {
        private long handle = create();
        private boolean running;
        private boolean closed;
        private boolean cancelled;

        public JpegMerger beginJpegMerge(int width, int height) {
            final long nativeHandle;
            synchronized (this) {
                if (closed || running || cancelled) throw new IllegalStateException("JPEG task unavailable");
                running = true; nativeHandle = handle;
            }
            long merger = 0;
            try {
                merger = createJpegMerge(nativeHandle, width, height);
                if (merger == 0) throw new IllegalStateException("JPEG merger unavailable");
                return new JpegMerger(this, nativeHandle, merger);
            } catch (RuntimeException | Error error) {
                if (merger != 0) destroyJpegMerge(merger);
                endRunning(); throw error;
            }
        }
        private synchronized void endRunning() {
            running = false;
            if (closed) release();
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats) {
            return run(frames, strides, calibration, width, height, cfa, reference, exposure, color, stats, 0f);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance) {
            return run(frames, strides, calibration, width, height, cfa, reference, exposure, color, stats, targetLuminance, 1f);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom) {
            return run(frames, strides, calibration, width, height, cfa, reference, exposure, color, stats, targetLuminance, zoom, 4f / 3);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect) {
            return run(frames, strides, calibration, width, height, cfa, reference, exposure, color, stats, targetLuminance, zoom, aspect, null);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets) {
            return run(frames, strides, calibration, width, height, cfa, reference, exposure, color, stats, targetLuminance, zoom, aspect, toneTargets, null, 0);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation) {
            return run(frames,strides,calibration,width,height,cfa,reference,exposure,color,stats,targetLuminance,zoom,aspect,toneTargets,colorReference,referenceRotation,null);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation, int[] periodicGrid) {
            return run(frames,strides,calibration,width,height,cfa,reference,exposure,color,stats,targetLuminance,zoom,aspect,toneTargets,colorReference,referenceRotation,periodicGrid,false);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation, int[] periodicGrid, boolean edgeAwareDemosaic) {
            return run(frames,strides,calibration,width,height,cfa,reference,exposure,color,stats,targetLuminance,zoom,aspect,toneTargets,colorReference,referenceRotation,periodicGrid,edgeAwareDemosaic,false);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation, int[] periodicGrid, boolean edgeAwareDemosaic, boolean frequencyDenoise) {
            return run(frames,strides,calibration,width,height,cfa,reference,exposure,color,stats,targetLuminance,zoom,aspect,toneTargets,colorReference,referenceRotation,periodicGrid,edgeAwareDemosaic,frequencyDenoise,false);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation, int[] periodicGrid, boolean edgeAwareDemosaic, boolean frequencyDenoise, boolean steadyPhotoFinish) {
            return run(frames, strides, calibration, width, height, cfa, reference, exposure, color, stats,
                targetLuminance, zoom, aspect, toneTargets, colorReference, referenceRotation, periodicGrid,
                edgeAwareDemosaic, frequencyDenoise, steadyPhotoFinish, null);
        }

        public int[] run(ByteBuffer[] frames, int[] strides, float[] calibration, int width, int height,
                         int cfa, int reference, float exposure, float[] color, double[] stats, float targetLuminance, float zoom, float aspect, float[] toneTargets, int[] colorReference, int referenceRotation, int[] periodicGrid, boolean edgeAwareDemosaic, boolean frequencyDenoise, boolean steadyPhotoFinish, ByteBuffer rawShading) {
            final long nativeHandle;
            synchronized (this) {
                if (closed || running) throw new IllegalStateException("RAW task unavailable");
                running = true;
                nativeHandle = handle;
            }
            try {
                return process(nativeHandle, frames, strides, calibration, width, height, cfa, reference, exposure, color, stats, targetLuminance, zoom, aspect, toneTargets, colorReference, referenceRotation, periodicGrid, edgeAwareDemosaic, frequencyDenoise, steadyPhotoFinish, rawShading);
            } finally {
                synchronized (this) {
                    running = false;
                    if (closed) release();
                }
            }
        }

        public int[] renderPortrait(int[] source, int width, int height, float[] mask, int maskWidth, int maskHeight, int radius) {
            return renderPortrait(source, width, height, mask, maskWidth, maskHeight, radius, null, 0, 0, .5f, .5f);
        }

        public int[] renderPortrait(int[] source, int width, int height, float[] mask, int maskWidth, int maskHeight, int radius,
                                    float[] depth, int depthWidth, int depthHeight, float subjectX, float subjectY) {
            final long nativeHandle;
            synchronized (this) {
                if (closed || running) throw new IllegalStateException("Portrait task unavailable");
                running = true;
                nativeHandle = handle;
            }
            try { return portrait(nativeHandle, source, width, height, mask, maskWidth, maskHeight, radius, depth, depthWidth, depthHeight, subjectX, subjectY); }
            finally {
                synchronized (this) { running = false; if (closed) release(); }
            }
        }

        public float[] refinePortraitMask(int[] source, int width, int height, float[] mask, int maskWidth, int maskHeight) {
            final long nativeHandle;
            synchronized (this) {
                if (closed || running) throw new IllegalStateException("Portrait task unavailable");
                running = true; nativeHandle = handle;
            }
            try { return refineMask(nativeHandle, source, width, height, mask, maskWidth, maskHeight); }
            finally { synchronized (this) { running = false; if (closed) release(); } }
        }

        public int[] denoiseJpeg(int[] source,int width,int height) {
            final long nativeHandle;
            synchronized(this) {if(closed || running) throw new IllegalStateException("JPEG task unavailable");running=true;nativeHandle=handle;}
            try {return denoise(nativeHandle,source,width,height);}
            finally {synchronized(this) {running=false;if(closed) release();}}
        }

        public synchronized boolean isCancelled() { return closed || cancelled; }
        public synchronized void cancel() { cancelled = true; if (handle != 0) RawNativeCore.cancel(handle); }
        private void release() { if (handle != 0) { destroy(handle); handle = 0; } }
        @Override public synchronized void close() {
            closed = true;
            cancel();
            if (!running) release();
        }
    }
}
