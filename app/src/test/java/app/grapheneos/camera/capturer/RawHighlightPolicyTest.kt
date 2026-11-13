package app.grapheneos.camera.capturer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawHighlightPolicyTest {
    private val calibration=floatArrayOf(64f,65f,66f,67f,4095f,0f,0f,0f,0f,0f,0f,0f,0f)
    private fun image(width: Int=192,height: Int=144,highlight: Boolean=false): ByteBuffer =
        ByteBuffer.allocate(width*height*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(width*height) {index ->putShort(if(highlight && index%width<width/10) 4095 else 400)}
            rewind()
        }

    @Test fun ordinarySceneAndIsolatedBrightPointsKeepExposure() {
        val input=image();input.putShort(10,4095)
        val before=input.array().copyOf()
        assertEquals(1f,RawHighlightPolicy.measure(input,192,144,384,calibration).scale,0f)
        assertArrayEquals(before,input.array());assertEquals(0,input.position())
    }

    @Test fun ClippedHighlightsRequestHeadroomAndSensitivityDropsFirst() {
        val reading=RawHighlightPolicy.measure(image(highlight=true),192,144,384,calibration)
        assertTrue(reading.clippedFraction>.05f);assertEquals(.5f,reading.scale,0f)
        val iso=RawHighlightPolicy.choose(20_000_000,400,1000,100,.5f)
        assertEquals(20_000_000L,iso.exposureNs);assertEquals(200,iso.iso)
        val time=RawHighlightPolicy.choose(20_000_000,100,1000,100,.5f)
        assertEquals(10_000_000L,time.exposureNs);assertEquals(100,time.iso)
    }

    @Test fun StrideSliceAndAllCfaPhasesAreRespected() {
        val width=8;val height=8;val stride=20
        val input=ByteBuffer.allocate(stride*height+16).order(ByteOrder.LITTLE_ENDIAN)
        for(y in 0 until height) for(x in 0 until width) input.putShort(8+y*stride+x*2,
            if(x%2==1 && y%2==0) 4095 else 400)
        input.position(8);input.limit(8+stride*height)
        val reading=RawHighlightPolicy.measure(input,width,height,stride,calibration)
        assertEquals(.25f,reading.clippedFraction,0f);assertEquals(8,input.position())
    }

    @Test fun InvalidOrTruncatedInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {RawHighlightPolicy.measure(ByteBuffer.allocate(20),8,8,16,calibration)}
        assertThrows(IllegalArgumentException::class.java) {RawHighlightPolicy.measure(image(),192,144,383,calibration)}
        val invalid=calibration.copyOf().apply {this[0]=Float.NaN}
        assertThrows(IllegalArgumentException::class.java) {RawHighlightPolicy.measure(image(),192,144,384,invalid)}
    }

    @Test fun CroppedOutHighlightsDoNotDarkenTheSelectedView() {
        val source=image(highlight=true)
        assertEquals(.5f,RawHighlightPolicy.measure(source,192,144,384,calibration).scale,0f)
        assertEquals(1f,RawHighlightPolicy.measure(source,192,144,384,calibration,2f,4f/3).scale,0f)
    }
}
