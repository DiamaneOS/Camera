package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class NightPreviewTonePolicyTest {
    @Test fun nearBlackPreviewDoesNotDictateRawWhiteBalance() {
        assertFalse(NightPreviewTonePolicy.reliableColor(.014f))
        assertFalse(NightPreviewTonePolicy.reliableColor(Float.NaN))
        assertTrue(NightPreviewTonePolicy.reliableColor(.12f))
    }
    @Test fun brightScenesKeepTheirReferenceExposure() {
        assertEquals(1f, NightPreviewTonePolicy.gain(.55f), 0f)
        assertEquals(1f, NightPreviewTonePolicy.gain(1f), 0f)
    }
    @Test fun darkScenesGetABoundedLiftRatherThanAnUnboundedNoiseBoost() {
        for (mean in listOf(0f, .01f, .05f, .12f, .23f)) {
            val gain = NightPreviewTonePolicy.gain(mean)
            assertTrue(gain > 1f && gain <= 12f)
        }
    }
    @Test fun luminanceLiftPreservesBlackWhiteAndHue() {
        val gain=NightPreviewTonePolicy.gain(.05f)
        assertEquals(-0x1000000,NightPreviewTonePolicy.lift(-0x1000000,gain))
        assertEquals(-1,NightPreviewTonePolicy.lift(-1,gain))
        val mid=NightPreviewTonePolicy.lift(0xff0d0d0d.toInt(),gain) and 255
        assertTrue(mid in 58..65)
        val color=NightPreviewTonePolicy.lift(0xff502814.toInt(),gain)
        val r=color shr 16 and 255;val g=color shr 8 and 255;val b=color and 255
        assertTrue(kotlin.math.abs(r-2*g)<=2 && kotlin.math.abs(g-2*b)<=2)
    }
    @Test fun lightTransitionsAreContinuousAndInvalidSamplesDoNotDriveTheCurve() {
        assertEquals(NightPreviewTonePolicy.gain(.23999f), NightPreviewTonePolicy.gain(.24001f), .001f)
        for (mean in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertEquals(1f, NightPreviewTonePolicy.gain(mean), 0f)
        }
    }
}
