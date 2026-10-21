package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class NightExposurePolicyTest {
    @Test fun stillBudgetIsIndependentOfSmoothPreviewOnlyWhenSteady() {
        assertEquals(125_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,false,true))
        assertEquals(30_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,false,false))
        assertEquals(50_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,50_000_000,false,true))
        assertEquals(1_000_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,true,false))
        val bright=NightExposurePolicy.choose(5_000_000,100,1_000,66_666_667,100,1550,true)
        assertEquals(5_000_000L,bright.exposureNs)
    }
    @Test fun rejectedSteadyPhotoRetainsShortPhotoSignalAndHighlightProtection() {
        val short = NightExposurePolicy.Setting(66_666_667, 1550)
        val ordinary = NightExposurePolicy.shortPhotoFallback(NightExposurePolicy.Setting(125_000_000, 1417), short, 1000, 100)
        assertEquals(short, ordinary)
        val protected = NightExposurePolicy.Setting(125_000_000, 708)
        val reduced = NightExposurePolicy.shortPhotoFallback(protected, short, 1000, 100)
        assertEquals(short.exposureNs, reduced.exposureNs)
        assertEquals(1327, reduced.iso)
        assertTrue(reduced.exposureNs.toDouble() * reduced.iso <= protected.exposureNs.toDouble() * protected.iso * 1.001)
        val bright = NightExposurePolicy.shortPhotoFallback(NightExposurePolicy.Setting(5_000_000, 100), short, 1000, 100)
        assertEquals(NightExposurePolicy.Setting(5_000_000, 100), bright)
    }
    @Test fun durationAdaptsToLightWithinReservedFrameBudget() {
        assertEquals(2,NightExposurePolicy.frameCount(5_000_000,100,100,8))
        assertEquals(4,NightExposurePolicy.frameCount(20_000_000,400,100,8))
        assertEquals(8,NightExposurePolicy.frameCount(100_000_000,1600,100,8))
        assertEquals(4,NightExposurePolicy.frameCount(100_000_000,1600,100,4))
        val setting=NightExposurePolicy.choose(100_000_000,1600,1000,1_000_000_000,100,1600,false,125_000_000,500_000_000)
        assertEquals(125_000_000,setting.exposureNs);assertEquals(1280,setting.iso)
    }
    @Test fun photoCompensatesDigitalGainClippingWithinExistingShortFrameLimit() {
        val ceiling=NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,false,false,5735,1550)
        assertEquals(66_666_667L,ceiling)
        val setting=NightExposurePolicy.choose(30_000_000,5735,1_000,ceiling,100,1550,false)
        assertEquals(66_666_667L,setting.exposureNs)
        assertEquals(1550,setting.iso)
        assertEquals(40_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,40_000_000,false,false,5735,1550))
        assertEquals(45_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,false,false,2325,1550))
        assertEquals(30_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,false,false,100,1550))
        assertEquals(1_000_000_000L,NightExposurePolicy.exposureCeiling(30_000_000,1_000,1_000_000_000,true,false,5735,1550))
    }
    @Test fun digitalGainIsCombinedOnceEvenWhenPreviewIsoExceedsRawRange() {
        assertEquals(100, NightExposurePolicy.effectiveIso(100, 100, 1550))
        assertEquals(10990, NightExposurePolicy.effectiveIso(1550, 709, 1550))
        assertEquals(10990, NightExposurePolicy.effectiveIso(11000, 709, 1550))
    }

    @Test fun missingSparseOrMovingSamplesKeepShortFrames() {
        assertFalse(NightMotionWindow().isSteady())
        val short = NightMotionWindow()
        repeat(8) { short.record(it * 20_000_000L, .000001) }
        assertFalse(short.isSteady())
        val moving = NightMotionWindow()
        repeat(16) { moving.record(it * 20_000_000L, if (it == 8) .01 else .000001) }
        assertFalse(moving.isSteady())
    }

    @Test fun sustainedSteadinessAllowsLongerFrames() {
        val stable = NightMotionWindow()
        repeat(16) { stable.record(it * 20_000_000L, .000004) }
        assertTrue(stable.isSteady())
        assertTrue(stable.isSteadyAt(320_000_000L))
        assertFalse(stable.isSteadyAt(500_000_000L))
        assertFalse(stable.isSteadyAt(250_000_000L))
        val long = NightExposurePolicy.choose(142_857_143, 1550, 1_000, 1_000_000_000, 100, 1550, stable.isSteady())
        assertEquals(250_000_000L, long.exposureNs)
        assertEquals(886, long.iso)
        val handheld = NightExposurePolicy.choose(142_857_143, 1550, 1_000, 1_000_000_000, 100, 1550, false)
        assertEquals(66_666_667L, handheld.exposureNs)
        assertEquals(1550, handheld.iso)
    }

    @Test fun invalidTimestampsOrValuesDoNotEnableLongFrames() {
        val window = NightMotionWindow()
        repeat(16) { window.record(it * 20_000_000L, 0.0) }
        window.record(300_000_000L, Double.NaN)
        assertFalse(window.isSteady())
    }

    @Test fun minimumIsoAndSensorLimitsPreventBrightSceneOverexposure() {
        val bright = NightExposurePolicy.choose(5_000_000, 100, 1_000, 1_000_000_000, 100, 1550, true)
        assertEquals(5_000_000L, bright.exposureNs)
        assertEquals(100, bright.iso)
        val limited = NightExposurePolicy.choose(100_000_000, 400, 1_000, 80_000_000, 100, 1550, true)
        assertEquals(80_000_000L, limited.exposureNs)
        assertEquals(500, limited.iso)
    }

    @Test fun sixteenMegapixelSensorsTakeFewerFramesThanEight() {
        assertEquals(7, NightExposurePolicy.framesThatFit(4000, 4000))
        assertEquals(7, NightExposurePolicy.framesThatFit(4608, 3456))
        assertEquals(9, NightExposurePolicy.framesThatFit(4208, 3120))
        assertEquals(10, NightExposurePolicy.framesThatFit(4096, 3072))
    }

    @Test fun framesThatFitStayWithinTheInputBound() {
        for ((w, h) in listOf(4000 to 4000, 4608 to 3456, 4208 to 3120, 3280 to 2464)) {
            val n = NightExposurePolicy.framesThatFit(w, h)
            val row = (w * 2L + 63) / 64 * 64
            assert(n * row * h <= NightExposurePolicy.MAX_INPUT_BYTES)
            assert((n + 1) * row * h > NightExposurePolicy.MAX_INPUT_BYTES)
        }
    }
}
