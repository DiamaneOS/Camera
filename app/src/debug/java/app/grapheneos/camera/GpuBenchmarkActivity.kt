package app.grapheneos.camera

import android.app.Activity
import android.content.*
import android.graphics.*
import android.os.*
import android.widget.TextView
import app.grapheneos.camera.capturer.ProcessingClient
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shell/DUMP-only front end for a fixed-path, isolated saved-fixture benchmark. */
class GpuBenchmarkActivity: Activity() {
    private var bound=false
    private val handler=Handler(Looper.getMainLooper())
    private val directory by lazy {File(filesDir,"processing-test/gpu").apply {mkdirs()}}
    private val timeout:Runnable=Runnable {write(JSONObject().put("status","timeout"));if(bound) {unbindService(connection);bound=false}}
    private val replies:Messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        handler.removeCallbacks(timeout)
        val row=JSONObject().put("status","complete")
        message.data.classLoader=SharedMemory::class.java.classLoader
        val output=message.data.getParcelable("output",SharedMemory::class.java)
        if(output!=null) {
            try {
                require(output.size in 1..64_000_000)
                val mapping=output.mapReadOnly()
                try {File(directory,"full-output.f32").outputStream().channel.use {channel -> while(mapping.hasRemaining())channel.write(mapping)}}
                finally {SharedMemory.unmap(mapping)}
                row.put("outputBytes",output.size)
            } catch(error:Exception) {row.put("outputFailure",error.javaClass.simpleName)}
            finally {output.close()}
        }
        for(key in message.data.keySet()) {
            if(key=="output")continue
            val value=message.data.get(key)
            row.put(key,if(value is LongArray) org.json.JSONArray(value.toList()) else value)
        }
        write(row)
        if(bound) {unbindService(connection);bound=false}
        true
    })
    private fun write(result:JSONObject) {File(directory,"result.json").writeText(result.toString(2))}
    private val connection:ServiceConnection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            try {
                val kind=intent.getIntExtra("kind",if(intent.getBooleanExtra("depth",false)) 1 else 0)
                require(kind in 0..4)
                require(kind<2 || intent.getBooleanExtra("helper",false))
                val buffer=if(kind==4) null else if(kind==2) {
                    val file=File(directory,"raw-input.f32");require(file.length()==400L*528*4*4)
                    ByteBuffer.allocateDirect(file.length().toInt()).order(ByteOrder.nativeOrder()).apply {put(file.readBytes());rewind()}
                } else {
                    val file=File(directory,"source.jpg");require(file.length() in 1..20*1024*1024)
                    val source=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {decoder,info,_ ->
                    require(info.size.width.toLong()*info.size.height<=16_000_000)
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
                val small=Bitmap.createScaledBitmap(source,512,512,true);val pixels=IntArray(512*512)
                try {small.getPixels(pixels,0,512,0,0,512,512)} finally {if(small!==source)small.recycle();source.recycle()}
                    val pixelsBuffer=ByteBuffer.allocateDirect(pixels.size*4).order(ByteOrder.nativeOrder()).apply {asIntBuffer().put(pixels)}
                    pixelsBuffer
                }
                val input=if(kind==4) ProcessingClient.fileMemory("gpu-test-raw",File(directory,"full-input.f32"),64_000_000L)
                    else ProcessingClient.memory("gpu-test-pixels",requireNotNull(buffer))
                input.use {memory ->
                    Messenger(binder).send(Message.obtain(null,1).apply {replyTo=replies;data=Bundle().apply {
                        putParcelable("pixels",memory);putBoolean("depth",kind==1);putInt("kind",kind)
                        if(kind==4) {
                            val meta=JSONObject(File(directory,"full-input.json").readText())
                            val padding=meta.getJSONArray("padding");require(padding.length()==4)
                            putInt("width",meta.getInt("width"));putInt("height",meta.getInt("height"))
                            putFloatArray("padding",FloatArray(4) {padding.getDouble(it).toFloat()})
                        }
                    }})
                }
            } catch(error:Exception) {write(JSONObject().put("status","failed").put("errorType",error.javaClass.simpleName))}
        }
        override fun onServiceDisconnected(name:ComponentName) {write(JSONObject().put("status","worker_disconnected"))}
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);setContentView(TextView(this).apply {text="Testing isolated local Portrait processing"})
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        write(JSONObject().put("status","running"))
        // The external helper must be signed like this build: never hand frames to another app.
        val helper=intent.getBooleanExtra("helper",false)
        if(helper && packageManager.checkSignatures(packageName,"app.grapheneos.camera.gpuprobe")!=android.content.pm.PackageManager.SIGNATURE_MATCH) {
            write(JSONObject().put("status","helper_signature_mismatch"));return
        }
        val service=if(helper) Intent().setComponent(ComponentName(
            "app.grapheneos.camera.gpuprobe","app.grapheneos.camera.gpuprobe.GpuBenchmarkService"))
            else Intent(this,GpuBenchmarkService::class.java)
        bound=bindService(service,connection,Context.BIND_AUTO_CREATE)
        if(bound)handler.postDelayed(timeout,45_000) else write(JSONObject().put("status","bind_failed"))
    }
    override fun onDestroy() {handler.removeCallbacks(timeout);if(bound)unbindService(connection);super.onDestroy()}
}
