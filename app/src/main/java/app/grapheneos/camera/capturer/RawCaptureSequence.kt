package app.grapheneos.camera.capturer

import android.graphics.ImageFormat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The order of a RAW capture. A split JPEG + RAW capture takes the JPEG, rebinds for RAW alone
 * with the JPEG's exposure and focus, takes the RAW, then gives the camera back its own options
 * and the JPEG binding, whatever the outcome. Every end runs once.
 */
internal class RawCaptureSequence(private val port: Port, private val jpegAndRaw: Boolean, private val split: Boolean) {
    interface Port {
        fun scheduleTimeout()
        fun cancelTimeout()
        /** Split: the JPEG alone. Otherwise the RAW, with the JPEG when both were asked for. */
        fun takeFirst()
        fun onMain(action: () -> Unit)
        fun isCurrent(): Boolean
        /** Reads the JPEG's capture settings, before the rebind starts a new result generation. */
        fun rememberJpegSettings()
        /** Enters the RAW phase and rebinds for RAW alone. Throws when that binding is not RAW. */
        fun bindRaw()
        /** Gives the RAW the JPEG's exposure and focus, keeping the camera's own options to restore. */
        fun matchJpegSettings()
        fun takeRaw()
        /** Puts back the camera's own options, if any were replaced. */
        fun restoreOptions()
        /** Leaves the RAW phase. Returns whether it was still active. */
        fun leaveRawPhase(): Boolean
        /** Rebinds for the JPEG, unless a lens or mode change already rebound the camera. */
        fun rebindJpeg()
        fun save()
        fun discard()
        fun reportError(error: Exception)
    }

    private val ended = AtomicBoolean()
    private val received = hashSetOf<Int>()
    private val expected = if (jpegAndRaw) setOf(ImageFormat.RAW_SENSOR, ImageFormat.JPEG) else setOf(ImageFormat.RAW_SENSOR)

    init { require(!split || jpegAndRaw) }

    fun start() {
        port.scheduleTimeout()
        try { port.takeFirst() } catch (error: Exception) { fail(IllegalStateException("Cannot start RAW capture", error)) }
    }

    /** A file is saved: once all are there the pair is published. */
    fun saved(format: Int) {
        if (ended.get()) { port.discard(); return }
        val complete = synchronized(received) {
            if (format !in expected || !received.add(format)) null else received == expected
        }
        when (complete) {
            null -> fail(IllegalStateException("Unexpected RAW capture output"))
            false -> if (split && synchronized(received) { received == setOf(ImageFormat.JPEG) }) port.onMain { takeSplitRaw() }
            true -> {
                if (!ended.compareAndSet(false, true)) return
                port.cancelTimeout()
                endSplit()
                port.save()
            }
        }
    }

    fun failed(error: Exception) = fail(error)
    fun timedOut() = fail(IllegalStateException("RAW capture timed out"))

    fun cancel() {
        if (!ended.compareAndSet(false, true)) return
        port.cancelTimeout()
        endSplit()
        // Late camera callbacks also discard after their file handles close.
        port.discard()
    }

    /** Main thread: the JPEG is saved, so take the RAW from a binding for RAW alone. */
    private fun takeSplitRaw() {
        if (ended.get() || !port.isCurrent()) return
        try {
            port.rememberJpegSettings()
            port.bindRaw()
            port.matchJpegSettings()
            port.takeRaw()
        } catch (error: Exception) { fail(IllegalStateException("Cannot start RAW capture", error)) }
    }

    private fun endSplit() {
        if (!split) return
        port.onMain {
            // Always undo the RAW's manual exposure and focus, even if the lens changed meanwhile.
            port.restoreOptions()
            if (port.leaveRawPhase()) port.rebindJpeg()
        }
    }

    private fun fail(error: Exception) {
        if (!ended.compareAndSet(false, true)) return
        port.cancelTimeout()
        endSplit()
        port.discard()
        port.onMain { if (port.isCurrent()) port.reportError(error) }
    }
}
