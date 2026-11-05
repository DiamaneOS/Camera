package app.grapheneos.camera.capturer

import android.media.MediaCodecInfo.CodecProfileLevel
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

    @Test fun aVideoModeChoiceSetsOrClearsTheForcedRate() {
        assertEquals(ForcedFrameRate.Stored.SET, ForcedFrameRate.storedAfterChoice(videoMode = true, forced = true))
        assertEquals(ForcedFrameRate.Stored.CLEAR, ForcedFrameRate.storedAfterChoice(videoMode = true, forced = false))
    }

    @Test fun aSlowMotionChoiceKeepsTheVideoModeForcedRate() {
        // The forced rate belongs to video mode. Picking 240 fps in slow motion says nothing about it.
        assertEquals(ForcedFrameRate.Stored.KEEP, ForcedFrameRate.storedAfterChoice(videoMode = false, forced = false))
    }

    @Test fun fullHdAt60NeedsLevel42() {
        // 8160 macroblocks x 60 = 489600 per second: above 4.1's 245760, within 4.2's 522240.
        assertEquals(CodecProfileLevel.AVCLevel42,
            ForcedFrameRate.avcLevel(1920, 1080, 20_000_000, CodecProfileLevel.AVCProfileHigh))
        assertEquals(CodecProfileLevel.AVCLevel42,
            ForcedFrameRate.avcLevel(1920, 1080, 25_000_000, CodecProfileLevel.AVCProfileHigh))
    }

    @Test fun hdAt60StaysAtLevel32() {
        // 3600 macroblocks x 60 = 216000 per second, exactly level 3.2.
        assertEquals(CodecProfileLevel.AVCLevel32,
            ForcedFrameRate.avcLevel(1280, 720, 8_000_000, CodecProfileLevel.AVCProfileHigh))
    }

    @Test fun aBitrateAboveTheLevelRaisesIt() {
        // 4.2 allows 50 Mb/s, or 62.5 Mb/s for High. 55 Mb/s needs High, and Main goes to level 5.
        assertEquals(CodecProfileLevel.AVCLevel42,
            ForcedFrameRate.avcLevel(1920, 1080, 55_000_000, CodecProfileLevel.AVCProfileHigh))
        assertEquals(CodecProfileLevel.AVCLevel5,
            ForcedFrameRate.avcLevel(1920, 1080, 55_000_000, CodecProfileLevel.AVCProfileMain))
    }

    @Test fun ultraHdAt60NeedsLevel52() {
        // 32400 macroblocks x 60 = 1944000 per second, above level 5.1's 983040.
        assertEquals(CodecProfileLevel.AVCLevel52,
            ForcedFrameRate.avcLevel(3840, 2160, 40_000_000, CodecProfileLevel.AVCProfileHigh))
    }

    @Test fun anUnspecifiedProfileGetsTheStricterBitRate() {
        // CameraX gives no profile on the FP6, and the encoder then picks High. The level has to hold for any pick.
        assertEquals(CodecProfileLevel.AVCLevel42, ForcedFrameRate.avcLevel(1920, 1080, 20_000_000, ForcedFrameRate.PROFILE_UNSPECIFIED))
        assertEquals(CodecProfileLevel.AVCLevel32, ForcedFrameRate.avcLevel(1280, 720, 14_000_000, ForcedFrameRate.PROFILE_UNSPECIFIED))
        assertEquals(CodecProfileLevel.AVCLevel5, ForcedFrameRate.avcLevel(1920, 1080, 55_000_000, ForcedFrameRate.PROFILE_UNSPECIFIED))
    }

    @Test fun noLevelForAnOtherProfileOrAnEmptyStream() {
        assertNull(ForcedFrameRate.avcLevel(1920, 1080, 20_000_000, CodecProfileLevel.AVCProfileHigh10))
        assertNull(ForcedFrameRate.avcLevel(1920, 1080, 20_000_000, 0))
        assertNull(ForcedFrameRate.avcLevel(0, 1080, 20_000_000, CodecProfileLevel.AVCProfileHigh))
    }

    @Test fun noLevelAboveFiveTwo() {
        // 8K at 60 fps is beyond level 5.2.
        assertNull(ForcedFrameRate.avcLevel(7680, 4320, 100_000_000, CodecProfileLevel.AVCProfileHigh))
    }
}
