package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFrameRateTest {
    private val fps30 = VideoFrameRate(30, 30)
    private val fps60 = VideoFrameRate(60, 60)

    @Test fun eachResolutionGetsOnlyItsOwnSessionCapabilities() {
        val uhd = VideoFrameRate.supportedOptions(listOf(setOf(fps30)))
        val fhd = VideoFrameRate.supportedOptions(listOf(setOf(fps30, fps60)))
        assertEquals(listOf(fps30), uhd)
        assertEquals(listOf(fps30, fps60), fhd)
        assertNull(VideoFrameRate.selectProfile(listOf(uhd.toSet()), fps60))
    }

    @Test fun aVariableRangeDoesNotAdvertiseAnUnsupportedFixedRate() {
        val variable = VideoFrameRate(15, 30)
        assertEquals(listOf(variable), VideoFrameRate.supportedOptions(listOf(setOf(variable))))
        assertNull(VideoFrameRate.selectProfile(listOf(setOf(variable)), fps30))
    }

    @Test fun requestedRateCanUseAProfileWithoutVideoSnapshots() {
        val withSnapshot = setOf(fps30)
        val withoutSnapshot = setOf(fps30, fps60)
        assertEquals(1, VideoFrameRate.selectProfile(listOf(withSnapshot, withoutSnapshot), fps60))
        assertEquals(0, VideoFrameRate.selectProfile(listOf(withSnapshot, withoutSnapshot), fps30))
    }

    @Test fun autoAndMatchingRatesKeepThePreferredStabilizationProfile() {
        val stabilized = setOf(fps30)
        val unstabilized = setOf(fps30, fps60)
        assertEquals(0, VideoFrameRate.selectProfile(listOf(stabilized, unstabilized), null))
        assertEquals(1, VideoFrameRate.selectProfile(listOf(stabilized, unstabilized), fps60))
    }

    @Test fun storedValuesRoundTripWithoutDependingOnTranslatedLabels() {
        val range = VideoFrameRate(24, 30)
        assertEquals(range, VideoFrameRate.fromPreference(range.preferenceValue()))
        for (value in listOf(null, "", "Auto", "60", "0:30", "60:30", "1:2147483648", "30:30:60")) {
            assertNull(VideoFrameRate.fromPreference(value))
        }
    }

    @Test fun supportedOptionsAreDeduplicatedAndDeterministic() {
        assertEquals(listOf(fps30, VideoFrameRate(15, 30), fps60),
            VideoFrameRate.supportedOptions(listOf(setOf(fps60, fps30), setOf(fps30, VideoFrameRate(15, 30)))))
        assertNull(VideoFrameRate.selectProfile(emptyList(), null))
    }
}
