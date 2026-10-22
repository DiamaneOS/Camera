package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class SlowMotionQualityPolicyTest {
    @Test fun encoderIneligibleAdvertisementsAreExcludedFromMenuAndResolution() {
        val eligible=SlowMotionQualityPolicy.eligible(listOf("UHD","FHD","HD")) { it != "UHD" }
        assertEquals(listOf("FHD","HD"),eligible)
        assertEquals("FHD",SlowMotionQualityPolicy.resolve("UHD",eligible))
    }
    @Test fun storedRearQualityCannotForceAnUnsupportedFrontProfile() {
        assertEquals("HD",SlowMotionQualityPolicy.resolve("FHD",listOf("HD")))
        assertEquals("HD",SlowMotionQualityPolicy.resolve("HD",listOf("FHD","HD")))
        assertEquals("FHD",SlowMotionQualityPolicy.resolve(null,listOf("FHD","HD")))
    }
    @Test fun noEligibleProfileNeverResolvesToOrdinaryVideo() {
        val none=SlowMotionQualityPolicy.eligible(listOf("FHD")) { false }
        assertNull(SlowMotionQualityPolicy.resolve("FHD",none))
        assertNull(SlowMotionQualityPolicy.resolve(null,none))
    }
}
