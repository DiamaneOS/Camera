package app.grapheneos.camera.capturer

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The split JPEG + RAW order over a fake camera. Main thread work runs when [FakeCamera.drain] is called. */
class RawCaptureSequenceTest {
    private class FakeCamera : RawCaptureSequence.Port {
        val log = arrayListOf<String>()
        val main = ArrayDeque<() -> Unit>()
        var current = true
        var rawPhase = false
        var boundRaw = false
        var replacedOptions = false
        var bindFails = false
        val errors = arrayListOf<Exception>()

        fun drain() { while (main.isNotEmpty()) main.removeFirst()() }

        override fun scheduleTimeout() { log += "timeout scheduled" }
        override fun cancelTimeout() { log += "timeout cancelled" }
        override fun takeFirst() { log += "take first" }
        override fun onMain(action: () -> Unit) { main.addLast(action) }
        override fun isCurrent() = current
        override fun rememberJpegSettings() { log += "remember JPEG settings" }
        override fun bindRaw() {
            rawPhase = true
            if (bindFails) throw IllegalStateException("bind refused")
            boundRaw = true; log += "bind RAW"
        }
        override fun matchJpegSettings() { replacedOptions = true; log += "match JPEG settings" }
        override fun takeRaw() { log += "take RAW" }
        override fun restoreOptions() {
            if (!replacedOptions) return
            replacedOptions = false; log += "restore options"
        }
        override fun leaveRawPhase(): Boolean = rawPhase.also { rawPhase = false }
        override fun rebindJpeg() {
            if (boundRaw) { boundRaw = false; log += "bind JPEG" }
        }
        override fun save() { log += "save" }
        override fun discard() { log += "discard" }
        override fun reportError(error: Exception) { errors += error; log += "error" }
    }

    private fun split(camera: FakeCamera) = RawCaptureSequence(camera, jpegAndRaw = true, split = true)

    @Test fun theJpegComesFirstThenTheRawWithItsSettingsThenTheCameraIsGivenBack() {
        val camera = FakeCamera(); val sequence = split(camera)
        sequence.start()
        sequence.saved(ImageFormat.JPEG); camera.drain()
        sequence.saved(ImageFormat.RAW_SENSOR); camera.drain()
        assertEquals(listOf("timeout scheduled", "take first", "remember JPEG settings", "bind RAW",
            "match JPEG settings", "take RAW", "timeout cancelled", "save", "restore options", "bind JPEG"), camera.log)
        assertFalse(camera.rawPhase || camera.boundRaw || camera.replacedOptions)
    }

    @Test fun aPairedCaptureSavesOnceBothArrive() {
        val camera = FakeCamera(); val sequence = RawCaptureSequence(camera, jpegAndRaw = true, split = false)
        sequence.start()
        sequence.saved(ImageFormat.RAW_SENSOR); camera.drain()
        sequence.saved(ImageFormat.JPEG); camera.drain()
        assertEquals(listOf("timeout scheduled", "take first", "timeout cancelled", "save"), camera.log)
    }

    @Test fun aRepeatedOrForeignOutputFailsTheCapture() {
        val camera = FakeCamera(); val sequence = split(camera)
        sequence.start()
        sequence.saved(ImageFormat.JPEG); sequence.saved(ImageFormat.JPEG); camera.drain()
        assertEquals("Unexpected RAW capture output", camera.errors.single().message)
        assertFalse("bind RAW" in camera.log)
        val other = FakeCamera(); val rawOnly = RawCaptureSequence(other, jpegAndRaw = false, split = false)
        rawOnly.start(); rawOnly.saved(ImageFormat.JPEG); other.drain()
        assertEquals(1, other.errors.size)
    }

    @Test fun cancelInTheJpegPhaseNeverRebinds() {
        val camera = FakeCamera(); val sequence = split(camera)
        sequence.start()
        sequence.cancel(); camera.drain()
        sequence.saved(ImageFormat.JPEG); camera.drain()
        assertEquals(listOf("timeout scheduled", "take first", "timeout cancelled", "discard", "discard"), camera.log)
        assertTrue(camera.errors.isEmpty())
    }

    @Test fun cancelBetweenTheJpegAndTheRawTakesNoRaw() {
        val camera = FakeCamera(); val sequence = split(camera)
        sequence.start()
        sequence.saved(ImageFormat.JPEG)
        sequence.cancel(); camera.drain()
        assertFalse("bind RAW" in camera.log || "take RAW" in camera.log)
    }

    @Test fun cancelInTheRawPhaseRestoresAndRebindsOnce() {
        val camera = FakeCamera(); val sequence = split(camera)
        sequence.start()
        sequence.saved(ImageFormat.JPEG); camera.drain()
        sequence.cancel(); sequence.cancel(); camera.drain()
        sequence.saved(ImageFormat.RAW_SENSOR); camera.drain()
        assertEquals(listOf("timeout cancelled", "discard", "restore options", "bind JPEG", "discard"),
            camera.log.dropWhile { it != "timeout cancelled" })
        assertFalse("save" in camera.log)
    }

    @Test fun aTimeoutInEitherPhaseReportsOnceAndCleansUp() {
        val jpegPhase = FakeCamera(); val first = split(jpegPhase)
        first.start(); first.timedOut(); jpegPhase.drain()
        first.saved(ImageFormat.JPEG); jpegPhase.drain()
        assertEquals("RAW capture timed out", jpegPhase.errors.single().message)
        assertFalse("bind RAW" in jpegPhase.log)

        val rawPhase = FakeCamera(); val second = split(rawPhase)
        second.start(); second.saved(ImageFormat.JPEG); rawPhase.drain()
        second.timedOut(); second.timedOut(); rawPhase.drain()
        second.saved(ImageFormat.RAW_SENSOR); rawPhase.drain()
        assertEquals(1, rawPhase.errors.size)
        assertTrue(rawPhase.log.indexOf("restore options") < rawPhase.log.indexOf("bind JPEG"))
        assertFalse("save" in rawPhase.log)
    }

    @Test fun aRefusedRawBindingLeavesTheRawPhaseAndReports() {
        val camera = FakeCamera(); camera.bindFails = true
        val sequence = split(camera)
        sequence.start()
        sequence.saved(ImageFormat.JPEG); camera.drain()
        assertEquals("Cannot start RAW capture", camera.errors.single().message)
        assertFalse(camera.rawPhase)
        assertFalse("take RAW" in camera.log || "match JPEG settings" in camera.log)
    }

    @Test fun theCamerasOptionsComeBackBeforeTheJpegBindingOnEveryEnd() {
        for (end in listOf("saved", "failed", "cancelled")) {
            val camera = FakeCamera(); val sequence = split(camera)
            sequence.start(); sequence.saved(ImageFormat.JPEG); camera.drain()
            assertTrue(camera.replacedOptions)
            when (end) {
                "saved" -> sequence.saved(ImageFormat.RAW_SENSOR)
                "failed" -> sequence.failed(IllegalStateException("camera error"))
                else -> sequence.cancel()
            }
            camera.drain()
            assertFalse(end, camera.replacedOptions)
            assertEquals(end, 1, camera.log.count { it == "restore options" })
            assertTrue(end, camera.log.indexOf("restore options") < camera.log.indexOf("bind JPEG"))
        }
    }

    @Test fun aCaptureThatIsNoLongerCurrentStaysQuiet() {
        val camera = FakeCamera(); val sequence = split(camera)
        sequence.start(); camera.current = false
        sequence.saved(ImageFormat.JPEG); camera.drain()
        assertFalse("bind RAW" in camera.log)
        sequence.timedOut(); camera.drain()
        assertTrue(camera.errors.isEmpty())
        assertTrue("discard" in camera.log)
    }
}
