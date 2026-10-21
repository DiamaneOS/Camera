package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class JpegExposurePolicyTest {
    private fun choose(exposure:Long=30_000_000,iso:Int=5830,boost:Int=376,maxExposure:Long=340_000_000,maxBoost:Int=6400)=
        JpegExposurePolicy.choose(exposure,iso,boost,1_000,maxExposure,100,1550,100,maxBoost)
    @Test fun exchangesDigitalGainForBoundedSingleFrameLight() {
        val setting=choose()!!
        assertEquals(66_666_667L,setting.exposureNs);assertEquals(1550,setting.iso);assertEquals(169,setting.boost)
    }
    @Test fun ordinaryOrAlreadyLongExposuresKeepAutomaticCapture() {
        assertNull(choose(iso=800,boost=100));assertNull(choose(exposure=66_666_667))
    }
    @Test fun sensorLimitAndUnavailableBoostFailConservatively() {
        assertEquals(50_000_000L,choose(maxExposure=50_000_000)!!.exposureNs)
        assertNull(choose(maxExposure=33_000_000));assertNull(choose(maxBoost=100))
    }
    @Test fun gainIncludedIsoIsNotMultipliedTwice() {
        assertEquals(choose(iso=1550),choose(iso=5830))
        assertEquals(choose(iso=1550),choose(iso=11000))
    }
    @Test fun achievedBrightnessRemainsWithinTenPercent() {
        for(boost in listOf(130,200,376,709)) {
            val s=choose(boost=boost) ?: continue
            val wanted=30_000_000.0*NightExposurePolicy.effectiveIso(5830,boost,1550)
            assertTrue(s.exposureNs.toDouble()*s.iso*s.boost/100/wanted in .9..1.1)
        }
    }
    @Test fun longSensorMinimumNeverExpandsThePhotoCeiling() {
        assertNull(JpegExposurePolicy.choose(30_000_000,5830,376,100_000_000,340_000_000,100,1550,100,6400))
    }
    @Test fun nightUsesOnlyTheHandheldBoundAndPreservesMeteredSignal() {
        val s=JpegExposurePolicy.chooseNight(30_000_000,5830,376,1000,340_000_000,100,1550,100,6400,125_000_000)!!
        assertEquals(125_000_000L,s.exposureNs)
        assertEquals(1399,s.iso)
        assertEquals(100,s.boost)
        val wanted=30_000_000.0*NightExposurePolicy.effectiveIso(5830,376,1550)
        assertEquals(1.0,s.exposureNs.toDouble()*s.iso*s.boost/100/wanted,.001)
    }
    @Test fun nightCanReduceAnalogGainWithoutClippingBrightScenes() {
        val s=JpegExposurePolicy.chooseNight(30_000_000,800,100,1000,500_000_000,100,1600,100,6400,125_000_000)!!
        assertEquals(125_000_000L,s.exposureNs);assertEquals(192,s.iso)
        val bright=JpegExposurePolicy.chooseNight(10_000_000,100,100,1000,500_000_000,100,1600,100,6400,125_000_000)
        assertNull(bright)
    }
    @Test fun nightRejectsUnnecessaryTransitionsAndImpossibleSensorRanges() {
        assertNull(JpegExposurePolicy.chooseNight(125_000_000,800,100,1000,500_000_000,100,1600,100,6400,125_000_000))
        assertNull(JpegExposurePolicy.chooseNight(30_000_000,800,100,150_000_000,500_000_000,100,1600,100,6400,125_000_000))
        assertNull(JpegExposurePolicy.chooseNight(30_000_000,5830,376,1000,50_000_000,100,1550,100,100,125_000_000))
    }
    @Test fun nightChoicesRespectEveryAdvertisedBoundAndMeteredBudget() {
        for (oldExposure in listOf(1_000_000L,30_000_000L,100_000_000L))
            for (iso in listOf(100,800,1550,5830)) for (boost in listOf(100,200,376,6400))
                for (limit in listOf(40_000_000L,66_666_667L,125_000_000L)) {
                    val s=JpegExposurePolicy.chooseNight(oldExposure,iso,boost,1000,110_000_000,100,1550,100,6400,limit) ?: continue
                    assertTrue(s.exposureNs in 1000..minOf(limit,110_000_000))
                    assertTrue(s.iso in 100..1550);assertTrue(s.boost in 100..6400)
                    val wanted=oldExposure.toDouble()*NightExposurePolicy.effectiveIso(iso,boost,1550)
                    assertTrue(s.exposureNs.toDouble()*s.iso*s.boost/100/wanted in .9..1.1)
                }
    }
    @Test fun nightHeadroomHoldsBackOnlyAnalogGainAtTheSameExposure() {
        val full=JpegExposurePolicy.chooseNight(60_000_000,4748,306,1000,340_000_000,100,1550,100,6400,100_000_000)!!
        val held=JpegExposurePolicy.chooseNight(60_000_000,4748,306,1000,340_000_000,100,1550,100,6400,100_000_000,2.0)!!
        assertEquals(full.exposureNs,held.exposureNs);assertEquals(full.boost,held.boost)
        assertEquals(775,held.iso);assertEquals(1f,full.restoreGain,0f)
        assertEquals(full.iso.toDouble(),held.iso*held.restoreGain.toDouble(),.5)
        // Near the sensor's lowest analog gain only the remaining range is held back.
        val low=JpegExposurePolicy.chooseNight(30_000_000,800,100,1000,500_000_000,100,1600,100,6400,125_000_000,2.0)!!
        assertEquals(100,low.iso);assertEquals(1.92f,low.restoreGain,.01f)
        val floor=JpegExposurePolicy.chooseNight(30_000_000,500,100,1000,500_000_000,100,1600,100,6400,125_000_000,2.0)!!
        assertEquals(100,floor.iso);assertEquals(1.2f,floor.restoreGain,.01f)
    }
    @Test fun nightHeadroomOnlyForClippedViewfinderLights() {
        assertEquals(1.0,JpegExposurePolicy.nightHeadroom(0f),0.0)
        assertEquals(1.0,JpegExposurePolicy.nightHeadroom(.00005f),0.0)
        assertEquals(2.0,JpegExposurePolicy.nightHeadroom(.0001f),0.0)
        assertEquals(2.0,JpegExposurePolicy.nightHeadroom(.02f),0.0)
        assertEquals(1.0,JpegExposurePolicy.nightHeadroom(Float.NaN),0.0)
    }
    @Test fun saturatedLightsCountByTheirBrightestChannel() {
        // Sodium orange (255,170,60) and a red LED (255,40,40) clip in one channel only.
        val histogram=LongArray(256).also { it[100]=9_990; it[255]=10 }
        assertEquals(.001f,JpegExposurePolicy.nearWhiteFraction(histogram,250),1e-6f)
        assertEquals(2.0,JpegExposurePolicy.nightHeadroom(JpegExposurePolicy.nearWhiteFraction(histogram,250)),0.0)
    }
    @Test fun nearWhiteFractionCountsFromTheLensLevel() {
        val histogram=LongArray(256).also { it[100]=9_000; it[242]=600; it[252]=400 }
        assertEquals(.04f,JpegExposurePolicy.nearWhiteFraction(histogram,250),1e-6f)
        assertEquals(.1f,JpegExposurePolicy.nearWhiteFraction(histogram,240),1e-6f)
        assertEquals(0f,JpegExposurePolicy.nearWhiteFraction(LongArray(256),240),0f)
    }
    @Test(expected=IllegalArgumentException::class) fun nearWhiteLevelIsBounded() {
        JpegExposurePolicy.nearWhiteFraction(LongArray(256),150)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidHeadroomIsRejected() {
        JpegExposurePolicy.chooseNight(60_000_000,4748,306,1000,340_000_000,100,1550,100,6400,100_000_000,.5)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidMeteringIsRejected() {choose(exposure=0)}
}
