package app.grapheneos.camera.capturer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Versioned, bounded mask/depth data for reversible Portrait editing. No executable model content. */
internal data class PortraitData(val width: Int, val height: Int, val maskWidth: Int, val maskHeight: Int,
    val depthWidth: Int, val depthHeight: Int, val x: Float, val y: Float, val blur: Int,
    val mask: FloatArray, val depth: FloatArray) {
    fun encode(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(40 + 4 * (mask.size + depth.size)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(width).putInt(height).putInt(maskWidth).putInt(maskHeight)
            .putInt(depthWidth).putInt(depthHeight).putFloat(x).putFloat(y).putInt(blur)
        mask.forEach(buffer::putFloat); depth.forEach(buffer::putFloat); buffer.flip()
        // Encoding is also a boundary: keep the exact same validation as persisted input.
        decode(buffer.duplicate())
        return buffer
    }
    companion object {
        const val MAX_BYTES = 8_000_040
        private const val MAGIC = 0x50525431
        fun decode(input: ByteBuffer): PortraitData {
            val data = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            require(data.remaining() in 40..MAX_BYTES && data.int == MAGIC)
            val w=data.int; val h=data.int; val mw=data.int; val mh=data.int; val dw=data.int; val dh=data.int
            val x=data.float; val y=data.float; val blur=data.int
            require(w>=2 && h>=2 && w.toLong()*h<=16_000_000)
            require(mw>=2 && mh>=2 && mw.toLong()*mh<=1_000_000)
            require((dw==0 && dh==0) || (dw>=2 && dh>=2 && dw.toLong()*dh<=1_000_000))
            require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && blur in 0..20)
            require(data.remaining().toLong()==4L*(mw.toLong()*mh+dw.toLong()*dh))
            val mask=FloatArray(mw*mh) { data.float.also { require(it.isFinite() && it in 0f..1f) } }
            val depth=FloatArray(dw*dh) { data.float.also { require(it.isFinite() && it in 0f..10_000f) } }
            return PortraitData(w,h,mw,mh,dw,dh,x,y,blur,mask,depth)
        }
    }
}
