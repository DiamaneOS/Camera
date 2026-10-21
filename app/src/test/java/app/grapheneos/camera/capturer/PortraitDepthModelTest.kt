package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class PortraitDepthModelTest {
    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private val data = ByteArray(200_000) { (it * 31 % 251).toByte() }

    @Test fun exactSizeUnpacksToADirectBuffer() {
        val model = PortraitDepthModel.read(ByteArrayInputStream(gzip(data)), data.size)
        assertTrue(model.isDirect); assertEquals(0, model.position()); assertEquals(data.size, model.remaining())
        assertArrayEquals(data, ByteArray(data.size).also { model.get(it) })
    }
    @Test fun shortLongAndCorruptStreamsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PortraitDepthModel.read(ByteArrayInputStream(gzip(data)), data.size + 1) }
        assertThrows(IllegalArgumentException::class.java) { PortraitDepthModel.read(ByteArrayInputStream(gzip(data)), data.size - 1) }
        val packed = gzip(data)
        assertThrows(java.io.IOException::class.java) { PortraitDepthModel.read(ByteArrayInputStream(packed.copyOf(packed.size / 2)), data.size) }
        assertThrows(java.io.IOException::class.java) { PortraitDepthModel.read(ByteArrayInputStream(data), data.size) }
    }
}
