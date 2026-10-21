package app.grapheneos.camera.capturer

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Build
import android.os.SystemClock

/** Selected-photo capability only. This provider never returns images, masks, originals or paths. */
class PortraitCapabilitiesProvider : ContentProvider() {
    override fun onCreate()=true
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle {
        val ctx=checkNotNull(context);val uid=Binder.getCallingUid();val pid=Binder.getCallingPid()
        if(method !in setOf("portraitEditable","createPortraitEdit")) return Bundle.EMPTY
        val uri=arg?.takeIf {it.length<=4096}?.let(Uri::parse)
        if(uri==null || uri.scheme!="content" || uri.authority!=android.provider.MediaStore.AUTHORITY)
            return Bundle().apply {putBoolean("editable",false)}
        val readable=uid==android.os.Process.myUid() ||
            (PortraitStore.readGrantAliases(ctx,uri).any {ctx.checkUriPermission(it,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED} ||
            (Build.VERSION.SDK_INT>=33 && holds(ctx,Manifest.permission.READ_MEDIA_IMAGES,pid,uid,callingPackage)) ||
            ((Build.VERSION.SDK_INT<33 || legacyCaller(ctx,callingPackage)) &&
                holds(ctx,Manifest.permission.READ_EXTERNAL_STORAGE,pid,uid,callingPackage)))
        // Check public access first so unprivileged calls never inspect the private index.
        val editable=readable && PortraitStore.contains(ctx,uri)
        return Bundle().apply {
            putBoolean("editable",editable)
            if(method=="createPortraitEdit" && editable) putString("token",grants.issue(uid,uri.toString()))
        }
    }
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,order: String?): Cursor?=null
    override fun getType(uri: Uri): String?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=null
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?)=0
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?)=0
    /**
     * The caller's own permission and app op. PermissionChecker's data-delivery check also
     * requires this app, the proxy, to hold the permission, which a camera never does, so it
     * refused every gallery.
     */
    private fun holds(ctx: android.content.Context,permission: String,pid: Int,uid: Int,pkg: String?): Boolean {
        if(pkg==null || ctx.checkPermission(permission,pid,uid)!=PackageManager.PERMISSION_GRANTED) return false
        val ops=ctx.getSystemService(android.app.AppOpsManager::class.java)
        fun allowed(op: String)=ops.unsafeCheckOpNoThrow(op,uid,pkg)==android.app.AppOpsManager.MODE_ALLOWED
        val own=android.app.AppOpsManager.permissionToOp(permission)
        // MediaProvider gates images on the read-media-images op for legacy readers as well.
        return (own==null || allowed(own)) && (Build.VERSION.SDK_INT<33 || (android.app.AppOpsManager.permissionToOp(Manifest.permission.READ_MEDIA_IMAGES)?.let(::allowed) ?: true))
    }
    /** The calling package itself, not any package sharing its uid, decides the legacy path. */
    private fun legacyCaller(ctx: android.content.Context,pkg: String?): Boolean = pkg!=null && try {
        ctx.packageManager.getApplicationInfo(pkg,0).targetSdkVersion<33
    } catch (_: PackageManager.NameNotFoundException) { false }
    companion object {
        private val grants=PortraitEditGrants {SystemClock.elapsedRealtime()}
        fun consume(token: String?,uid: Int,uri: Uri)=grants.consume(token,uid,uri.toString())
    }
}
