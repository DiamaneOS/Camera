package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PortraitDataTest {
    private fun sample(blur: Int=8)=PortraitData(200,300,2,2,2,2,.2f,.7f,blur,
        floatArrayOf(0f,.25f,.75f,1f),floatArrayOf(.1f,.5f,2f,3f))
    @Test fun savedOriginalGeometryAndPlanesRoundTrip() {
        val a=sample();val b=PortraitData.decode(a.encode())
        assertEquals(a.width,b.width);assertEquals(a.height,b.height);assertEquals(a.x,b.x,0f)
        assertArrayEquals(a.mask,b.mask,0f);assertArrayEquals(a.depth,b.depth,0f)
    }
    @Test fun blurOffRemainsReversible() {
        val restored=PortraitData.decode(sample(0).encode())
        assertEquals(0,restored.blur);assertEquals(4,restored.mask.size);assertEquals(4,restored.depth.size)
    }
    @Test fun missingTruncatedAndExtraDataAreRejected() {
        for (length in listOf(0,39,40,71,73)) {
            val buffer=ByteBuffer.allocate(length)
            val original=sample().encode();while(buffer.hasRemaining()) buffer.put(if(original.hasRemaining()) original.get() else 0)
            buffer.flip();assertThrows(IllegalArgumentException::class.java) {PortraitData.decode(buffer)}
        }
    }
    @Test fun dimensionsCannotOverflowOrRequestUnboundedBuffers() {
        val bytes=sample().encode();bytes.order(ByteOrder.LITTLE_ENDIAN).putInt(12,Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {PortraitData.decode(bytes)}
    }
    @Test fun nonFinitePointsAndPlaneValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {sample().copy(x=Float.NaN).encode()}
        assertThrows(IllegalArgumentException::class.java) {sample().copy(mask=floatArrayOf(0f,1f,Float.NaN,1f)).encode()}
        assertThrows(IllegalArgumentException::class.java) {sample().copy(depth=floatArrayOf(0f,1f,-1f,2f)).encode()}
    }
}
