package app.grapheneos.camera.capturer

import java.nio.ByteBuffer
import kotlin.math.abs

/** A conservative veto for optional longer Photo exposures, not an optical-flow estimator.
 * Only bounded luma averages are retained, in memory, while the exclusive camera refocuses.
 * Sensor timestamps are compared with each other; receipt times use a separate monotonic clock.
 */
internal class RawSceneStability {
    enum class Reason { MISSING, STEADY, MOTION, NOISE, EXPOSURE, STALE, NOT_SETTLED, INVALID }
    data class Reading(val reason: Reason, val samples: Int, val noise: Float = 0f,
                       val drift: Float = 0f, val localChange: Float = 0f, val patchNoise: Float = 0f) {
        val steady get() = reason == Reason.STEADY
    }
    private data class Frame(val timestamp: Long, val luma: FloatArray)
    private val frames = ArrayDeque<Frame>(3)
    private var sensorTimestamp = -1L
    private var sensorReceipt = -1L
    private var imageTimestamp = -1L
    private var imageReceipt = -1L
    private var readySince = -1L
    private var invalid = false

    /** Resume after a RAW-only pause with three new settled samples. Timestamp
     * validation and any invalid-input veto survive the window reset. */
    @Synchronized fun restartWindow() {
        frames.clear()
        readySince = -1L
    }

    @Synchronized fun observeState(timestamp: Long, ready: Boolean, received: Long) {
        if (timestamp <= sensorTimestamp || timestamp < 0 || received < sensorReceipt || received < 0) {
            invalid = true
            frames.clear()
            return
        }
        sensorTimestamp = timestamp
        sensorReceipt = received
        if (!ready) {
            readySince = -1L
            frames.clear()
        } else if (readySince < 0) readySince = timestamp
    }

    @Synchronized fun observeImage(buffer: ByteBuffer, width: Int, height: Int,
                                  rowStride: Int, pixelStride: Int, timestamp: Long, received: Long) {
        if (!acceptTimestamp(timestamp, received)) return
        val luma = try { sampleLuma(buffer, width, height, rowStride, pixelStride) }
        catch (_: IllegalArgumentException) { invalid = true; frames.clear(); return }
        append(timestamp, luma)
    }

    /** Also used by generated policy tests; callers cannot mutate retained samples. */
    @Synchronized internal fun observeLuma(luma: FloatArray, timestamp: Long, received: Long) {
        if (!acceptTimestamp(timestamp, received)) return
        if (luma.size != WIDTH * HEIGHT || luma.any { !it.isFinite() || it !in 0f..255f }) {
            invalid = true; frames.clear(); return
        }
        append(timestamp, luma.copyOf())
    }

    private fun acceptTimestamp(timestamp: Long, received: Long): Boolean {
        if (timestamp <= imageTimestamp || timestamp < 0 || received < imageReceipt || received < 0) {
            invalid = true; frames.clear(); return false
        }
        imageTimestamp = timestamp
        imageReceipt = received
        if (invalid || readySince < 0 || timestamp < readySince) return false
        return frames.lastOrNull()?.let { timestamp - it.timestamp >= SAMPLE_SPACING } ?: true
    }

    private fun append(timestamp: Long, luma: FloatArray) {
        if (frames.size == 3) frames.removeFirst()
        frames.addLast(Frame(timestamp, luma))
    }

    @Synchronized fun reading(now: Long): Reading {
        fun result(reason: Reason) = Reading(reason, frames.size)
        if (invalid) return result(Reason.INVALID)
        if (readySince < 0) return result(Reason.NOT_SETTLED)
        if (frames.size < 3) return result(Reason.MISSING)
        val first = frames.first()
        val last = frames.last()
        if (now < imageReceipt || now < sensorReceipt || now - imageReceipt > MAX_AGE ||
            now - sensorReceipt > MAX_AGE || last.timestamp < readySince ||
            abs(sensorTimestamp - last.timestamp) > MAX_AGE ||
            last.timestamp - first.timestamp !in 2 * SAMPLE_SPACING..MAX_SPAN)
            return result(Reason.STALE)
        var noise = 0f; var drift = 0f; var local = 0f; var patchNoise = 0f
        for (frame in frames) {
            val mean = frame.luma.average()
            if (mean !in 8.0..245.0 || frame.luma.count { it > 2f && it < 253f } < WIDTH * HEIGHT * .9)
                return result(Reason.EXPOSURE)
        }
        // Include the first/last pair so that slow movement cannot pass by making
        // each adjacent difference small. Do not normalize away lighting flicker.
        val pairs = listOf(0 to 1, 1 to 2, 0 to 2)
        for ((a, b) in pairs) {
            val difference = FloatArray(WIDTH * HEIGHT) { frames[b].luma[it] - frames[a].luma[it] }
            val sorted = difference.sortedArray()
            val median = sorted[sorted.size / 2]
            val absolute = FloatArray(difference.size) { abs(difference[it] - median) }.apply { sort() }
            val sigma = absolute[absolute.size / 2] * 1.4826f
            noise = maxOf(noise, sigma)
            drift = maxOf(drift, abs(median), abs(difference.average().toFloat()))
            if (sigma > 3f) return Reading(Reason.NOISE, 3, noise, drift, local)
            if (drift > 1f) return Reading(Reason.EXPOSURE, 3, noise, drift, local)
            // Small coherent changes veto even if the moving subject occupies
            // very little of the frame. A high noise floor never expands the
            // threshold indefinitely: the uncertainty gate above rejects it.
            val patches = FloatArray((WIDTH - 2) * (HEIGHT - 2))
            for (y in 1 until HEIGHT - 1) for (x in 1 until WIDTH - 1) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) sum += difference[(y + dy) * WIDTH + x + dx]
                patches[(y - 1) * (WIDTH - 2) + x - 1] = sum / 9
            }
            // Demosaicing and preview processing correlate neighbouring noise.
            // Estimate its scale in the actual patch domain rather than assuming
            // nine independent samples (which can mistake static grain for motion).
            val patchMedian = patches.sortedArray().let { it[it.size / 2] }
            val patchDeviation = FloatArray(patches.size) { abs(patches[it] - patchMedian) }.apply { sort() }
            patchNoise = maxOf(patchNoise, patchDeviation[patchDeviation.size / 2] * 1.4826f)
            if (patchNoise > 1f) return Reading(Reason.NOISE, 3, noise, drift, local, patchNoise)
            val threshold = maxOf(1.5f, 6 * patchNoise)
            local = maxOf(local, patches.maxOf { abs(it) })
            if (local > threshold) return Reading(Reason.MOTION, 3, noise, drift, local, patchNoise)
        }
        return Reading(Reason.STEADY, 3, noise, drift, local, patchNoise)
    }

    /** Explicit debug diagnostics only; release call sites are removed with BuildConfig.DEBUG. */
    @Synchronized internal fun snapshotForTest(now: Long): Pair<Reading, Array<FloatArray>> =
        reading(now) to frames.map { it.luma.copyOf() }.toTypedArray()

    companion object {
        const val WIDTH = 96
        const val HEIGHT = 72
        private const val SAMPLE_SPACING = 80_000_000L
        private const val MAX_AGE = 150_000_000L
        private const val MAX_SPAN = 400_000_000L

        /** Sixteen evenly spaced samples per cell; O(96*72*16), no image-sized copy.
         * Android's Y plane has pixel stride one, but accept bounded strided fixtures.
         * The source position/limit and all input bytes remain unchanged.
         */
        internal fun sampleLuma(buffer: ByteBuffer, width: Int, height: Int,
                                rowStride: Int, pixelStride: Int): FloatArray {
            require(width in WIDTH..4096 && height in HEIGHT..4096 && width.toLong() * height <= 4_000_000)
            require(pixelStride in 1..4 && rowStride.toLong() >= (width - 1L) * pixelStride + 1 &&
                rowStride.toLong() <= width.toLong() * pixelStride + 4096)
            require(buffer.remaining().toLong() >= (height - 1L) * rowStride + (width - 1L) * pixelStride + 1)
            val source = buffer.slice()
            return FloatArray(WIDTH * HEIGHT) { index ->
                val left = (index % WIDTH) * width / WIDTH
                val right = (index % WIDTH + 1) * width / WIDTH
                val top = (index / WIDTH) * height / HEIGHT
                val bottom = (index / WIDTH + 1) * height / HEIGHT
                var sum = 0
                for (dy in 0..3) for (dx in 0..3) {
                    val x = left + (2 * dx + 1) * (right - left) / 8
                    val y = top + (2 * dy + 1) * (bottom - top) / 8
                    sum += source.get(y * rowStride + x * pixelStride).toInt() and 255
                }
                sum / 16f
            }
        }
    }
}
