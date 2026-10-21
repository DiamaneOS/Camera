package app.grapheneos.camera;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** DUMP-only four-JPEG research capture. Fixed private files; never publishes media. */
public final class JpegBurstComparisonTestActivity extends Activity {
    private static final int COUNT = 4;
    private static final int MAX_JPEG_BYTES = 24 * 1024 * 1024;
    private volatile boolean stopped;
    private final AtomicReference<CameraDevice> active = new AtomicReference<>();

    private static final class Frame {
        final long timestamp;
        final byte[] bytes;
        Frame(long timestamp, byte[] bytes) { this.timestamp = timestamp; this.bytes = bytes; }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView label = new TextView(this);
        label.setText("Local JPEG burst comparison");
        setContentView(label);
        String camera = getIntent().getStringExtra("camera");
        int ceilingMs = getIntent().getIntExtra("ceilingMs", 67);
        int focusMode = getIntent().getIntExtra("focusMode", CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        boolean controlled = getIntent().getBooleanExtra("controlled", false);
        int noiseMode = getIntent().getIntExtra("noiseMode", CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        int width = getIntent().getIntExtra("width", 0), height = getIntent().getIntExtra("height", 0);
        int budgetPercent = getIntent().getIntExtra("budgetPercent", 100);
        new Thread(() -> capture(camera, ceilingMs, focusMode, controlled, noiseMode, width, height, budgetPercent), "jpeg-burst-comparison").start();
    }

    private void checkActive() { if (stopped) throw new IllegalStateException("Stopped"); }

    private static boolean exposureMatches(TotalCaptureResult result, long exposure, int iso, int boost) {
        if (result == null) return false;
        Long time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Integer digital = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        return result.get(CaptureResult.CONTROL_AE_MODE) != null &&
            result.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF &&
            time != null && Math.abs(time - exposure) <= exposure / 100 &&
            sensitivity != null && Math.abs(sensitivity - iso) <= Math.max(1, iso / 50) &&
            digital != null && Math.abs(digital - boost) <= 2;
    }

    private void capture(String camera, int ceilingMs, int focusMode, boolean controlled, int noiseMode, int requestedWidth, int requestedHeight, int budgetPercent) {
        File root = new File(getNoBackupFilesDir(), "jpeg-burst-comparison");
        root.mkdirs();
        JSONObject report = new JSONObject();
        HandlerThread callbacks = new HandlerThread("jpeg-burst-callbacks");
        callbacks.start();
        Handler handler = new Handler(callbacks.getLooper());
        CameraDevice device = null;
        CameraCaptureSession session = null;
        ImageReader preview = null, jpeg = null;
        long started = SystemClock.elapsedRealtime();
        try {
            if (ceilingMs != 67 && ceilingMs != 100 && ceilingMs != 125 && ceilingMs != 500) throw new IllegalArgumentException("Exposure ceiling");
            if (focusMode != CaptureRequest.CONTROL_AF_MODE_AUTO && focusMode != CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                throw new IllegalArgumentException("Focus mode");
            CameraManager manager = getSystemService(CameraManager.class);
            if (camera == null || !Arrays.asList(manager.getCameraIdList()).contains(camera))
                throw new IllegalArgumentException("Public camera required");
            CameraCharacteristics chars = manager.getCameraCharacteristics(camera);
            int[] shadingModes=chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES);
            report.put("availableShadingModes", shadingModes==null?JSONObject.NULL:new JSONArray(shadingModes));
            if(Build.VERSION.SDK_INT>=33) {
                long[] useCases=chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES);
                report.put("availableStreamUseCases",useCases==null?JSONObject.NULL:new JSONArray(useCases));
            }
            if(!((requestedWidth==0 && requestedHeight==0) || (requestedWidth>=8 && requestedWidth<=8192 && requestedHeight>=8 && requestedHeight<=8192 &&
                (long)requestedWidth*requestedHeight<=16_000_000)))throw new IllegalArgumentException("Requested dimensions");
            int[] noiseModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            if ((noiseMode != CaptureRequest.NOISE_REDUCTION_MODE_FAST && noiseMode != CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) ||
                noiseModes == null || Arrays.stream(noiseModes).noneMatch(mode -> mode == noiseMode))
                throw new IllegalArgumentException("Noise mode unavailable");
            int[] focusModes = Objects.requireNonNull(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
            if (Arrays.stream(focusModes).noneMatch(mode -> mode == focusMode)) throw new IllegalArgumentException("Focus unavailable");
            StreamConfigurationMap map = Objects.requireNonNull(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            Size size = Arrays.stream(Objects.requireNonNull(map.getOutputSizes(ImageFormat.JPEG)))
                .filter(s -> s.getWidth() >= 8 && s.getHeight() >= 8 && s.getWidth() <= 8192 && s.getHeight() <= 8192 &&
                    (long)s.getWidth() * s.getHeight() <= 16_000_000 && s.getWidth() % 2 == 0 && s.getHeight() % 2 == 0 &&
                    (requestedWidth==0 || (s.getWidth()==requestedWidth && s.getHeight()==requestedHeight)))
                .max(Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight())).orElseThrow();
            Size ps = Arrays.stream(Objects.requireNonNull(map.getOutputSizes(ImageFormat.PRIVATE)))
                .filter(s -> s.getWidth() <= 1280 && s.getHeight() <= 1280)
                .min(Comparator.comparingDouble(s -> Math.abs((double)s.getWidth() / s.getHeight() -
                    (double)size.getWidth() / size.getHeight()) * 1000 + Math.abs(s.getWidth() - 1280))).orElseThrow();
            preview = ImageReader.newInstance(ps.getWidth(), ps.getHeight(), ImageFormat.PRIVATE, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            preview.setOnImageAvailableListener(r -> { try (Image image = r.acquireLatestImage()) { }
                catch (IllegalStateException ignored) { } }, handler);
            jpeg = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 3);
            ArrayBlockingQueue<Object> frames = new ArrayBlockingQueue<>(COUNT);
            jpeg.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireNextImage()) {
                    if (image == null || stopped) return;
                    ByteBuffer encoded = image.getPlanes()[0].getBuffer();
                    if (encoded.remaining() < 4 || encoded.remaining() > MAX_JPEG_BYTES)
                        throw new IllegalStateException("JPEG bound");
                    byte[] bytes = new byte[encoded.remaining()];
                    encoded.get(bytes);
                    if ((bytes[0] & 255) != 255 || (bytes[1] & 255) != 216 ||
                        !frames.offer(new Frame(image.getTimestamp(), bytes)))
                        throw new IllegalStateException("JPEG queue");
                } catch (Exception error) { frames.offer(error); }
            }, handler);
            ArrayBlockingQueue<Object> opened = new ArrayBlockingQueue<>(1);
            AtomicBoolean opening = new AtomicBoolean(true);
            manager.openCamera(camera, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (stopped || !opening.get() || !opened.offer(d)) d.close();
                    else if (!opening.get() && opened.remove(d)) d.close();
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); opened.offer("disconnected"); }
                @Override public void onError(CameraDevice d, int error) { d.close(); opened.offer("camera error"); }
            }, handler);
            Object op = opened.poll(8, TimeUnit.SECONDS);
            opening.set(false);
            Object late = opened.poll();
            if (late instanceof CameraDevice) ((CameraDevice)late).close();
            if (!(op instanceof CameraDevice)) throw new IllegalStateException("Camera open");
            device = (CameraDevice)op;
            active.set(device);
            checkActive();
            ArrayBlockingQueue<Object> configured = new ArrayBlockingQueue<>(1);
            AtomicBoolean configuring = new AtomicBoolean(true);
            device.createCaptureSession(Arrays.asList(preview.getSurface(), jpeg.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (stopped || !configuring.get() || !configured.offer(s)) s.close();
                    else if (!configuring.get() && configured.remove(s)) s.close();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { s.close(); configured.offer("unsupported"); }
            }, handler);
            Object cs = configured.poll(8, TimeUnit.SECONDS);
            configuring.set(false);
            Object lateSession = configured.poll();
            if (lateSession instanceof CameraCaptureSession) ((CameraCaptureSession)lateSession).close();
            if (!(cs instanceof CameraCaptureSession)) throw new IllegalStateException("Session configuration");
            session = (CameraCaptureSession)cs;
            AtomicReference<TotalCaptureResult> latest = new AtomicReference<>();
            CameraCaptureSession.CaptureCallback meteringCallback = new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) { latest.set(result); }
            };
            CaptureRequest.Builder repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeating.addTarget(preview.getSurface());
            repeating.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
            session.setRepeatingRequest(repeating.build(), meteringCallback, handler);
            long warmEnd = SystemClock.elapsedRealtime() + 3500;
            while (SystemClock.elapsedRealtime() < warmEnd) {
                checkActive(); SystemClock.sleep(30);
                TotalCaptureResult value = latest.get();
                if (value == null || SystemClock.elapsedRealtime() - started < 1500) continue;
                Integer ae = value.get(CaptureResult.CONTROL_AE_STATE), af = value.get(CaptureResult.CONTROL_AF_STATE);
                if (ae != null && (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) &&
                    af != null && af != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && af != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN) break;
            }
            TotalCaptureResult metering = Objects.requireNonNull(latest.get());
            Range<Integer> isoRange = Objects.requireNonNull(chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE));
            Range<Long> exposureRange = Objects.requireNonNull(chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE));
            Range<Integer> boostRange = Objects.requireNonNull(chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE));
            long oldTime = Objects.requireNonNull(metering.get(CaptureResult.SENSOR_EXPOSURE_TIME));
            int oldIso = Objects.requireNonNull(metering.get(CaptureResult.SENSOR_SENSITIVITY));
            int oldBoost = Objects.requireNonNull(metering.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST));
            if (oldTime <= 0 || oldIso <= 0 || oldBoost <= 0) throw new IllegalStateException("Metering values");
            if (budgetPercent < 10 || budgetPercent > 400) throw new IllegalArgumentException("Budget percent");
            double budget = oldTime * (Math.min(oldIso, isoRange.getUpper()) * (oldBoost / 100.0));
            long ceiling = ceilingMs == 67 ? 66_666_667L : ceilingMs * 1_000_000L;
            long exposure = exposureRange.clamp(Math.min(ceiling, (long)(budget / isoRange.getLower())));
            int iso = isoRange.clamp((int)Math.round(budget / exposure));
            int boost = boostRange.clamp((int)Math.round(100 * budget / (exposure * (double)iso)));
            // Less gain at the same exposure time: analog first with keepBoost, else digital first.
            boolean keepBoost = getIntent().getBooleanExtra("keepBoost", false);
            double reduced = budget * budgetPercent / 100.0;
            if (keepBoost) iso = isoRange.clamp((int)Math.round(100 * reduced / (exposure * (double)boost)));
            else iso = isoRange.clamp((int)Math.round(reduced / exposure));
            boost = boostRange.clamp((int)Math.round(100 * reduced / (exposure * (double)iso)));
            if (controlled) {
                exposure = exposureRange.clamp(ceiling);
                iso = isoRange.getUpper();
                boost = boostRange.clamp(200);
            }
            report.put("requestedExposureNs", exposure).put("requestedIso", iso).put("requestedBoost", boost).put("requestedNoiseMode", noiseMode).put("budgetPercent", budgetPercent);
            // Complete focus admission at the automatic preview cadence, before
            // independently checking the longer manual frame period.
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(repeating.build(), meteringCallback, handler);
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(repeating.build(), meteringCallback, handler);
            long focusEnd = SystemClock.elapsedRealtime()+3000, lastFocusFrame=-1;
            int focusAdmitted=0;
            boolean fixedFocus = Objects.requireNonNull(chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)) == 0f;
            while (SystemClock.elapsedRealtime()<focusEnd) {
                checkActive(); SystemClock.sleep(20);
                TotalCaptureResult value=latest.get();
                if(value==null || value.getFrameNumber()==lastFocusFrame)continue;
                lastFocusFrame=value.getFrameNumber();
                Integer af=value.get(CaptureResult.CONTROL_AF_STATE);
                focusAdmitted=fixedFocus || (af!=null && af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)?focusAdmitted+1:0;
                if(focusAdmitted>=2)break;
            }
            if(focusAdmitted<2)throw new IllegalStateException("Automatic focus not admitted");
            repeating.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            repeating.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            repeating.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exposure, 33_333_333L));
            repeating.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            repeating.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost);
            repeating.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            repeating.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(repeating.build(), meteringCallback, handler);
            long settleStarted = SystemClock.elapsedRealtime(), settleEnd = settleStarted + 3000;
            int admitted = 0; long lastFrame = -1;
            while (SystemClock.elapsedRealtime() < settleEnd) {
                checkActive(); SystemClock.sleep(20);
                TotalCaptureResult value = latest.get();
                if (value == null || value.getFrameNumber() == lastFrame) continue;
                lastFrame = value.getFrameNumber();
                Integer af = value.get(CaptureResult.CONTROL_AF_STATE);
                boolean focusReady = fixedFocus || (af != null && af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
                report.put("lastExposureNs", value.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    .put("lastIso", value.get(CaptureResult.SENSOR_SENSITIVITY))
                    .put("lastBoost", value.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
                    .put("lastAf", af).put("lastAe", value.get(CaptureResult.CONTROL_AE_MODE));
                admitted = exposureMatches(value, exposure, iso, boost) && focusReady ? admitted + 1 : 0;
                if (admitted >= 2) break;
            }
            if (admitted < 2) throw new IllegalStateException("Manual focus/exposure not admitted");
            report.put("settleMs", SystemClock.elapsedRealtime() - settleStarted)
                .put("focusMode", focusMode).put("controlledExposure", controlled)
                .put("requestedExposureNs", exposure).put("requestedIso", iso).put("requestedBoost", boost)
                .put("width", size.getWidth()).put("height", size.getHeight()).put("frames", COUNT)
                .put("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION))
                .put("meteredExposureNs", oldTime).put("meteredIso", oldIso).put("meteredBoost", oldBoost)
                .put("jpegStallNs", map.getOutputStallDuration(ImageFormat.JPEG, size));
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(jpeg.getSurface());
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            request.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exposure, 33_333_333L));
            request.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            request.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost);
            request.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            request.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
            request.set(CaptureRequest.CONTROL_ENABLE_ZSL, false);
            request.set(CaptureRequest.JPEG_QUALITY, (byte)95);
            request.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseMode);
            session.stopRepeating();
            ConcurrentHashMap<Long, TotalCaptureResult> results = new ConcurrentHashMap<>();
            long burstStarted = SystemClock.elapsedRealtime();
            session.captureBurst(Collections.nCopies(COUNT, request.build()), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult value) {
                    Long timestamp = value.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp != null && results.size() < COUNT) results.put(timestamp, value);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) { frames.offer("Capture failed"); }
            }, handler);
            JSONArray measurements = new JSONArray();
            long firstSensorTimestamp = 0;
            long deadline = burstStarted + 15_000;
            for (int index = 0; index < COUNT; index++) {
                Object next = null;
                while (next == null && SystemClock.elapsedRealtime() < deadline) { checkActive(); next = frames.poll(100, TimeUnit.MILLISECONDS); }
                if (!(next instanceof Frame)) throw new IllegalStateException("Burst frame unavailable");
                Frame frame = (Frame)next;
                TotalCaptureResult result = null;
                while (result == null && SystemClock.elapsedRealtime() < deadline) {
                    checkActive(); result = results.remove(frame.timestamp); if (result == null) SystemClock.sleep(5);
                }
                if (!exposureMatches(result, exposure, iso, boost)) throw new IllegalStateException("Burst exposure changed");
                Integer actualNoiseMode=result.get(CaptureResult.NOISE_REDUCTION_MODE);
                if(actualNoiseMode==null || actualNoiseMode!=noiseMode)throw new IllegalStateException("Noise mode not honored");
                if(index==0)firstSensorTimestamp=frame.timestamp;
                Files.write(new File(root, String.format(Locale.ROOT, "frame-%02d.jpg", index)).toPath(), frame.bytes);
                measurements.put(new JSONObject().put("exposureNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    .put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY)).put("boost", result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
                    .put("noiseReductionMode", result.get(CaptureResult.NOISE_REDUCTION_MODE)).put("focus", result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                    .put("shadingMode", result.get(CaptureResult.SHADING_MODE))
                    .put("sensorFrameDurationNs", result.get(CaptureResult.SENSOR_FRAME_DURATION))
                    .put("sensorElapsedNs", frame.timestamp-firstSensorTimestamp)
                    .put("bytes", frame.bytes.length));
            }
            report.put("burstMs", SystemClock.elapsedRealtime() - burstStarted).put("matchedSensorTimestamps", true)
                .put("measurements", measurements).put("status", "ok");
        } catch (Throwable error) {
            try { report.put("status", "error").put("errorType", error.getClass().getSimpleName())
                .put("errorMessage", error.getMessage()); } catch (Exception ignored) { }
        } finally {
            stopped = true;
            if (session != null) session.close();
            if (device != null) device.close();
            active.set(null);
            if (preview != null) preview.close();
            if (jpeg != null) jpeg.close();
            callbacks.quitSafely();
            try {
                callbacks.join(1000);
                report.put("elapsedMs", SystemClock.elapsedRealtime() - started);
                Files.write(new File(root, "result.json").toPath(), report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }

    @Override protected void onStop() {
        stopped = true;
        CameraDevice device = active.get();
        if (device != null) device.close();
        super.onStop();
    }
}
