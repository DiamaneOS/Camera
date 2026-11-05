package app.grapheneos.camera.capturer

import app.grapheneos.camera.capturer.PreviewSnapshot.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSnapshotTest {
    @Test fun onlyAForcedRateRecordingCopiesThePreview() {
        assertEquals(Route.PREVIEW_COPY, PreviewSnapshot.route(hasPhotoStream = false, recording = true, forcedRate = true))
        // A 30 fps recording keeps its photo stream.
        assertEquals(Route.PHOTO_STREAM, PreviewSnapshot.route(hasPhotoStream = true, recording = true, forcedRate = false))
        // Slow motion has no photo stream and no forced rate, as before.
        assertEquals(Route.UNAVAILABLE, PreviewSnapshot.route(hasPhotoStream = false, recording = true, forcedRate = false))
        assertEquals(Route.UNAVAILABLE, PreviewSnapshot.route(hasPhotoStream = false, recording = false, forcedRate = true))
    }

    @Test fun theCopyKeepsTheCameraTurn() {
        assertEquals(1080 to 1920, PreviewSnapshot.copySize(1920, 1080, 90))
        assertEquals(1080 to 1920, PreviewSnapshot.copySize(1920, 1080, 270))
        assertEquals(1920 to 1080, PreviewSnapshot.copySize(1920, 1080, 0))
        assertEquals(1920 to 1080, PreviewSnapshot.copySize(1920, 1080, 180))
    }

    @Test fun rotationUndoesTheDeviceRotation() {
        assertEquals(listOf(0, 270, 180, 90), (0..3).map { PreviewSnapshot.rotationDegrees(it) })
    }

    @Test fun mirroring() {
        assertTrue(PreviewSnapshot.mirror(frontFacing = true, saveAsPreviewed = false))
        assertFalse(PreviewSnapshot.mirror(frontFacing = true, saveAsPreviewed = true))
        assertFalse(PreviewSnapshot.mirror(frontFacing = false, saveAsPreviewed = false))
        assertFalse(PreviewSnapshot.mirror(frontFacing = false, saveAsPreviewed = true))
    }

    // A frame as rows of distinct pixels, so any turn or flip shows.
    private val sensorFrame = listOf(listOf(1, 2, 3), listOf(4, 5, 6))

    private fun turn(frame: List<List<Int>>, degrees: Int): List<List<Int>> {
        var turned = frame
        repeat((degrees / 90) % 4) {
            turned = turned[0].indices.map { column -> turned.indices.reversed().map { row -> turned[row][column] } }
        }
        return turned
    }

    private fun flip(frame: List<List<Int>>) = frame.map { it.reversed() }

    @Test fun savesWhatThePhotoStreamWouldSave() {
        for (sensor in listOf(0, 90, 180, 270)) for (front in listOf(false, true))
        for (surfaceRotation in 0..3) for (asPreviewed in listOf(false, true)) {
            val device = surfaceRotation * 90
            // The photo stream: CameraX turns the sensor frame by this, and flips front photos saved as previewed.
            val streamTurn = if (front) (sensor + device) % 360 else (sensor - device + 360) % 360
            val photo = turn(sensorFrame, streamTurn).let { if (front && asPreviewed) flip(it) else it }

            // The preview copy: upright for the natural orientation, and mirrored on the front camera.
            val copy = turn(sensorFrame, sensor).let { if (front) flip(it) else it }
            val saved = turn(copy, PreviewSnapshot.rotationDegrees(surfaceRotation))
                .let { if (PreviewSnapshot.mirror(front, asPreviewed)) flip(it) else it }

            assertEquals("sensor $sensor front $front rotation $device as previewed $asPreviewed", photo, saved)
            val (width, height) = PreviewSnapshot.copySize(sensorFrame[0].size, sensorFrame.size, sensor)
            assertEquals(width to height, copy[0].size to copy.size)
        }
    }

    @Test fun oneCopyAtATime() {
        val gate = PreviewSnapshot.Gate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }
}
