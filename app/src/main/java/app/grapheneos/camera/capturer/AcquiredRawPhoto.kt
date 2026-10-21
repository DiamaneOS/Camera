package app.grapheneos.camera.capturer

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SharedMemory
import org.json.JSONObject

internal data class RawPreviewGuide(
    val luminance: Float,
    val toneTargets: FloatArray?,
    val colors: IntArray?,
    val rotation: Int,
    val thumbnail: Bitmap?,
)

/** Ownership passes from acquisition to the durable queue; processing needs no camera Activity. */
internal class AcquiredRawPhoto(val payload: Bundle, val guide: RawPreviewGuide, val profile: JSONObject) : AutoCloseable {
    @Suppress("DEPRECATION")
    override fun close() {
        payload.getParcelableArrayList<SharedMemory>("frames")?.forEach(SharedMemory::close)
        payload.getParcelable<SharedMemory>("rawShading")?.close()
        guide.thumbnail?.recycle()
    }
}
