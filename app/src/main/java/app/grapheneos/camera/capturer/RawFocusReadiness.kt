package app.grapheneos.camera.capturer

/** Require consecutive distinct camera frames; a repeated or moving lens result cannot settle focus. */
class RawFocusReadiness {
    private var lastFrame = Long.MIN_VALUE
    private var consecutive = 0
    fun observe(frameNumber: Long, ready: Boolean): Boolean {
        if (frameNumber <= lastFrame) return false
        lastFrame = frameNumber
        consecutive = if (ready) consecutive + 1 else 0
        return consecutive >= 2
    }
}
