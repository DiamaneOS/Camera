package app.grapheneos.camera.capturer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingAdmissionTest {
    @Test fun aNewInstanceWaitsForTheTaskOfAnUnboundOne() {
        val admission = ProcessingAdmission()
        // The first instance accepted a photo, then was cancelled and unbound mid-task.
        assertTrue(admission.tryEnter())
        // The next photo's instance is refused while that native task still runs.
        assertFalse(admission.tryEnter())
        admission.leave()
        assertTrue(admission.tryEnter())
    }

    @Test(expected = IllegalStateException::class)
    fun leavingTwiceIsAnError() {
        val admission = ProcessingAdmission()
        admission.tryEnter(); admission.leave(); admission.leave()
    }
}
