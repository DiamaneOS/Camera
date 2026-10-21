package app.grapheneos.camera.capturer

/** One outstanding matte; late replies cannot release a newer request or refresh an old frame. */
class PortraitPreviewPolicy {
    private var serial = 0
    private var pending: Int? = null
    private var pendingGeneration = 0
    private var requestedAt = 0L
    private var maskCapturedAt = -1L
    val busy: Boolean get() = pending != null

    fun begin(now: Long, generation: Int): Int {
        check(!busy)
        serial = if (serial == Int.MAX_VALUE) 1 else serial + 1
        pending = serial
        pendingGeneration = generation
        requestedAt = now
        return serial
    }

    fun reply(sequence: Int, generation: Int, now: Long): Boolean {
        if (pending != sequence) return false
        pending = null
        if (generation != pendingGeneration || now - requestedAt !in 0..800) return false
        maskCapturedAt = requestedAt
        return true
    }

    fun expire(now: Long) {
        if (busy && (now < requestedAt || now - requestedAt > 3000)) pending = null
    }

    fun fresh(now: Long) = maskCapturedAt >= 0 && now - maskCapturedAt in 0..800
    fun invalidateMask() { maskCapturedAt = -1 }
    fun reset() { pending = null; invalidateMask() }
}
