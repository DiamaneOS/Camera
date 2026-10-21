package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class DirectCameraLeaseTest {
    @Test fun aDelayedMainRunnableCannotAcquireAfterTimeout() {
        val lease=DirectCameraLease<Any>();var mutated=false
        lease.requestClose();lease.resourcesClosed()
        assertFalse(lease.acquire {mutated=true});assertFalse(mutated);assertTrue(lease.ready())
    }
    @Test fun anUnbindFailureStillWaitsForThePreviousCamera() {
        val lease=DirectCameraLease<Any>();assertTrue(lease.acquire {})
        lease.requestClose();lease.resourcesClosed();assertFalse(lease.ready())
        lease.previousClosed();assertTrue(lease.ready())
    }
    @Test fun aLateOpenIsOwnedAndClosedEvenAfterTheWaiterTimesOut() {
        val lease=DirectCameraLease<Any>();val camera=Any()
        lease.acquire {};lease.beginOpen();lease.requestClose();lease.resourcesClosed();lease.previousClosed()
        assertFalse(lease.ready());assertTrue(lease.arrived(camera));assertFalse(lease.ready())
        assertEquals(listOf(camera),lease.requestClose());lease.closed(camera);assertTrue(lease.ready())
    }
    @Test fun resourcesAndBothCameraClosuresPrecedeRelease() {
        val lease=DirectCameraLease<Any>();val camera=Any()
        lease.acquire {};lease.beginOpen();assertFalse(lease.arrived(camera))
        lease.requestClose();lease.closed(camera);assertFalse(lease.ready())
        lease.previousClosed();assertFalse(lease.ready());lease.resourcesClosed();assertTrue(lease.ready())
    }
    @Test fun synchronousOpenFailureDoesNotInventAnOutstandingDevice() {
        val lease=DirectCameraLease<Any>();lease.acquire {};lease.beginOpen();lease.openFailed()
        lease.requestClose();lease.previousClosed();lease.resourcesClosed();assertTrue(lease.ready())
    }
}
