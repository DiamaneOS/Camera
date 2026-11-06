package app.grapheneos.camera

import app.grapheneos.camera.capturer.PortraitDepthModel
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.*
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Debug-only: existing approved models, read-only input, no app permissions or files. */
class GpuBenchmarkService: Service() {
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean()
    private val messages=Messenger(Handler(Looper.getMainLooper()) { message ->
        val data=message.data.apply {classLoader=SharedMemory::class.java.classLoader}
        val input=try {androidx.core.os.BundleCompat.getParcelable(data,"pixels",SharedMemory::class.java)} catch(_:RuntimeException) {null}
        val reply=message.replyTo
        if(message.what!=1 || input==null || reply==null || !busy.compareAndSet(false,true)) input?.close()
        else executor.execute {
            val result=Bundle()
            try {
                android.util.Log.i("GpuTestStage","input")
                check(android.os.Process.isIsolated())
                check(checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_DENIED)
                check(checkSelfPermission(android.Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED)
                result.putBoolean("isolated",true);result.putBoolean("cameraDenied",true);result.putBoolean("networkDenied",true)
                require(input.size==512*512*4)
                val pixels=IntArray(512*512);val map=input.mapReadOnly()
                try {map.order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels)} finally {SharedMemory.unmap(map)}
                val depth=data.getBoolean("depth")
                val bytes=if(depth) assets.open(PortraitDepthModel.ASSET).use {PortraitDepthModel.read(it)}.let {m -> ByteArray(m.remaining()).also {m.get(it)}}
                    else assets.open("portrait_subject_v1.tflite").use {it.readBytes()}
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)}
                check(if(depth) bytes.size==PortraitDepthModel.BYTES && hash==PortraitDepthModel.SHA256
                    else bytes.size==6_227_884 && hash=="e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25")
                val side=if(depth) 518 else 512;val channels=if(depth) 3 else 4
                val sampled=if(depth) {
                    val source=Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888)
                    val resized=Bitmap.createScaledBitmap(source,side,side,true)
                    try {IntArray(side*side).also {resized.getPixels(it,0,side,0,0,side,side)}} finally {resized.recycle();source.recycle()}
                } else pixels
                val values=FloatArray(side*side*channels)
                for(i in sampled.indices) {
                    val p=sampled[i];values[i*channels]=(p shr 16 and 255)/255f
                    values[i*channels+1]=(p shr 8 and 255)/255f;values[i*channels+2]=(p and 255)/255f
                    if(!depth) values[i*4+3]=if(i==(side/2)*side+side/2) 1f else 0f
                }
                val modelBytes=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {put(bytes);rewind()}
                val cpu=FloatArray(side*side)
                android.util.Log.i("GpuTestStage","cpu")
                Interpreter(modelBytes,Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)).use { interpreter ->
                    require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1,side,side,channels)))
                    require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1,side,side,1)))
                    val source=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).apply {asFloatBuffer().put(values)}
                    val output=ByteBuffer.allocateDirect(cpu.size*4).order(ByteOrder.nativeOrder())
                    val start=SystemClock.elapsedRealtime();interpreter.run(source,output)
                    result.putLong("cpuMs",SystemClock.elapsedRealtime()-start)
                    output.rewind();output.asFloatBuffer().get(cpu)
                }
                // The depth model ships gzipped and CompiledModel loads assets by name, so depth stays CPU only here.
                if(depth) {result.putString("gpu","skipped");result.putBoolean("success",true)}
                else {
                val options=CompiledModel.Options(Accelerator.GPU).apply {
                    gpuOptions=CompiledModel.GpuOptions(backend=CompiledModel.GpuOptions.Backend.OPENGL)
                }
                val start=SystemClock.elapsedRealtime()
                android.util.Log.i("GpuTestStage","compile")
                CompiledModel.create(assets,"portrait_subject_v1.tflite",options).use { model ->
                    result.putLong("gpuCompileMs",SystemClock.elapsedRealtime()-start)
                    val inputs=model.createInputBuffers();val outputs=model.createOutputBuffers()
                    try {
                        require(inputs.size==1 && outputs.size==1)
                        inputs[0].writeFloat(values)
                        val elapsed=LongArray(3)
                        var gpu=FloatArray(0)
                        for(i in elapsed.indices) {
                            android.util.Log.i("GpuTestStage","run")
                            val run=SystemClock.elapsedRealtime();model.run(inputs,outputs);gpu=outputs[0].readFloat()
                            elapsed[i]=SystemClock.elapsedRealtime()-run
                        }
                        require(gpu.size==cpu.size && gpu.all {it.isFinite() && if(depth) it in 0f..10_000f else it in 0f..1f})
                        require(cpu.all {it.isFinite()})
                        result.putLongArray("gpuRunMs",elapsed)
                        result.putDouble("maxAbsDifference",gpu.indices.maxOf {abs(gpu[it]-cpu[it]).toDouble()})
                        result.putDouble("meanAbsDifference",gpu.indices.sumOf {abs(gpu[it]-cpu[it]).toDouble()}/gpu.size)
                        result.putBoolean("success",true)
                    } finally {inputs.forEach {it.close()};outputs.forEach {it.close()}}
                }
                }
            } catch(error:Throwable) {result.putString("errorType",error.javaClass.simpleName);result.putString("error",error.message?.take(250))}
            finally {input.close()}
            try {reply.send(Message.obtain(null,1).apply {this.data=result})} catch(_:RemoteException) {}
            finally {busy.set(false);stopSelf()}
        }
        true
    })
    override fun onBind(intent:Intent)=messages.binder
    override fun onDestroy() {executor.shutdownNow();super.onDestroy()}
}
