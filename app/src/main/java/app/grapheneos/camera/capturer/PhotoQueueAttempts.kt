package app.grapheneos.camera.capturer

/** Automatic processing attempts per captured photo before it waits for the user's Retry. */
internal object PhotoQueueAttempts {
    const val MAX = 3
    fun mayStart(attempts: Int): Boolean = attempts in 0 until MAX
}
