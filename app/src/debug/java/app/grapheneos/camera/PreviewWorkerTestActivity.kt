package app.grapheneos.camera

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.*
import org.json.JSONObject
import java.io.File

/** DUMP-only fixed-package diagnostic. Never accepts file paths or camera identifiers. */
class PreviewWorkerTestActivity:Activity() {
    private val row=JSONObject()
    private var bound=false
    private val handler=Handler(Looper.getMainLooper())
    private val timeout=Runnable {row.put("bindTimeout",true);save();finish()}
    private fun save() {File(File(filesDir,"processing-test").apply { mkdirs() },"preview-worker-boundary.json").writeText(row.toString(2))}
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {handler.removeCallbacks(timeout);row.put("connected",true);save();finish()}
        override fun onServiceDisconnected(name:ComponentName) {row.put("disconnected",true);save()}
        override fun onNullBinding(name:ComponentName) {handler.removeCallbacks(timeout);row.put("nullBinding",true);save();finish()}
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val target="app.grapheneos.camera.processor.dev"
        var stage="package"
        try {
            val info=packageManager.getPackageInfo(target,PackageManager.GET_PERMISSIONS)
            row.put("noRequestedPermissions",info.requestedPermissions.isNullOrEmpty())
            row.put("allowedDeclarations",app.grapheneos.camera.capturer.PreviewWorkerPolicy.declarationsAllowed(info.requestedPermissions))
            row.put("sensorsDenied",packageManager.checkPermission(app.grapheneos.camera.capturer.PreviewWorkerPolicy.SENSOR_PERMISSION,target)==PackageManager.PERMISSION_DENIED)
            row.put("distinctUid",info.applicationInfo?.uid!=android.os.Process.myUid())
            row.put("signaturesMatch",packageManager.checkSignatures(packageName,target)==PackageManager.SIGNATURE_MATCH)
            stage="service"
            val component=ComponentName(target,"app.grapheneos.camera.processor.PortraitProcessorService")
            val service=packageManager.getServiceInfo(component,0)
            row.put("exported",service.exported).put("enabled",service.enabled)
            row.put("permissionMatches",service.permission==target+".BIND_PREVIEW")
            stage="permission"
            val permission=packageManager.getPermissionInfo(target+".BIND_PREVIEW",0)
            row.put("permissionOwnerMatches",permission.packageName==target)
            row.put("signatureProtection",(permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)==PermissionInfo.PROTECTION_SIGNATURE)
            row.put("callerPermissionGranted",checkSelfPermission(target+".BIND_PREVIEW")==PackageManager.PERMISSION_GRANTED)
            stage="bind"
            bound=bindService(Intent().setComponent(component),connection,BIND_AUTO_CREATE)
            row.put("bound",bound);save()
            if(bound)handler.postDelayed(timeout,4000) else finish()
        } catch(error:Exception) {row.put("errorStage",stage).put("errorType",error.javaClass.simpleName);save();finish()}
    }
    override fun onDestroy() {handler.removeCallbacks(timeout);if(bound)unbindService(connection);super.onDestroy()}
}
