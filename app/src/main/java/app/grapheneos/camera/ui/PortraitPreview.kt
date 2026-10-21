package app.grapheneos.camera.ui

import android.content.*
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.TextureView
import android.os.*
import androidx.annotation.RequiresApi
import androidx.camera.view.PreviewView
import app.grapheneos.camera.capturer.PreviewWorkerPolicy
import app.grapheneos.camera.capturer.PortraitPreviewService
import app.grapheneos.camera.capturer.ProcessingClient
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Camera pixels render continuously; a permission-checked worker refreshes the matte. */
@RequiresApi(33)
internal class PortraitPreview(private val context: Context, private val view: PreviewView,
    private val onFailure: () -> Unit) : AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val background=PortraitBackgroundOverlay(context,view,onFailure)
    private val layoutListener=android.view.View.OnLayoutChangeListener {_,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom->
        if(right-left!=oldRight-oldLeft || bottom-top!=oldBottom-oldTop) {generation++;policy.invalidateMask();apply()}
    }
    private var connected: Messenger?=null
    private var bound=false
    private var gpu=false
    private var gpuFailed=false
    private var gpuReady=false
    private var connectionEpoch=0
    private var boundComponent:ComponentName?=null
    private val initializationTimeout:Runnable=Runnable {if(gpu && !gpuReady && !closed && !paused)processorDisconnected()}
    private val policy=app.grapheneos.camera.capturer.PortraitPreviewPolicy()
    private var paused=false
    private var closed=false
    private var generation=0
    private var pointX=.5f;private var pointY=.5f;private var blur=8
    private var diagnosticSequence:Int?=null
    private var diagnosticRender=true
    private var diagnosticInference=true
    private val diagnosticReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(closed || paused)return
            if(intent.action=="app.grapheneos.camera.DEBUG_PREVIEW_CONTROL") {
                diagnosticRender=intent.getBooleanExtra("render",true)
                diagnosticInference=intent.getBooleanExtra("inference",true)
                if(!diagnosticInference)policy.invalidateMask()
                apply()
            } else app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.requested=true
        }
    }
    private var diagnosticReceiverRegistered=false
    private val minimumUpdateIntervalMs=200L
    private var lastSubmittedAt = -minimumUpdateIntervalMs
    private var maskBitmap: Bitmap?=null
    private var effectVisible=false
    private val pixels=IntArray(512*512)
    private val pixelBuffer=ByteBuffer.allocateDirect(pixels.size*4).order(ByteOrder.nativeOrder())
    private val replies:Messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        if(message.what==0) {
            if(gpu && bound && !closed && !paused && message.arg1==connectionEpoch) {
                handler.removeCallbacks(initializationTimeout)
                gpuReady=message.data.getBoolean("ready")
                if(app.grapheneos.camera.BuildConfig.DEBUG)android.util.Log.i("PortraitPreviewReady","ready="+gpuReady+" warmupMs="+message.data.getLong("warmupMs"))
                if(!gpuReady)processorDisconnected()
            }
            return@Handler true
        }
        val current=policy.reply(message.arg1,generation,SystemClock.elapsedRealtime())
        var memory: SharedMemory?=null
        try {
            message.data.classLoader=SharedMemory::class.java.classLoader
            memory=message.data.getParcelable("mask",SharedMemory::class.java)
            if(app.grapheneos.camera.BuildConfig.DEBUG) android.util.Log.i("PortraitPreviewReply",
                "accepted="+current+" workerMs="+message.data.getLong("workerMs")+" inferenceMs="+message.data.getLong("inferenceMs")+
                " depthMs="+message.data.getLong("depthInferenceMs")+" processor="+message.data.getString("processor","cpu")+" refineMs="+message.data.getLong("refineMs")+" failure="+message.data.getString("failure","none"))
            if(app.grapheneos.camera.BuildConfig.DEBUG && current && message.data.containsKey("lowGpuPriority"))
                android.util.Log.i("PortraitPreviewGpuContext","low="+message.data.getBoolean("lowGpuPriority")+" current="+message.data.getBoolean("gpuContextCurrent"))
            if(gpu && current && message.data.getBoolean("failed")) {
                gpuFailed=true;generation++;policy.reset()
                if(bound) {context.unbindService(connection);bound=false};connected=null
                if(!paused && !closed)bound=bindProcessor()
            }
            if(!closed && !paused && current && memory!=null && (!app.grapheneos.camera.BuildConfig.DEBUG || diagnosticInference)) {
                val w=message.data.getInt("width");val h=message.data.getInt("height")
                require(w==512 && h==512 && memory.size==w*h*4)
                val format=message.data.getString("format")
                require(format=="confidence_argb8888" || format=="portrait_control_argb8888" && message.data.getInt("protocolVersion")==2)
                val mapping=memory.mapReadOnly();val pixels=IntArray(w*h)
                try {mapping.order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels)}
                finally {SharedMemory.unmap(mapping)}
                val next=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
                if(app.grapheneos.camera.BuildConfig.DEBUG && message.arg1==diagnosticSequence) {
                    diagnosticSequence=null
                    val directory=java.io.File(context.filesDir,"processing-test/preview-snapshot")
                    java.io.File(directory,"control.png").outputStream().use {next.compress(Bitmap.CompressFormat.PNG,100,it)}
                    java.io.File(directory,"control.json").writeText(org.json.JSONObject().put("processor",message.data.getString("processor","cpu"))
                        .put("format",format).put("depthAvailable",message.data.getBoolean("depthAvailable")).put("workerMs",message.data.getLong("workerMs")).toString())
                }
                val controls=format=="portrait_control_argb8888" && message.data.getBoolean("depthAvailable")
                val maximum=if(controls)pixels.maxOf {it ushr 8 and 255}/255f else 1f
                val old=maskBitmap;maskBitmap=next
                background.mask(next,controls,maximum);old?.recycle()
                apply()
                if(app.grapheneos.camera.BuildConfig.DEBUG) android.util.Log.i("PortraitPreview","inferenceMs="+message.data.getLong("inferenceMs"))
            } else if(current) { policy.invalidateMask();apply() }
        } catch (_: Exception) {policy.invalidateMask();apply()}
        finally {
            memory?.close()
            // The completed request has released the one-frame budget. Avoid
            // waiting another polling interval before starting its replacement.
            if(!closed && !paused) {handler.removeCallbacks(tick);handler.post(tick)}
        }
        true
    })
    private val connection:ServiceConnection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {
            if(name!=boundComponent || closed || paused)return
            if(gpu) try {
                val manager=context.packageManager
                val info=manager.getPackageInfo(name.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
                if(!PreviewWorkerPolicy.framesAllowed(info.requestedPermissions,
                    manager.checkPermission(PreviewWorkerPolicy.SENSOR_PERMISSION,name.packageName)==android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                    processorDisconnected();return
                }
            }
            catch(_:Exception) {processorDisconnected();return}
            connected=Messenger(binder)
            if(gpu)try {
                gpuReady=false;handler.removeCallbacks(initializationTimeout);handler.postDelayed(initializationTimeout,8_000)
                connected?.send(Message.obtain(null,0).apply {arg1=connectionEpoch;replyTo=replies})
            } catch(_:RemoteException) {if(name==boundComponent)processorDisconnected()}
        }
        override fun onServiceDisconnected(name: ComponentName) {if(name==boundComponent)processorDisconnected()}
        override fun onBindingDied(name: ComponentName) {if(name==boundComponent)processorDisconnected()}
        override fun onNullBinding(name: ComponentName) {if(name==boundComponent)processorDisconnected()}
    }
    private val tick:Runnable=object: Runnable {
        override fun run() {
            if(closed || paused) return
            try {
                val now=SystemClock.elapsedRealtime()
                if(!policy.fresh(now)) apply()
                val waiting=policy.busy
                policy.expire(now)
                if(gpu && waiting && !policy.busy)processorDisconnected()
                if((!app.grapheneos.camera.BuildConfig.DEBUG || diagnosticInference) && (!gpu || gpuReady) && !policy.busy && now-lastSubmittedAt>=minimumUpdateIntervalMs && blur>0 && connected!=null && view.previewStreamState.value==PreviewView.StreamState.STREAMING) {
                    val started=SystemClock.elapsedRealtime();val image=snapshot()
                    if(image!=null) {
                        val diagnostic=app.grapheneos.camera.BuildConfig.DEBUG && app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.requested
                        if(diagnostic) {
                            app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.requested=false
                            val directory=java.io.File(context.filesDir,"processing-test/preview-snapshot").apply {mkdirs()}
                            java.io.File(directory,"source.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
                            java.io.File(directory,"metadata.json").writeText(org.json.JSONObject().put("x",pointX).put("y",pointY)
                                .put("viewWidth",view.width).put("viewHeight",view.height).put("inputWidth",512).put("inputHeight",512)
                                .put("exposureNs",app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.exposureNs)
                                .put("frameDurationNs",app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.frameDurationNs).toString())
                        }
                        image.getPixels(pixels,0,512,0,0,512,512);image.recycle()
                        pixelBuffer.clear();pixelBuffer.asIntBuffer().put(pixels)
                        val memory=ProcessingClient.memory("portrait-preview-pixels",pixelBuffer)
                        try {lastSubmittedAt=SystemClock.elapsedRealtime();val sequence=policy.begin(lastSubmittedAt,generation);if(diagnostic)diagnosticSequence=sequence;connected?.send(Message.obtain(null,1).apply {
                            arg1=sequence;replyTo=replies;data=Bundle().apply {putParcelable("pixels",memory)
                                putInt("width",512);putInt("height",512);putFloat("aspect",view.width.toFloat()/view.height.coerceAtLeast(1));putFloat("x",pointX);putFloat("y",pointY)}
                        })} finally {memory.close()}
                        if(app.grapheneos.camera.BuildConfig.DEBUG) android.util.Log.i("PortraitPreview","captureMs="+(SystemClock.elapsedRealtime()-started))
                    }
                }
            } catch (_: Exception) {policy.reset();apply()}
            handler.postDelayed(this,60)
        }
    }
    /** Same public TextureView transforms used by pinned CameraX 1.6.1, with a bounded readback.
     * Reading the full PreviewView bitmap stalls the UI for roughly 90 ms on the test device. */
    private fun snapshot(): Bitmap? {
        val texture=(0 until view.childCount).map {view.getChildAt(it)}.filterIsInstance<TextureView>().firstOrNull()
        if(texture==null || texture.width<=0 || texture.height<=0 || view.width<=0 || view.height<=0) return null
        val raw=texture.getBitmap(512,512) ?: return null
        try {
            val output=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
            val matrix=Matrix().apply {
                setScale(texture.width/512f,texture.height/512f)
                postConcat(texture.getTransform(Matrix()))
                postConcat(texture.matrix)
                postTranslate(texture.left.toFloat(),texture.top.toFloat())
                postScale(512f/view.width,512f/view.height)
            }
            Canvas(output).drawBitmap(raw,matrix,Paint(Paint.FILTER_BITMAP_FLAG));return output
        } finally {raw.recycle()}
    }
    private fun processorDisconnected() {
        handler.removeCallbacks(initializationTimeout);gpuReady=false
        connected=null;policy.reset();apply()
        if(gpu && !closed && !paused) {
            gpuFailed=true;generation++
            if(bound) {context.unbindService(connection);bound=false}
            bound=bindProcessor()
        }
    }
    private fun bindProcessor():Boolean {
        // An optional companion must have our signature, a distinct UID, no permissions,
        // and a signature-protected service. A different package cannot receive frames.
        val target=app.grapheneos.camera.BuildConfig.PORTRAIT_PROCESSOR_PACKAGE
        handler.removeCallbacks(initializationTimeout);gpuReady=false;connectionEpoch++
        gpu=false
        if(!gpuFailed) try {
            val manager=context.packageManager
            val info=manager.getPackageInfo(target,android.content.pm.PackageManager.GET_PERMISSIONS)
            val component=ComponentName(target,"de.diamaneos.camera.processor.PortraitProcessorService")
            val service=manager.getServiceInfo(component,0)
            val permission=manager.getPermissionInfo(target+".BIND_PREVIEW",0)
            if(PreviewWorkerPolicy.declarationsAllowed(info.requestedPermissions) && info.applicationInfo?.uid!=android.os.Process.myUid() &&
                manager.checkSignatures(context.packageName,target)==android.content.pm.PackageManager.SIGNATURE_MATCH &&
                service.exported && service.enabled && service.permission==target+".BIND_PREVIEW" &&
                permission.packageName==target && permission.protection==android.content.pm.PermissionInfo.PROTECTION_SIGNATURE) {
                boundComponent=component
                gpu=context.bindService(Intent().setComponent(component),connection,Context.BIND_AUTO_CREATE)
                if(gpu)return true
            }
        } catch(_:Exception) {gpu=false}
        val cpu=Intent(context,PortraitPreviewService::class.java);boundComponent=cpu.component
        return context.bindService(cpu,connection,Context.BIND_AUTO_CREATE)
    }
    init {
        view.addOnLayoutChangeListener(layoutListener)
        view.setRenderEffect(null)
        if(app.grapheneos.camera.BuildConfig.DEBUG) {
            context.registerReceiver(diagnosticReceiver,IntentFilter("app.grapheneos.camera.DEBUG_PREVIEW_SNAPSHOT").apply {addAction("app.grapheneos.camera.DEBUG_PREVIEW_CONTROL")},
                "android.permission.DUMP",handler,Context.RECEIVER_EXPORTED)
            diagnosticReceiverRegistered=true
        }
        apply();resume()
    }
    private fun apply() {
        val visible=!paused && blur>0 && policy.fresh(SystemClock.elapsedRealtime())
        if(app.grapheneos.camera.BuildConfig.DEBUG && visible!=effectVisible)
            android.util.Log.i("PortraitPreviewVisibility","visible="+visible)
        effectVisible=visible
        background.effect(blur,visible && (!app.grapheneos.camera.BuildConfig.DEBUG || diagnosticRender))
    }
    fun parameters(x: Float,y: Float,radius: Int) {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && radius in 0..20)
        if(x!=pointX || y!=pointY) {generation++;policy.invalidateMask()}
        pointX=x;pointY=y;blur=radius;apply()
    }
    fun pause() {
        paused=true;generation++;connectionEpoch++;handler.removeCallbacks(initializationTimeout);handler.removeCallbacks(tick);policy.invalidateMask();apply()
        if(bound) {context.unbindService(connection);bound=false};connected=null;policy.reset()
    }
    fun resume() {
        if(closed) return
        paused=false
        if(!bound) bound=bindProcessor()
        if(!bound) handler.post {if(!closed) onFailure()}
        handler.removeCallbacks(tick);handler.post(tick)
    }
    override fun close() {closed=true;if(diagnosticReceiverRegistered) {context.unregisterReceiver(diagnosticReceiver);diagnosticReceiverRegistered=false};pause();view.removeOnLayoutChangeListener(layoutListener);background.close();view.setRenderEffect(null);maskBitmap?.recycle();maskBitmap=null}
}
