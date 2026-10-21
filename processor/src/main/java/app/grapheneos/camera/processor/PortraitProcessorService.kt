package app.grapheneos.camera.processor

import app.grapheneos.camera.capturer.PreviewWorkerPolicy
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.system.OsConstants
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Optional separate-UID GPU worker. No permissions, activities, files, URIs or network input. */
class PortraitProcessorService:Service() {
    companion object {init {System.loadLibrary("portrait_preview")}}
    private val worker=Executors.newSingleThreadExecutor()
    private val segmentationWorker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean()
    private val ready=AtomicBoolean()
    private val watchdog=Handler(Looper.getMainLooper())
    private val closing=AtomicBoolean()
    private var model:Segmenter?=null
    private var depth:DepthEstimator?=null
    private var gpuContext:PreviewGpuContext?=null
    private fun depthEstimator():DepthEstimator {
        val context=gpuContext ?: PreviewGpuContext().also {gpuContext=it}
        context.makeCurrent()
        return depth ?: DepthEstimator(this).also {depth=it}
    }
    private var depthDisabled=false
    private external fun controls(source:IntArray,mask:FloatArray,depth:FloatArray?,width:Int,height:Int,x:Float,y:Float):IntArray
    private class Segmenter(service:Service):AutoCloseable {
        private val model:CompiledModel
        init {
            val bytes=service.assets.open("portrait_subject_v1.tflite").use {it.readBytes()}
            check(bytes.size==6_227_884 && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}==
                "e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25")
            // Run subject inference on CPU concurrently with GPU depth. These
            // independent models read the same immutable pixels; serial GPU
            // execution otherwise consumes the entire mask freshness budget.
            model=CompiledModel.create(service.assets,"portrait_subject_v1.tflite",CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions=CompiledModel.CpuOptions(numThreads=2)
            })
        }
        private val inputs=try {model.createInputBuffers()} catch(error:Exception) {model.close();throw error}
        private val outputs=try {model.createOutputBuffers()} catch(error:Exception) {inputs.forEach {it.close()};model.close();throw error}
        private val values=FloatArray(512*512*4)
        fun segment(pixels:IntArray,x:Float,y:Float):FloatArray {
            require(pixels.size==512*512 && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
            val seed=minOf(511,(y*512).toInt())*512+minOf(511,(x*512).toInt())
            for(i in pixels.indices) {
                val p=pixels[i];values[i*4]=(p shr 16 and 255)/255f
                values[i*4+1]=(p shr 8 and 255)/255f;values[i*4+2]=(p and 255)/255f;values[i*4+3]=if(i==seed)1f else 0f
            }
            require(inputs.size==1 && outputs.size==1)
            inputs[0].writeFloat(values);model.run(inputs,outputs)
            return outputs[0].readFloat().also {require(it.size==512*512 && it.all {v->v.isFinite() && v in 0f..1f})}
        }
        override fun close() {inputs.forEach {it.close()};outputs.forEach {it.close()};model.close()}
    }
    private data class SegmentResult(val mask:FloatArray,val milliseconds:Long)
    private fun segment(pixels:IntArray,x:Float,y:Float)=segmentationWorker.submit<SegmentResult> {
        check(!closing.get())
        val segmenter=model ?: Segmenter(this).also {model=it}
        val started=SystemClock.elapsedRealtime()
        SegmentResult(segmenter.segment(pixels,x,y),SystemClock.elapsedRealtime()-started)
    }
    private data class DepthResult(val width:Int,val height:Int,val values:FloatArray)
    private class DepthEstimator(service:Service):AutoCloseable {
        companion object {private const val PREVIEW_DEPTH_SIDE=224}
        private val model:CompiledModel
        init {
            val bytes=service.assets.open("portrait_preview_depth_v2_small.tflite").use {it.readBytes()}
            check(bytes.size==48_740_176 && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}==
                "bde1e29c8fe0be113901d87ad9fa9070a3ef5d4f1513d9cc65afc1248853a704")
            model=CompiledModel.create(service.assets,"portrait_preview_depth_v2_small.tflite",CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions=CompiledModel.GpuOptions(backend=CompiledModel.GpuOptions.Backend.OPENGL)
            })
        }
        private val inputs=try {model.createInputBuffers()} catch(error:Exception) {model.close();throw error}
        private val outputs=try {model.createOutputBuffers()} catch(error:Exception) {inputs.forEach {it.close()};model.close();throw error}
        fun estimate(pixels:IntArray,aspect:Float):DepthResult {
            require(pixels.size==512*512 && aspect.isFinite() && aspect in .2f..5f)
            // Live matte input follows the subject model's STRETCH contract. Restore
            // the viewport aspect for depth, whose saved-photo path uses letterboxing.
            val w=if(aspect<1)maxOf(2,kotlin.math.round(PREVIEW_DEPTH_SIDE*aspect).toInt()) else PREVIEW_DEPTH_SIDE
            val h=if(aspect>1)maxOf(2,kotlin.math.round(PREVIEW_DEPTH_SIDE/aspect).toInt()) else PREVIEW_DEPTH_SIDE
            val left=(PREVIEW_DEPTH_SIDE-w)/2;val top=(PREVIEW_DEPTH_SIDE-h)/2
            val source=android.graphics.Bitmap.createBitmap(pixels,512,512,android.graphics.Bitmap.Config.ARGB_8888)
            val resized=android.graphics.Bitmap.createBitmap(PREVIEW_DEPTH_SIDE,PREVIEW_DEPTH_SIDE,android.graphics.Bitmap.Config.ARGB_8888)
            val small=IntArray(PREVIEW_DEPTH_SIDE*PREVIEW_DEPTH_SIDE)
            try {
                val canvas=android.graphics.Canvas(resized);canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(source,null,android.graphics.RectF(left.toFloat(),top.toFloat(),(left+w).toFloat(),(top+h).toFloat()),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                resized.getPixels(small,0,PREVIEW_DEPTH_SIDE,0,0,PREVIEW_DEPTH_SIDE,PREVIEW_DEPTH_SIDE)
            } finally {source.recycle();resized.recycle()}
            val values=FloatArray(PREVIEW_DEPTH_SIDE*PREVIEW_DEPTH_SIDE*3)
            for(i in small.indices) {
                val p=small[i];values[i*3]=(p shr 16 and 255)/255f
                values[i*3+1]=(p shr 8 and 255)/255f;values[i*3+2]=(p and 255)/255f
            }
            require(inputs.size==1 && outputs.size==1)
            inputs[0].writeFloat(values);model.run(inputs,outputs);val full=outputs[0].readFloat()
            require(full.size==PREVIEW_DEPTH_SIDE*PREVIEW_DEPTH_SIDE && full.all {it.isFinite() && it in 0f..10_000f})
            val cropped=FloatArray(w*h)
            for(y in 0 until h)System.arraycopy(full,(top+y)*PREVIEW_DEPTH_SIDE+left,cropped,y*w,w)
            return DepthResult(w,h,cropped)
        }
        override fun close() {inputs.forEach {it.close()};outputs.forEach {it.close()};model.close()}
    }
    private fun reply(target:Messenger?,sequence:Int,result:Bundle,what:Int=1) {
        try {target?.send(Message.obtain(null,what).apply {arg1=sequence;data=result})} catch(_:RemoteException) {}
    }
    /** Compile and run on synthetic black input before accepting any camera frame. */
    private fun warmup(message:Message) {
        val target=message.replyTo ?: return
        val sequence=message.arg1
        if(closing.get() || !busy.compareAndSet(false,true)) {
            reply(target,sequence,Bundle().apply {putBoolean("ready",false)},0);return
        }
        val deadline=Runnable {if(busy.get())android.os.Process.killProcess(android.os.Process.myPid())}
        watchdog.postDelayed(deadline,8_000)
        worker.execute {
            val result=Bundle();val started=SystemClock.elapsedRealtime()
            try {
                check(checkSelfPermission(PreviewWorkerPolicy.SENSOR_PERMISSION)==PackageManager.PERMISSION_DENIED)
                check(checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_DENIED)
                check(checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED)
                check(!closing.get())
                val pixels=IntArray(512*512)
                val segmented=segment(pixels,.5f,.5f)
                check(!closing.get())
                if(!depthDisabled)try {
                    val estimator=depthEstimator()
                    estimator.estimate(pixels,1f)
                } catch(_:Exception) {depthDisabled=true;try {depth?.close()} catch(_:Exception) {};depth=null}
                segmented.get()
                check(!closing.get());ready.set(true);result.putBoolean("ready",true)
            } catch(error:Exception) {result.putBoolean("ready",false);result.putString("failure",error.javaClass.simpleName)}
            result.putBoolean("lowGpuPriority",gpuContext?.lowPriority==true)
            result.putBoolean("gpuContextCurrent",gpuContext?.isCurrent()==true)
            result.putLong("warmupMs",SystemClock.elapsedRealtime()-started)
            watchdog.removeCallbacks(deadline);busy.set(false)
            reply(target,sequence,result,0)
        }
    }
    private fun handle(message:Message):Boolean {
        if(message.what==0) {warmup(message);return true}
        val data=message.data.apply {classLoader=SharedMemory::class.java.classLoader}
        val input=try {data.getParcelable("pixels",SharedMemory::class.java)} catch(_:RuntimeException) {null}
        val target=message.replyTo;val sequence=message.arg1
        if(message.what!=1 || input==null || target==null || closing.get() || !busy.compareAndSet(false,true)) {
            input?.close();reply(target,sequence,Bundle().apply {putBoolean("failed",true)})
        } else {
            val deadline=Runnable {if(busy.get())android.os.Process.killProcess(android.os.Process.myPid())}
            watchdog.postDelayed(deadline,5_000)
            worker.execute {
            val result=Bundle();var output:SharedMemory?=null;val started=SystemClock.elapsedRealtime()
            try {
                check(ready.get())
                check(checkSelfPermission(PreviewWorkerPolicy.SENSOR_PERMISSION)==PackageManager.PERMISSION_DENIED)
                check(checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_DENIED)
                check(checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED)
                require(data.getInt("width")==512 && data.getInt("height")==512 && input.size==512*512*4)
                val pixels=IntArray(512*512);val map=input.mapReadOnly()
                try {map.order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels)} finally {SharedMemory.unmap(map)}
                val segmented=segment(pixels,data.getFloat("x"),data.getFloat("y"))
                check(!closing.get())
                val depthStart=SystemClock.elapsedRealtime()
                val estimate=if(depthDisabled)null else try {
                    val estimator=depthEstimator()
                    estimator.estimate(pixels,data.getFloat("aspect",1f))
                } catch(_:Exception) {depthDisabled=true;try {depth?.close()} catch(_:Exception) {};depth=null;null}
                result.putLong("depthInferenceMs",SystemClock.elapsedRealtime()-depthStart)
                val segmentation=segmented.get()
                result.putLong("inferenceMs",segmentation.milliseconds)
                check(!closing.get())
                val refinement=SystemClock.elapsedRealtime()
                val refined=controls(pixels,segmentation.mask,estimate?.values,estimate?.width ?: 0,estimate?.height ?: 0,data.getFloat("x"),data.getFloat("y"))
                result.putLong("refineMs",SystemClock.elapsedRealtime()-refinement)
                output=SharedMemory.create("portrait-preview-mask",512*512*4)
                val mapping=output.mapReadWrite()
                try {mapping.order(ByteOrder.nativeOrder()).asIntBuffer().put(refined)}
                finally {SharedMemory.unmap(mapping)}
                check(output.setProtect(OsConstants.PROT_READ))
                result.putParcelable("mask",output);result.putInt("width",512);result.putInt("height",512)
                result.putBoolean("depthAvailable",estimate!=null && refined.any {(it ushr 8 and 255)<255})
                result.putString("format","portrait_control_argb8888");result.putString("processor",if(estimate!=null)"cpu_seg_gpu_depth" else "cpu_seg");result.putInt("protocolVersion",2)
            } catch(error:Exception) {result.putBoolean("failed",true);result.putString("failure","gpu:"+error.javaClass.simpleName)}
            finally {input.close()}
            result.putBoolean("lowGpuPriority",gpuContext?.lowPriority==true)
            result.putBoolean("gpuContextCurrent",gpuContext?.isCurrent()==true)
            result.putLong("workerMs",SystemClock.elapsedRealtime()-started)
            watchdog.removeCallbacks(deadline);busy.set(false)
            try {reply(target,sequence,result)} finally {output?.close()}
            }
        }
        return true
    }
    private val messages:Messenger=Messenger(Handler(Looper.getMainLooper()) {handle(it)})

    override fun onBind(intent:Intent):IBinder? {
        val requested=packageManager.getPackageInfo(packageName,PackageManager.GET_PERMISSIONS).requestedPermissions
        if(!PreviewWorkerPolicy.declarationsAllowed(requested))return null
        if(checkSelfPermission(PreviewWorkerPolicy.SENSOR_PERMISSION)==PackageManager.PERMISSION_GRANTED) {
            // No frames have been accepted. Revocation kills this disposable worker once
            // it is unbound; a future bind can then use the GPU without sensor access.
            try {revokeSelfPermissionOnKill(PreviewWorkerPolicy.SENSOR_PERMISSION)} catch(_:RuntimeException) {}
            return null
        }
        return messages.binder
    }
    override fun onDestroy() {
        closing.set(true)
        worker.execute {try {depth?.close();depth=null} finally {gpuContext?.close();gpuContext=null}};worker.shutdown()
        segmentationWorker.execute {model?.close();model=null};segmentationWorker.shutdown()
        super.onDestroy()
    }
}
