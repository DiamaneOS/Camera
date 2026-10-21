package app.grapheneos.camera

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.grapheneos.camera.capturer.PhotoProcessingQueue

/** DUMP-protected queue commands for the synthetic debug documents provider. */
class RecoveryQueueTestActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val action=intent.getStringExtra("command")
        val id=intent.getStringExtra("job")
        if(action !in listOf("retry","cancel","abandon","resume") ||
            (action!="resume" && id?.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))!=true)) {
            finish();return
        }
        val deadline=SystemClock.elapsedRealtime()+5000
        val handler=Handler(Looper.getMainLooper())
        fun run() {
            if(PhotoProcessingQueue.isRecovering && SystemClock.elapsedRealtime()<deadline) {
                handler.postDelayed({run()},25);return
            }
            if(!PhotoProcessingQueue.isRecovering)when(action) {
                "retry"->PhotoProcessingQueue.retry(checkNotNull(id))
                "cancel"->PhotoProcessingQueue.cancel(checkNotNull(id))
                "abandon"->PhotoProcessingQueue.abandon(checkNotNull(id))
                "resume"->PhotoProcessingQueue.resume()
            }
            finish()
        }
        run()
    }
}
