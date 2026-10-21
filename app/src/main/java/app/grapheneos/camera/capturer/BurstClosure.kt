package app.grapheneos.camera.capturer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The end of a direct burst. Once its camera, the previous CameraX camera and all resources are
 * closed and its thread has ended, the burst finishes exactly once: CameraX is bound again.
 * Until then it rechecks every 250 ms, as often as closure events ask.
 */
internal class BurstClosure(private val lease: DirectCameraLease<*>, private val env: Env) {
    interface Env {
        fun now(): Long
        fun previousCameraClosed(): Boolean
        /** Whether the previous CameraX binding is still the one this burst took over. */
        fun ownsPreviousBinding(): Boolean
        fun unbindPrevious()
        /** Closure work off the main thread. Returns whether the burst thread ended. */
        fun runOffMain(action: () -> Unit)
        fun joinThread(): Boolean
        fun post(delayMs: Long, action: () -> Unit)
        /** Gives the camera back to CameraX. Runs once. */
        fun finish()
        fun activityDestroyed(): Boolean
        fun activityVisible(): Boolean
        fun notifySlowRecovery()
    }

    val recovered = CountDownLatch(1)
    private val closed = AtomicBoolean()
    private val finishing = AtomicBoolean()
    @Volatile private var startedAt = 0L
    private var notified = false // main thread

    /** The burst left its capture loop: from now on recovery may finish it. */
    fun begin() { startedAt = env.now(); closed.set(true) }

    /** Main thread. Safe to call any number of times, from any closure event. */
    fun recover() {
        if (!closed.get() || recovered.count == 0L) return
        if (lease.wasAcquired()) {
            if (env.previousCameraClosed()) lease.previousClosed()
            else if (env.ownsPreviousBinding()) {
                // The first unbind may have thrown. Retry only for the binding this burst
                // still owns, never a later replacement.
                try { env.unbindPrevious() } catch (_: Exception) { }
            }
        }
        if (lease.ready() && finishing.compareAndSet(false, true)) {
            env.runOffMain {
                if (!env.joinThread()) {
                    finishing.set(false); env.post(250, ::recover)
                } else env.post(0) {
                    try { env.finish() } finally { recovered.countDown() }
                }
            }
        } else if (!finishing.get()) {
            // Give up re-posting once the Activity is gone for good: nothing can rebind it.
            if (env.activityDestroyed() && env.now() - startedAt >= 30_000) { recovered.countDown(); return }
            if (!notified && env.now() - startedAt >= 3000 && env.activityVisible()) {
                notified = true; env.notifySlowRecovery()
            }
            env.post(250, ::recover)
        }
    }
}
