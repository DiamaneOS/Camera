package app.grapheneos.camera.capturer

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** The saved-photo depth model ships gzipped. Float16-valued float32 weights keep LiteRT's float32 speed. */
object PortraitDepthModel {
    const val ASSET = "portrait_depth_v2_small.tflite.gzip"
    const val BYTES = 98_814_896
    const val SHA256 = "72ba875d48c520c3ced0cd10782f81fab5764c24c34ee7620e9d3e075a866de5"

    /** Unpacks exactly [size] bytes into a direct buffer. Short, long or corrupt streams are rejected. */
    fun read(compressed: InputStream, size: Int = BYTES): ByteBuffer {
        val model = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        GZIPInputStream(compressed, 1 shl 16).use { input ->
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(chunk, 0, minOf(chunk.size, model.remaining() + 1))
                if (count < 0) break
                require(count <= model.remaining()) { "depth model too large" }
                model.put(chunk, 0, count)
            }
        }
        require(!model.hasRemaining()) { "depth model truncated" }
        return model.apply { rewind() }
    }
}
