package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Test

class LensPresetsTest {
    @Test fun aLogicalRearCameraOffersAllThree() =
        assertEquals(listOf(.56f, 1f, 2f), LensPresets.choose(.56f, 10f))

    @Test fun highSpeedAndFrontRangesOfferOnlyMainAndItsCrop() {
        val highSpeed = PhotoZoomMapping.highSpeedRange(PhotoZoomMapping.Range(.56f, 10f))
        assertEquals(listOf(1f, 2f), LensPresets.choose(highSpeed.minimum, highSpeed.maximum))
        assertEquals(listOf(1f, 2f), LensPresets.choose(1f, 10f))
    }

    @Test fun aCameraThatCannotReachTwoTimesKeepsTheWideLens() =
        assertEquals(listOf(.6f, 1f), LensPresets.choose(.6f, 1.8f))

    @Test fun onlyOneTimesLeftHidesTheBar() {
        assertEquals(emptyList<Float>(), LensPresets.choose(1f, 1.9f))
        assertEquals(emptyList<Float>(), LensPresets.choose(1f, 1f))
    }

    @Test fun aRoundingBelowOneIsNotAWideLens() =
        assertEquals(listOf(1f, 2f), LensPresets.choose(.98f, 8f))

    @Test fun invalidRangesOfferNothing() {
        assertEquals(emptyList<Float>(), LensPresets.choose(Float.NaN, 10f))
        assertEquals(emptyList<Float>(), LensPresets.choose(2f, 1f))
        assertEquals(emptyList<Float>(), LensPresets.choose(0f, 10f))
    }
}
