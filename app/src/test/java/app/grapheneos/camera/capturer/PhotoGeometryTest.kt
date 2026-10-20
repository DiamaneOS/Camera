package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PhotoGeometryTest {
    @Test fun offCentreFrontSubjectFollowsTheSavedImagePreference() {
        assertEquals(.8f, PhotoGeometry.savedSubjectX(.2f, true, false), .00001f)
        assertEquals(.2f, PhotoGeometry.savedSubjectX(.2f, true, true), .00001f)
        assertEquals(.2f, PhotoGeometry.savedSubjectX(.2f, false, false), .00001f)
    }

    @Test fun asymmetricalFrontReferenceMatchesSensorPixelsBeforeOutputMirroring() {
        val sensor = intArrayOf(1, 2, 3, 4, 5, 6)
        val frontPreview = intArrayOf(3, 2, 1, 6, 5, 4)
        PhotoGeometry.unmirrorReference(frontPreview, 3, 2, true)
        assertArrayEquals(sensor, frontPreview)
        val rearPreview = sensor.copyOf()
        PhotoGeometry.unmirrorReference(rearPreview, 3, 2, false)
        assertArrayEquals(sensor, rearPreview)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPointIsRejected() { PhotoGeometry.savedSubjectX(Float.NaN, true, false) }
}
