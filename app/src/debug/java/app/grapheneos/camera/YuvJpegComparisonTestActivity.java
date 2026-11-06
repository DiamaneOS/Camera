package app.grapheneos.camera;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** DUMP-only same-request YUV/JPEG test. Fixed private outputs, no production entry point. */
public final class YuvJpegComparisonTestActivity extends Activity {
    private volatile boolean stopped;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label=new TextView(this);label.setText("Local YUV/JPEG comparison");setContentView(label);
        String camera=getIntent().getStringExtra("camera");
        boolean compensate=getIntent().getBooleanExtra("compensate",false);
        new Thread(() -> {
            File root=new File(getFilesDir(),"yuv-jpeg-comparison");root.mkdirs();JSONObject report=new JSONObject();
            HandlerThread callbacks=new HandlerThread("yuv-comparison-callbacks");callbacks.start();Handler handler=new Handler(callbacks.getLooper());
            CameraDevice device=null;CameraCaptureSession session=null;
            ImageReader preview=null,yuv=null,jpeg=null;Image yi=null,ji=null;
            long start=SystemClock.elapsedRealtime();
            try {
                if(camera==null)throw new IllegalArgumentException("Camera required");
                CameraManager manager=getSystemService(CameraManager.class);CameraCharacteristics chars=manager.getCameraCharacteristics(camera);
                StreamConfigurationMap map=Objects.requireNonNull(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
                List<Size> js=Arrays.asList(Objects.requireNonNull(map.getOutputSizes(ImageFormat.JPEG)));
                Size size=Arrays.stream(Objects.requireNonNull(map.getOutputSizes(ImageFormat.YUV_420_888)))
                    .filter(s -> s.getWidth()>=8 && s.getHeight()>=8 && (long)s.getWidth()*s.getHeight()<=16_000_000 && s.getWidth()%2==0 && s.getHeight()%2==0 && js.contains(s))
                    .max(Comparator.comparingLong(s -> (long)s.getWidth()*s.getHeight())).orElseThrow();
                Size ps=Arrays.stream(Objects.requireNonNull(map.getOutputSizes(ImageFormat.PRIVATE)))
                    .filter(s -> s.getWidth()<=1920 && s.getHeight()<=1920)
                    .min(Comparator.comparingDouble(s -> Math.abs((double)s.getWidth()/s.getHeight()-(double)size.getWidth()/size.getHeight())*1000+Math.abs(s.getWidth()-1280))).orElseThrow();
                preview=ImageReader.newInstance(ps.getWidth(),ps.getHeight(),ImageFormat.PRIVATE,3,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
                preview.setOnImageAvailableListener(r -> {try(Image image=r.acquireLatestImage()) {}},handler);
                yuv=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,2);
                jpeg=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.JPEG,2);
                ArrayBlockingQueue<Image> ys=new ArrayBlockingQueue<>(1),jsq=new ArrayBlockingQueue<>(1);
                yuv.setOnImageAvailableListener(r -> {Image image=r.acquireNextImage();if(image!=null&&!ys.offer(image))image.close();},handler);
                jpeg.setOnImageAvailableListener(r -> {Image image=r.acquireNextImage();if(image!=null&&!jsq.offer(image))image.close();},handler);
                ArrayBlockingQueue<Object> opened=new ArrayBlockingQueue<>(1);
                java.util.concurrent.atomic.AtomicBoolean opening=new java.util.concurrent.atomic.AtomicBoolean(true);
                manager.openCamera(camera,new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) {if(stopped||!opening.get()||!opened.offer(d))d.close();else if(!opening.get()&&opened.remove(d))d.close();}
                    @Override public void onDisconnected(CameraDevice d) {d.close();opened.offer("disconnected");}
                    @Override public void onError(CameraDevice d,int error) {d.close();opened.offer("camera error");}
                },handler);
                Object op=opened.poll(10,TimeUnit.SECONDS);opening.set(false);Object lateOpen=opened.poll();if(lateOpen instanceof CameraDevice)((CameraDevice)lateOpen).close();if(!(op instanceof CameraDevice))throw new IllegalStateException("Camera open failed");device=(CameraDevice)op;
                ArrayBlockingQueue<Object> configured=new ArrayBlockingQueue<>(1);
                java.util.concurrent.atomic.AtomicBoolean configuring=new java.util.concurrent.atomic.AtomicBoolean(true);
                device.createCaptureSession(Arrays.asList(preview.getSurface(),yuv.getSurface(),jpeg.getSurface()),new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {if(stopped||!configuring.get()||!configured.offer(s))s.close();else if(!configuring.get()&&configured.remove(s))s.close();}
                    @Override public void onConfigureFailed(CameraCaptureSession s) {s.close();configured.offer("unsupported");}
                },handler);
                Object cs=configured.poll(10,TimeUnit.SECONDS);configuring.set(false);Object lateSession=configured.poll();if(lateSession instanceof CameraCaptureSession)((CameraCaptureSession)lateSession).close();if(!(cs instanceof CameraCaptureSession))throw new IllegalStateException("Combined stream configuration unavailable");session=(CameraCaptureSession)cs;
                CaptureRequest.Builder repeating=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);repeating.addTarget(preview.getSurface());
                repeating.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                repeating.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<>(30,30));
                java.util.concurrent.atomic.AtomicReference<TotalCaptureResult> latest=new java.util.concurrent.atomic.AtomicReference<>();
                session.setRepeatingRequest(repeating.build(),new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession ss,CaptureRequest r,TotalCaptureResult t) {latest.set(t);}
                },handler);SystemClock.sleep(3000);
                if(stopped)throw new IllegalStateException("Stopped");
                CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                request.addTarget(yuv.getSurface());request.addTarget(jpeg.getSurface());
                request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<>(30,30));
                int[] modes=chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
                if(modes!=null&&Arrays.stream(modes).anyMatch(n -> n==CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))request.set(CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                request.set(CaptureRequest.JPEG_QUALITY,(byte)95);
                if(compensate) {
                    TotalCaptureResult metering=Objects.requireNonNull(latest.get());
                    long oldExposure=Objects.requireNonNull(metering.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                    int oldIso=Objects.requireNonNull(metering.get(CaptureResult.SENSOR_SENSITIVITY));
                    Integer boost=metering.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
                    Range<Integer> isoRange=Objects.requireNonNull(chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE));
                    Range<Long> exposureRange=Objects.requireNonNull(chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE));
                    Range<Integer> boostRange=Objects.requireNonNull(chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE));
                    if(oldExposure<=0||oldIso<=0||boost==null||boost<=0)throw new IllegalStateException("Incomplete metering");
                    double effective=Math.min(oldIso,isoRange.getUpper())*(boost/100.0);
                    double budget=oldExposure*effective;
                    long exposure=exposureRange.clamp(Math.min(66_666_667L,(long)(budget/isoRange.getUpper())));
                    int iso=isoRange.clamp((int)Math.round(budget/exposure));
                    int digital=boostRange.clamp((int)Math.round(100*budget/(exposure*(double)iso)));
                    request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
                    request.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
                    request.set(CaptureRequest.SENSOR_FRAME_DURATION,Math.max(exposure,33_333_333L));
                    request.set(CaptureRequest.SENSOR_SENSITIVITY,iso);
                    request.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,digital);
                    request.set(CaptureRequest.CONTROL_AWB_LOCK,true);
                    request.set(CaptureRequest.CONTROL_ENABLE_ZSL,false);
                    CaptureRequest.Builder manualPreview=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    manualPreview.addTarget(preview.getSurface());
                    manualPreview.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
                    manualPreview.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
                    manualPreview.set(CaptureRequest.SENSOR_FRAME_DURATION,Math.max(exposure,33_333_333L));
                    manualPreview.set(CaptureRequest.SENSOR_SENSITIVITY,iso);
                    manualPreview.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,digital);
                    manualPreview.set(CaptureRequest.CONTROL_AWB_LOCK,true);
                    manualPreview.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    session.setRepeatingRequest(manualPreview.build(),new CameraCaptureSession.CaptureCallback() {
                        @Override public void onCaptureCompleted(CameraCaptureSession ss,CaptureRequest r,TotalCaptureResult t) {latest.set(t);}
                    },handler);
                    long settleEnd=SystemClock.elapsedRealtime()+2500;
                    while(SystemClock.elapsedRealtime()<settleEnd) {
                        TotalCaptureResult last=latest.get();Long actual=last==null?null:last.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        if(actual!=null&&Math.abs(actual-exposure)<=exposure/100)break;
                        if(stopped)throw new IllegalStateException("Stopped");SystemClock.sleep(30);
                    }
                    report.put("requestedExposureNs",exposure).put("requestedIso",iso).put("requestedBoost",digital)
                        .put("meteredExposureNs",oldExposure).put("meteredIso",oldIso).put("meteredBoost",boost);
                }
                ArrayBlockingQueue<Object> results=new ArrayBlockingQueue<>(1);long captureStart=SystemClock.elapsedRealtime();
                session.capture(request.build(),new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult t) {results.offer(t);}
                    @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f) {results.offer("capture failed");}
                },handler);
                yi=ys.poll(15,TimeUnit.SECONDS);ji=jsq.poll(5,TimeUnit.SECONDS);Object rr=results.poll(5,TimeUnit.SECONDS);
                if(yi==null||ji==null||!(rr instanceof TotalCaptureResult))throw new IllegalStateException("Missing paired capture");
                TotalCaptureResult result=(TotalCaptureResult)rr;
                Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
                if(timestamp==null||yi.getTimestamp()!=timestamp||ji.getTimestamp()!=timestamp)throw new IllegalStateException("Pair timestamps differ");
                if(compensate)report.put("manualExposureHonored",Math.abs(Objects.requireNonNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))-report.getLong("requestedExposureNs"))<=report.getLong("requestedExposureNs")/100);
                report.put("aeMode",result.get(CaptureResult.CONTROL_AE_MODE)).put("enableZsl",result.get(CaptureResult.CONTROL_ENABLE_ZSL)).put("frameDurationNs",result.get(CaptureResult.SENSOR_FRAME_DURATION));
                report.put("captureMs",SystemClock.elapsedRealtime()-captureStart).put("matchedSensorTimestamp",true)
                    .put("width",size.getWidth()).put("height",size.getHeight()).put("exposureNs",result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    .put("iso",result.get(CaptureResult.SENSOR_SENSITIVITY)).put("boost",result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
                    .put("noiseReductionMode",result.get(CaptureResult.NOISE_REDUCTION_MODE)).put("dataSpace",yi.getDataSpace())
                    .put("sensorOrientation",chars.get(CameraCharacteristics.SENSOR_ORIENTATION));
                ByteBuffer encoded=ji.getPlanes()[0].getBuffer();byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);Files.write(new File(root,"sensor-jpeg.jpg").toPath(),bytes);
                int w=yi.getWidth(),h=yi.getHeight();if(!yi.getCropRect().equals(new Rect(0,0,w,h)))throw new IllegalStateException("Unexpected YUV crop");
                byte[] nv21=new byte[Math.multiplyExact(w*h,3)/2];Image.Plane[] planes=yi.getPlanes();
                if(planes.length!=3)throw new IllegalStateException("Unexpected planes");
                for(int plane=0;plane<3;++plane) {
                    ByteBuffer input=planes[plane].getBuffer().duplicate();int base=input.position(),stride=planes[plane].getRowStride(),step=planes[plane].getPixelStride();
                    int pw=plane==0?w:w/2,ph=plane==0?h:h/2;
                    for(int y=0;y<ph;++y)for(int x=0;x<pw;++x) {
                        int target=plane==0?y*w+x:w*h+y*w+2*x+(plane==1?1:0);
                        nv21[target]=input.get(base+y*stride+x*step);
                    }
                }
                Files.write(new File(root,"sensor-yuv.nv21").toPath(),nv21);
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                if(!new YuvImage(nv21,ImageFormat.NV21,w,h,null).compressToJpeg(new Rect(0,0,w,h),95,output))throw new IllegalStateException("YUV encoding failed");
                Files.write(new File(root,"yuv-jpeg.jpg").toPath(),output.toByteArray());report.put("status","ok");
            } catch(Throwable error) {try {report.put("status","error").put("errorType",error.getClass().getSimpleName()).put("message",error.getMessage());}catch(Exception ignored) {}}
            finally {
                if(yi!=null)yi.close();if(ji!=null)ji.close();if(session!=null)session.close();if(device!=null)device.close();
                if(preview!=null)preview.close();if(yuv!=null)yuv.close();if(jpeg!=null)jpeg.close();callbacks.quitSafely();
                try {report.put("elapsedMs",SystemClock.elapsedRealtime()-start);Files.write(new File(root,"result.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored) {}
                runOnUiThread(() -> label.setText("Local comparison finished"));
            }
        },"yuv-jpeg-comparison").start();
    }
    @Override protected void onDestroy() {stopped=true;super.onDestroy();}
}
