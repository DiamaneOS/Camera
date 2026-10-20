package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PhotoResolutionTest {
    @Test fun choicesContainOnlyRealSizesAndNeverInventFiftyMegapixels() {
        val sizes=listOf(PhotoResolution(4096,3072),PhotoResolution(4032,3024),PhotoResolution(3264,2448),PhotoResolution(2592,1944),PhotoResolution(1600,1200))
        val choices=PhotoResolutionPolicy.choices(sizes)
        assertEquals(sizes.first(),choices.first())
        assertTrue(choices.all {it in sizes && it.pixels<=12_582_912})
        assertTrue(choices.size<sizes.size)
        assertEquals(emptyList<PhotoResolution>(),PhotoResolutionPolicy.choices(emptyList()))
    }
    @Test fun aspectUsesTheCurrentPhotoShapeWithoutDroppingNearSensorRatios() {
        val wide=PhotoResolution(4096,2304);val full=PhotoResolution(4096,3072);val sensor=PhotoResolution(4208,3120)
        assertEquals(listOf(wide),PhotoResolutionPolicy.advertised(listOf(wide,full,sensor),16.0/9))
        assertEquals(listOf(sensor,full),PhotoResolutionPolicy.advertised(listOf(wide,full,sensor),4.0/3))
    }
    @Test fun smallOrUnusualCameraKeepsItsActualMaximum() {
        val tiny=PhotoResolution(640,480)
        assertEquals(listOf(tiny),PhotoResolutionPolicy.choices(listOf(tiny)))
        assertEquals(listOf(tiny),PhotoResolutionPolicy.advertised(listOf(tiny),16.0/9))
    }
    @Test fun persistedSizeIsBoundedAndParsedStrictly() {
        assertEquals(PhotoResolution(4096,3072),PhotoResolution.parse("4096x3072"))
        for(value in listOf(null,"auto","500000x500000","0x4","-1x4","4096x3072/extra","32768x32768"))assertNull(PhotoResolution.parse(value))
    }
}
