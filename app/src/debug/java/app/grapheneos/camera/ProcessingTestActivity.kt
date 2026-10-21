package app.grapheneos.camera

import android.app.Activity
import android.os.Bundle
import android.os.SharedMemory
import android.os.SystemClock
import android.widget.TextView
import app.grapheneos.camera.capturer.ProcessingClient
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Development-only, fixed private inputs. Never opens a camera or accepts external file paths. */
class ProcessingTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(true)
        val label = TextView(this).apply { text = "Processing saved test input" }
        setContentView(label)
        val mode = intent.getStringExtra("mode")
        thread(name = "saved-processing-test") {
            val root = File(filesDir, "processing-test").apply { mkdirs() }
            val result = JSONObject().put("mode", mode)
            val memories = arrayListOf<SharedMemory>()
            val started = SystemClock.elapsedRealtime()
            try {
                if (mode == "gallery") {
                    val items = CapturedItems.get(this)
                    result.put("items", org.json.JSONArray(items.map { item ->
                        JSONObject().put("date", item.dateString).put("raw", item.isRaw).put("type", item.type)
                    }))
                    result.put("status", "ok")
                    File(root, "gallery.json").writeText(result.toString(2))
                    runOnUiThread { label.text = result.toString(2) }
                    return@thread
                }
                val payload = Bundle()
                when (mode) {
                    "jpeg-burst" -> {
                        check(BuildConfig.CAMERA_JPEG_BURST_EVALUATION)
                        val directory = File(root, "jpeg-burst")
                        val planFile = File(directory, "plan.json")
                        require(planFile.length() in 1..4096)
                        val plan = JSONObject(planFile.readText())
                        val frames = plan.getJSONArray("frames"); require(frames.length() in 2..8)
                        for (index in 0 until frames.length()) {
                            val name = frames.getString(index)
                            require(name == "frame-%02d.jpg".format(java.util.Locale.ROOT, index))
                            val file = File(directory, name); require(file.length() in 4..32*1024*1024)
                            memories.add(ProcessingClient.fileMemory("test-jpeg-frame", file))
                        }
                        require(memories.sumOf { it.size.toLong() } <= 240L*1024*1024)
                        payload.putInt("mode", 4); payload.putParcelableArrayList("frames", ArrayList(memories))
                        payload.putInt("width", plan.getInt("width")); payload.putInt("height", plan.getInt("height"))
                        payload.putInt("quality", 95)
                        if (plan.has("gain")) payload.putFloat("jpegGain", plan.getDouble("gain").toFloat())
                    }
                    "jpeg-neural" -> {
                        val image=File(root,"rgb.jpg")
                        require(image.length() in 1..20*1024*1024)
                        val memory=ProcessingClient.fileMemory("test-rgb",image);memories.add(memory)
                        payload.putInt("mode",3);payload.putParcelable("jpeg",memory);payload.putInt("quality",95)
                        payload.putBoolean("neuralDenoiseTest",true)
                        payload.putBoolean("neuralForegroundTest",intent.getBooleanExtra("foreground",false))
                    }
                    "portrait" -> {
                        val image = File(root, "portrait.jpg")
                        require(image.length() in 1..20 * 1024 * 1024)
                        val memory = ProcessingClient.fileMemory("test-portrait", image)
                        memories.add(memory)
                        payload.putInt("mode", 2); payload.putParcelable("jpeg", memory)
                        payload.putFloat("x", .70f); payload.putFloat("y", .20f); payload.putInt("blur", 8)
                    }
                    "night" -> {
                        val planFile = File(root, "native/plan.json")
                        require(planFile.length() in 1..262144)
                        val plan = JSONObject(planFile.readText())
                        val frames = plan.getJSONArray("frames")
                        require(frames.length() in 2..8)
                        val strides = IntArray(frames.length())
                        val calibration = FloatArray(frames.length() * 13)
                        for (index in 0 until frames.length()) {
                            val frame = frames.getJSONObject(index)
                            val name = frame.getString("file")
                            require(name.matches(Regex("frame-[0-9]{2}\\.bin")))
                            val file = File(root, "native/" + name)
                            require(file.length() in 1..32 * 1024 * 1024)
                            val memory = ProcessingClient.fileMemory("test-raw", file)
                            memories.add(memory)
                            require(memories.sumOf { it.size.toLong() } <= 240L * 1024 * 1024)
                            strides[index] = frame.getInt("stride")
                            val values = frame.getJSONArray("calibration")
                            require(values.length() == 13)
                            for (c in 0 until 13) calibration[index * 13 + c] = values.getDouble(c).toFloat()
                        }
                        payload.putInt("mode", 1); payload.putParcelableArrayList("frames", ArrayList(memories))
                        if (plan.optBoolean("rawShading", false)) {
                            val shading = ProcessingClient.fileMemory("test-shading", File(root, "native/shading.bin"),
                                app.grapheneos.camera.capturer.RawShadingData.MAX_BYTES.toLong())
                            memories.add(shading); payload.putParcelable("rawShading", shading)
                        }
                        payload.putIntArray("strides", strides); payload.putFloatArray("calibration", calibration)
                        for (name in listOf("width", "height", "cfa", "reference")) payload.putInt(name, plan.getInt(name))
                        payload.putFloat("exposure", plan.getDouble("exposure").toFloat())
                        val edge=plan.optInt("edgeAwareDemosaic",0);require(edge in 0..1);payload.putInt("edgeAwareDemosaic",edge)
                        val frequency=plan.optInt("frequencyDenoise",0);require(frequency in 0..1);payload.putInt("frequencyDenoise",frequency)
                        val steadyFinish=plan.optInt("steadyPhotoFinish",0);require(steadyFinish in 0..1);payload.putInt("steadyPhotoFinish",steadyFinish)
                        for(name in listOf("targetLuminance","zoom","aspect"))if(plan.has(name))payload.putFloat(name,plan.getDouble(name).toFloat().also {require(it.isFinite())})
                        if(plan.has("referenceRotation"))payload.putInt("referenceRotation",plan.getInt("referenceRotation"))
                        for(name in listOf("periodicGrid","colorReference"))if(plan.has(name)) {
                            val array=plan.getJSONArray(name);require(array.length()==if(name=="periodicGrid")4 else 9216)
                            payload.putIntArray(name,IntArray(array.length()) {array.getInt(it)})
                        }
                        if(plan.has("toneTargets")) {
                            val array=plan.getJSONArray("toneTargets");require(array.length()==3)
                            payload.putFloatArray("toneTargets",FloatArray(3) {array.getDouble(it).toFloat().also {v->require(v.isFinite())}})
                        }
                        val color = plan.getJSONArray("color")
                        require(color.length() == 9)
                        payload.putFloatArray("color", FloatArray(9) { color.getDouble(it).toFloat() })
                    }
                    else -> error("Unknown fixed test mode")
                }
                result.put("prepareMs", SystemClock.elapsedRealtime() - started)
                val stats = Bundle()
                val bytes = ProcessingClient.process(applicationContext, payload, { false }, stats)
                for (key in stats.keySet()) result.put(key, stats.get(key))
                check(stats.getInt("cameraPermission", -999) == -1) { "Worker inherited camera permission" }
                File(root, mode + "-result.jpg").writeBytes(bytes)
                result.put("status", "ok").put("bytes", bytes.size)
            } catch (error: Throwable) {
                result.put("status", "error").put("error", error.javaClass.simpleName + ": " + error.message)
            } finally { memories.forEach(SharedMemory::close) }
            result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(root, "result.json").writeText(result.toString(2))
            runOnUiThread { label.text = result.toString(2) }
        }
    }
}
