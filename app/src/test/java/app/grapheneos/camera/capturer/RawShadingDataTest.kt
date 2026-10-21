package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteOrder

class RawShadingDataTest {
    private fun map(gains: FloatArray = floatArrayOf(1f, 2f, 3f, 4f)) =
        RawShadingData.Map(1, 1, 0, 0, 16, 12, gains)

    @Test fun protocolStoresExplicitGeometryAndCfaSiteOrder() {
        val expected = listOf(listOf(1f, 2f, 3f, 4f), listOf(2f, 1f, 4f, 3f),
            listOf(2f, 4f, 1f, 3f), listOf(4f, 2f, 3f, 1f))
        for (cfa in 0..3) {
            val buffer = RawShadingData.encode(arrayOf(map(), map()), 16, 12, cfa)!!.order(ByteOrder.LITTLE_ENDIAN)
            assertArrayEquals(byteArrayOf(76, 83, 67, 49), ByteArray(4).also { buffer.get(it) })
            assertEquals(1, buffer.int); assertEquals(2, buffer.int)
            assertEquals(16, buffer.int); assertEquals(12, buffer.int); assertEquals(cfa, buffer.int)
            repeat(2) {
                assertEquals(1, buffer.int); assertEquals(1, buffer.int)
                assertEquals(0, buffer.int); assertEquals(0, buffer.int)
                assertEquals(16, buffer.int); assertEquals(12, buffer.int)
                assertEquals(expected[cfa], List(4) { buffer.float })
            }
            assertFalse(buffer.hasRemaining())
        }
    }

    @Test fun missingMapDisablesTheWholeBurstRatherThanMixingCalibration() {
        assertNull(RawShadingData.encode(arrayOf(map(), null), 16, 12, 0))
    }

    @Test fun sourceArraysCannotChangeTheCaptureAfterValidation() {
        val gains = floatArrayOf(1f, 2f, 3f, 4f)
        val captured = map(gains); gains.fill(Float.NaN)
        val encoded = RawShadingData.encode(arrayOf(captured, captured), 16, 12, 0)!!
        encoded.position(48)
        assertEquals(1f, encoded.order(ByteOrder.LITTLE_ENDIAN).float, 0f)
    }

    @Test fun maximumDocumentedMapAndBurstHaveABoundedPayload() {
        val large = RawShadingData.Map(64, 64, 0, 0, 16, 12, FloatArray(64 * 64 * 4) { 1f })
        val encoded = RawShadingData.encode(Array(8) { large }, 16, 12, 0)!!
        assertEquals(524504, encoded.remaining())
        assertEquals(RawShadingData.MAX_BYTES, encoded.remaining())
    }

    @Test fun malformedCalibrationAndGeometryAreRejected() {
        for (gain in listOf(Float.NaN, Float.POSITIVE_INFINITY, .99f, 32.1f)) {
            assertThrows(IllegalArgumentException::class.java) { map(floatArrayOf(gain, 1f, 1f, 1f)) }
        }
        assertThrows(IllegalArgumentException::class.java) { RawShadingData.Map(65, 1, 0, 0, 16, 12, FloatArray(260) { 1f }) }
        assertThrows(IllegalArgumentException::class.java) { RawShadingData.encode(arrayOf(map(), map()), 16, 12, 4) }
        val outside = RawShadingData.Map(1, 1, -1, 0, 16, 12, FloatArray(4) { 1f })
        assertThrows(IllegalArgumentException::class.java) { RawShadingData.encode(arrayOf(outside, outside), 16, 12, 0) }
    }
}
