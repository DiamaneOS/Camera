package app.grapheneos.camera.capturer

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RAW frames of a direct burst, each paired with its capture result by sensor timestamp, in either
 * arrival order. While every reader slot is held, acquiring fails: each consumed frame asks for
 * another drain, so no frame is left behind. Camera failures reach the consumer in order.
 */
internal class RawFrameQueue<I : Any, R : Any>(
    private val reader: Reader<I>, private val closed: AtomicBoolean, private val cancelled: AtomicBoolean,
    private val clock: () -> Long, capacity: Int = 4,
) {
    interface Reader<I> {
        /** Throws IllegalStateException while all slots are held or the reader is closing. */
        fun acquire(): I?
        fun close(image: I)
        fun timestamp(image: I): Long
        /** Runs [drain] on the reader's thread after a consumed frame was closed. */
        fun requestDrain(drain: () -> Unit)
    }

    private val frames = ArrayBlockingQueue<Any>(capacity)
    private val results = ConcurrentHashMap<Long, R>()

    /** Reader thread: take every queued image. Returns how many were taken. */
    fun drain(): Int {
        var taken = 0
        while (true) {
            val image = try { reader.acquire() } catch (_: IllegalStateException) { return taken } ?: return taken
            taken++
            if (closed.get() || cancelled.get() || !frames.offer(image)) reader.close(image)
        }
    }

    fun result(timestamp: Long, result: R) { results[timestamp] = result }

    /** A failed frame or a lost buffer ends the burst at the next frame the consumer asks for. */
    fun fail(error: Exception) { frames.offer(error) }

    /** Consumer thread: the next frame and its result, or the camera's failure. */
    fun next(deadline: Long, checkActive: () -> Unit, consume: (I, R) -> Unit) {
        var frame: Any? = null
        while (frame == null) {
            checkActive(); check(clock() < deadline) { "RAW burst timed out" }
            frame = frames.poll(100, TimeUnit.MILLISECONDS)
        }
        if (frame is Exception) throw frame
        @Suppress("UNCHECKED_CAST") val image = frame as I
        try {
            var result = results.remove(reader.timestamp(image))
            while (result == null) {
                checkActive(); check(clock() < deadline) { "RAW burst metadata missing" }
                Thread.sleep(5); result = results.remove(reader.timestamp(image))
            }
            consume(image, result)
        } finally {
            reader.close(image)
            reader.requestDrain { if (!closed.get()) drain() }
        }
    }

    /** After the burst closed: closes the frames nobody consumed. */
    fun close() {
        while (true) {
            val frame = frames.poll() ?: break
            @Suppress("UNCHECKED_CAST")
            if (frame !is Exception) try { reader.close(frame as I) } catch (_: Exception) { }
        }
    }
}
