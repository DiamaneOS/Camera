package app.grapheneos.camera

import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** One-shot development sampler, enabled only by a fixed app-private request file. */
internal object StartupDiagnostics {
    fun startIfRequested(context: Context) {
        if (!BuildConfig.DEBUG) return
        val request=File(context.filesDir,"processing-test/startup-sample-request")
        if(!request.isFile || !request.delete()) return
        val main=Looper.getMainLooper().thread
        val started=SystemClock.uptimeMillis()
        val age=started-Process.getStartUptimeMillis()
        thread(name="camera-startup-sampler",isDaemon=true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val counts=linkedMapOf<String,Int>();var samples=0;var written=started
            while(SystemClock.uptimeMillis()-started<15_000) {
                val stack=main.stackTrace.take(24).joinToString("\n")
                counts[stack]=(counts[stack] ?: 0)+1;samples++
                val now=SystemClock.uptimeMillis()
                if(now-written>=1000) {
                    written=now
                    try {
                        val rows=JSONArray();counts.entries.sortedByDescending {it.value}.take(120).forEach {
                            rows.put(JSONObject().put("stack",it.key).put("count",it.value))
                        }
                        File(File(context.filesDir,"processing-test").apply { mkdirs() },"startup-samples.json").writeText(JSONObject().put("processAgeAtSamplerMs",age)
                            .put("elapsedMs",now-started).put("samples",samples).put("stacks",rows).toString())
                    } catch (_: Exception) { }
                }
                Thread.sleep(25)
            }
        }
    }
}
