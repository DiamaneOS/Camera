package app.grapheneos.camera.capturer

/** Reservations cover inputs before acquisition, including jobs waiting for the processor. */
internal class PhotoQueueBudget(private val maximumJobs: Int = 8, private val maximumBytes: Long = 1L shl 30) {
    private val reservations = linkedMapOf<String, Long>()

    @Synchronized fun reserve(id: String, bytes: Long): Boolean {
        require(bytes > 0 && bytes <= maximumBytes && id !in reservations)
        if (reservations.size >= maximumJobs || reservations.values.sum() > maximumBytes - bytes) return false
        reservations[id] = bytes
        return true
    }

    @Synchronized fun commit(id: String, bytes: Long) {
        val reserved = reservations[id] ?: error("Missing photo reservation")
        require(bytes in 1..reserved)
        reservations[id] = bytes
    }

    @Synchronized fun release(id: String) { reservations.remove(id) }
}
