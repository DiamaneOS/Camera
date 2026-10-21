package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PreviewWorkerPolicyTest {
    @Test fun permissionFreePlatformCanShare() {assertTrue(PreviewWorkerPolicy.framesAllowed(null,false));assertTrue(PreviewWorkerPolicy.framesAllowed(emptyArray(),false))}
    @Test fun injectedSensorDeclarationMustBeRevoked() {
        val injected=arrayOf(PreviewWorkerPolicy.SENSOR_PERMISSION)
        assertTrue(PreviewWorkerPolicy.declarationsAllowed(injected))
        assertFalse(PreviewWorkerPolicy.framesAllowed(injected,true));assertTrue(PreviewWorkerPolicy.framesAllowed(injected,false))
    }
    @Test fun sensitiveDeclarationsAreRejectedEvenWhenDenied() {
        for(name in arrayOf("android.permission.INTERNET","android.permission.CAMERA","android.permission.READ_MEDIA_IMAGES","android.permission.ACCESS_FINE_LOCATION")) {
            assertFalse(PreviewWorkerPolicy.framesAllowed(arrayOf(name),false))
            assertFalse(PreviewWorkerPolicy.framesAllowed(arrayOf(PreviewWorkerPolicy.SENSOR_PERMISSION,name),false))
        }
    }
    @Test fun unexpectedFuturePermissionFailsClosed() {assertFalse(PreviewWorkerPolicy.declarationsAllowed(arrayOf("android.permission.NEW_PERMISSION")))}
}
