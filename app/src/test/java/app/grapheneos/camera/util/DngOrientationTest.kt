package app.grapheneos.camera.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DngOrientationTest {
    private fun image(orientation: Int, little: Boolean): ByteArray {
        val bytes = ByteArray(128) { (it * 17).toByte() }
        ByteBuffer.wrap(bytes).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).apply {
            put(if (little) 0x49.toByte() else 0x4d.toByte()); put(bytes[0])
            putShort(42); putInt(8); putShort(1)
            putShort(274); putShort(3); putInt(1); putShort(orientation.toShort()); putShort(0); putInt(0)
        }
        return bytes
    }

    private fun withFile(bytes: ByteArray, test: (File) -> Unit) {
        val file = File.createTempFile("dng-orientation-", ".dng")
        try { file.writeBytes(bytes); test(file) } finally { file.delete() }
    }

    @Test fun mirroredFrontDngPreservesEveryOtherByteInBothByteOrders() {
        val expected = mapOf(1 to 2, 3 to 4, 6 to 5, 8 to 7)
        for (little in listOf(false, true)) for ((before, after) in expected) {
            val bytes = image(before, little)
            withFile(bytes) { file ->
                DngOrientation.mirrorHorizontally(file)
                assertArrayEquals(image(after, little), file.readBytes())
                // A future CameraX which already applies mirror metadata must be idempotent.
                DngOrientation.mirrorHorizontally(file)
                assertArrayEquals(image(after, little), file.readBytes())
            }
        }
    }

    @Test fun galleryNormalizesOnlyItsDecodeCopyAndReturnsOriginalOrientation() {
        for (little in listOf(false, true)) for (orientation in 1..8) {
            withFile(image(orientation, little)) { file ->
                assertEquals(orientation, DngOrientation.normalizeCopy(file))
                assertArrayEquals(image(1, little), file.readBytes())
            }
        }
    }

    @Test fun malformedOffsetsTypesAndValuesFailWithoutModification() {
        val bad = listOf(
            image(8, true).apply { this[4] = 127 },
            image(8, true).apply { this[8] = 100 },
            image(8, true).apply { this[12] = 4 },
            image(8, true).apply { this[14] = 2 },
            image(0, true), ByteArray(7),
        )
        for (bytes in bad) withFile(bytes) { file ->
            try { DngOrientation.mirrorHorizontally(file); fail("Malformed DNG accepted") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun galleryAcceptsAbsentOrientationButCaptureCannotSilentlyIgnoreRequestedMirror() {
        val bytes = image(1, true).apply { this[10] = 0 }
        withFile(bytes) { file ->
            assertEquals(1, DngOrientation.normalizeCopy(file))
            try { DngOrientation.mirrorHorizontally(file); fail("Missing orientation accepted") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
}
