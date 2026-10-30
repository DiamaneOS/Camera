package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForcedFrameRateTest {
    private val highSpeed = listOf(VideoFrameRate(120, 120), VideoFrameRate(240, 240))

    @Test fun offeredWhenOnlyHighSpeedProvesTheSensorMode() {
        assertEquals(VideoFrameRate(60, 60), ForcedFrameRate.offered(30, highSpeed))
    }

    @Test fun notOfferedWithoutHighSpeedAtThisQuality() {
        assertNull(ForcedFrameRate.offered(30, emptyList()))
    }

    @Test fun notOfferedWhereTheCameraAdvertisesIt() {
        assertNull(ForcedFrameRate.offered(60, highSpeed))
    }
}
