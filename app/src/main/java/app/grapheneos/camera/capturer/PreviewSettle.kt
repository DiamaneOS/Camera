package app.grapheneos.camera.capturer

import android.hardware.camera2.CaptureResult

/**
 * A newly opened camera starts with unsettled exposure and white balance: on the FP6 its first
 * frames are nearly black, then orange, for about half a second. A rebind keeps showing the last
 * frame of the previous camera until the new one reports both as settled, or for at most [MAX_HOLD_MS].
 */
internal object PreviewSettle {
    const val MAX_HOLD_MS = 1000L

    fun settled(aeMode: Int?, aeState: Int?, awbMode: Int?, awbState: Int?): Boolean {
        val exposure = aeMode == CaptureResult.CONTROL_AE_MODE_OFF || aeState == null ||
            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        val whiteBalance = (awbMode != null && awbMode != CaptureResult.CONTROL_AWB_MODE_AUTO) || awbState == null ||
            awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
        return exposure && whiteBalance
    }

    fun settled(result: CaptureResult): Boolean = settled(result.get(CaptureResult.CONTROL_AE_MODE),
        result.get(CaptureResult.CONTROL_AE_STATE), result.get(CaptureResult.CONTROL_AWB_MODE),
        result.get(CaptureResult.CONTROL_AWB_STATE))

    /** Main thread state of one hold. Each rebind starts a new generation. */
    class Hold {
        @Volatile private var generation = 0L
        private var waiting = false
        private var settled = false
        private var streamingSince: Long? = null
        @Volatile var awaitingResult = false
            private set

        /** A rebind that will report capture results. Returns its generation. */
        fun arm(): Long { generation++; waiting = true; settled = false; streamingSince = null; awaitingResult = true; return generation }
        /** A rebind without capture results: never hold its first frame. */
        fun disarm() { generation++; waiting = false; awaitingResult = false }
        fun current() = generation

        /** The new stream shows frames. True while the old frame should stay. */
        fun streaming(now: Long): Boolean {
            if (!waiting) return false
            if (streamingSince == null) streamingSince = now
            return !settled && now - streamingSince!! < MAX_HOLD_MS
        }
        /** True when this settles a waiting hold that already streams: reveal now. */
        fun settle(of: Long): Boolean {
            if (of != generation || !waiting) return false
            settled = true; awaitingResult = false
            return streamingSince != null
        }
        /** True when the hold of this generation ran out while streaming. */
        fun expired(of: Long, now: Long): Boolean {
            val since = streamingSince ?: return false
            if (of != generation || !waiting || settled || now - since < MAX_HOLD_MS) return false
            waiting = false; awaitingResult = false
            return true
        }
    }
}
