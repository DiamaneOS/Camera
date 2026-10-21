package app.grapheneos.camera.capturer

/**
 * Reservations cover inputs before acquisition, including jobs waiting for the processor.
 * Failed jobs keep their inputs for the user's Retry in a separate, larger pool, so they do not
 * block new photos.
 */
internal class PhotoQueueBudget(
    private val maximumJobs: Int = 8, private val maximumBytes: Long = 1L shl 30,
    private val maximumParkedJobs: Int = 32, private val maximumParkedBytes: Long = 4L shl 30,
) {
    private val reservations = linkedMapOf<String, Long>()
    private val parked = linkedMapOf<String, Long>()

    @Synchronized fun reserve(id: String, bytes: Long): Boolean {
        require(bytes > 0 && bytes <= maximumBytes && id !in reservations && id !in parked)
        if (!fits(reservations, maximumJobs, maximumBytes, bytes)) return false
        reservations[id] = bytes
        return true
    }

    @Synchronized fun commit(id: String, bytes: Long) {
        val reserved = reservations[id] ?: error("Missing photo reservation")
        require(bytes in 1..reserved)
        reservations[id] = bytes
    }

    @Synchronized fun release(id: String) { reservations.remove(id); parked.remove(id) }

    /** A failed job found at launch. */
    @Synchronized fun reserveParked(id: String, bytes: Long): Boolean {
        require(bytes > 0 && bytes <= maximumBytes && id !in reservations && id !in parked)
        if (!fits(parked, maximumParkedJobs, maximumParkedBytes, bytes)) return false
        parked[id] = bytes
        return true
    }

    /** Moves a failed job out of the queue's slots. When the failed pool is full it keeps its slot. */
    @Synchronized fun park(id: String) {
        val bytes = reservations[id] ?: return
        if (!fits(parked, maximumParkedJobs, maximumParkedBytes, bytes)) return
        reservations.remove(id); parked[id] = bytes
    }

    /** Takes a failed job back into the queue for a retry. False while the queue is full. */
    @Synchronized fun unpark(id: String): Boolean {
        if (id in reservations) return true
        val bytes = parked[id] ?: return false
        if (!fits(reservations, maximumJobs, maximumBytes, bytes)) return false
        parked.remove(id); reservations[id] = bytes
        return true
    }

    private fun fits(pool: Map<String, Long>, jobs: Int, limit: Long, bytes: Long) =
        pool.size < jobs && pool.values.sum() <= limit - bytes
}
