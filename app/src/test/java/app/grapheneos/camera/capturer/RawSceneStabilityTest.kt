package app.grapheneos.camera.capturer

import java.nio.ByteBuffer
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class RawSceneStabilityTest {
    private val span = 100_000_000L
    private val start = 10_000_000_000L
    private fun image(value: Float = 80f) = FloatArray(RawSceneStability.WIDTH * RawSceneStability.HEIGHT) { value }
    private fun feed(window: RawSceneStability, index: Int, luma: FloatArray = image()) {
        val timestamp = start + index * span
        window.observeState(timestamp, true, index * span)
        window.observeLuma(luma, timestamp, index * span)
    }
    @Test fun threeFreshSettledSamplesAreRequiredAndCameraClockMayDiffer() {
        val window = RawSceneStability()
        assertFalse(window.reading(0).steady)
        feed(window, 0); feed(window, 1)
        assertFalse(window.reading(span).steady)
        feed(window, 2)
        assertTrue(window.reading(2 * span).steady)
        assertEquals(RawSceneStability.Reason.STALE, window.reading(4 * span).reason)
    }
    @Test fun autofocusOrExposureSearchClearsOldSamples() {
        val window = RawSceneStability()
        repeat(3) { feed(window, it) }
        window.observeState(start + 3 * span, false, 3 * span)
        assertFalse(window.reading(3 * span).steady)
        repeat(2) { feed(window, it + 4) }
        assertFalse(window.reading(5 * span).steady)
        feed(window, 6)
        assertTrue(window.reading(6 * span).steady)
    }
    @Test fun pausedRawProbeRequiresFreshSceneSamplesAndCannotHideNewMotion() {
        val window = RawSceneStability()
        repeat(3) { feed(window, it) }
        assertTrue(window.reading(2 * span).steady)
        assertFalse(window.reading(9 * span).steady)
        window.restartWindow()
        feed(window, 10); feed(window, 11)
        assertFalse(window.reading(11 * span).steady)
        val moved = image()
        for (y in 30..32) for (x in 45..47) moved[y * RawSceneStability.WIDTH + x] += 20f
        feed(window, 12, moved)
        assertEquals(RawSceneStability.Reason.MOTION, window.reading(12 * span).reason)
        repeat(3) { feed(window, 13 + it) }
        assertTrue(window.reading(15 * span).steady)
        window.observeLuma(image(), start, 16 * span)
        window.restartWindow()
        repeat(3) { feed(window, 17 + it) }
        assertEquals(RawSceneStability.Reason.INVALID, window.reading(19 * span).reason)
    }
    @Test fun coherentSmallSubjectsAndSlowMotionVetoLongExposure() {
        for (step in listOf(1f, 6f)) {
            val window = RawSceneStability()
            repeat(3) { frame ->
                val luma = image()
                for (y in 30..32) for (x in 45..47) luma[y * RawSceneStability.WIDTH + x] += frame * step
                feed(window, frame, luma)
            }
            assertEquals(RawSceneStability.Reason.MOTION, window.reading(2 * span).reason)
        }
    }
    @Test fun lightingFlickerAndAlmostBlackOrClippedImagesAreUncertain() {
        val flicker = RawSceneStability()
        repeat(3) { feed(flicker, it, image(80f + it * 2)) }
        assertEquals(RawSceneStability.Reason.EXPOSURE, flicker.reading(2 * span).reason)
        for (value in listOf(1f, 254f)) {
            val window = RawSceneStability()
            repeat(3) { feed(window, it, image(value)) }
            assertEquals(RawSceneStability.Reason.EXPOSURE, window.reading(2 * span).reason)
        }
    }
    @Test fun boundedIndependentNoiseMayPassButUncertainHighNoiseNeverDoes() {
        for (noise in listOf(.4f, 4f)) {
            val random = Random(520)
            val window = RawSceneStability()
            repeat(3) { feed(window, it, FloatArray(image().size) { 80f + noise * random.nextGaussian().toFloat() }) }
            val result = window.reading(2 * span)
            if (noise < 1) assertTrue(result.toString(), result.steady)
            else assertEquals(RawSceneStability.Reason.NOISE, result.reason)
        }
    }
    @Test fun irregularRepeatedAndMalformedSamplesFailClosed() {
        val stale = RawSceneStability()
        feed(stale, 0); feed(stale, 1); feed(stale, 6)
        assertEquals(RawSceneStability.Reason.STALE, stale.reading(6 * span).reason)
        for (corrupt in listOf(-1, 0, 1)) {
            val window = RawSceneStability()
            repeat(3) { feed(window, it) }
            when (corrupt) {
                -1 -> window.observeLuma(image(), start + span, 3 * span)
                0 -> window.observeState(start + 2 * span, true, 3 * span)
                1 -> window.observeLuma(image().apply { this[5] = Float.NaN }, start + 3 * span, 3 * span)
            }
            assertEquals(RawSceneStability.Reason.INVALID, window.reading(3 * span).reason)
        }
    }
    @Test fun correlatedPreviewNoiseUsesItsMeasuredPatchScale() {
        val random = Random(204)
        val window = RawSceneStability()
        val w = RawSceneStability.WIDTH; val h = RawSceneStability.HEIGHT
        repeat(3) { frame ->
            val white = FloatArray(w * h) { random.nextGaussian().toFloat() * 2.4f }
            val correlated = image()
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1)
                    sum += white[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                correlated[y * w + x] += sum / 9
            }
            feed(window, frame, correlated)
        }
        val reading = window.reading(2 * span)
        assertTrue(reading.toString(), reading.steady)
        assertTrue(reading.patchNoise > reading.noise / 3)
    }
    @Test fun timestampsAheadOfLatestSensorOrFutureReceiptAreNotFresh() {
        val window = RawSceneStability()
        repeat(3) { feed(window, it) }
        assertFalse(window.reading(span).steady)
        window.observeLuma(image(), start + 5 * span, 3 * span)
        assertEquals(RawSceneStability.Reason.STALE, window.reading(3 * span).reason)
    }
    @Test fun samplerHonorsStrideSliceAndImmutableInputs() {
        val w = 192; val h = 144; val stride = w * 2 + 7; val offset = 13
        val bytes = ByteArray(offset + h * stride + 20) { 0x55 }
        for (y in 0 until h) for (x in 0 until w) bytes[offset + y * stride + x * 2] = (x / 2).toByte()
        val original = bytes.copyOf()
        val buffer = ByteBuffer.wrap(bytes).apply { position(offset); limit(offset + h * stride) }.asReadOnlyBuffer()
        val sampled = RawSceneStability.sampleLuma(buffer, w, h, stride, 2)
        for (y in 0 until RawSceneStability.HEIGHT) for (x in 0 until RawSceneStability.WIDTH)
            assertEquals(x.toFloat(), sampled[y * RawSceneStability.WIDTH + x], 0f)
        assertEquals(offset, buffer.position()); assertArrayEquals(original, bytes)
        for (bad in listOf(ByteBuffer.allocate(5), buffer.duplicate().apply { limit(offset + 100) })) {
            try { RawSceneStability.sampleLuma(bad, w, h, stride, 2); fail("Truncated image accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun retainedSamplesCannotBeMutatedByCaller() {
        val window = RawSceneStability()
        val luma = image()
        repeat(3) { feed(window, it, luma); luma.fill(80f) }
        luma.fill(0f)
        assertTrue(window.reading(2 * span).steady)
    }
    @Test fun onlyACompleteFreshWindowCanRecoverAfterMovement() {
        val window = RawSceneStability()
        repeat(3) { frame ->
            val luma = image()
            if (frame == 1) for (y in 30..32) for (x in 45..47) luma[y * RawSceneStability.WIDTH + x] += 20f
            feed(window, frame, luma)
        }
        assertFalse(window.reading(2 * span).steady)
        feed(window, 3)
        assertFalse(window.reading(3 * span).steady)
        feed(window, 4)
        assertTrue(window.reading(4 * span).steady)
        val snapshot = window.snapshotForTest(4 * span)
        snapshot.second.forEach { it.fill(0f) }
        assertTrue(window.reading(4 * span).steady)
    }
}
