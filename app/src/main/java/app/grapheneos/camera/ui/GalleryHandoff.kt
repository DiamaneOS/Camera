package app.grapheneos.camera.ui

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.grapheneos.camera.ui.activities.MainActivity

/**
 * DiamaneOS: the gallery button opens the newest capture in the system gallery (Glimpse on DiamaneOS) with
 * the standard review intent, so photos are viewed and edited in one place. The in-app viewer opens instead
 * while the phone is locked (it shows only this session's captures), while the newest photo is still being
 * processed, when the capture is not in the media store, or when no gallery takes the intent.
 */
internal object GalleryHandoff {
    private const val TAG = "GalleryHandoff"

    fun open(activity: MainActivity): Boolean {
        if (activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (!activity.isThumbnailLoaded) return false
        val item = activity.camConfig.lastCapturedItem ?: return false
        if (item.processingId != null) return false
        val uri = mediaStoreUri(activity, item.uri) ?: return false
        val intent = Intent(MediaStore.ACTION_REVIEW)
            .setDataAndType(uri, item.mimeType())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "the gallery cannot open the capture", e)
            false
        }
    }

    // Captures in a folder chosen in More settings have a document link. The gallery needs the media store
    // link of the same file, which exists once the file is indexed.
    private fun mediaStoreUri(activity: MainActivity, uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        if (!DocumentsContract.isDocumentUri(activity, uri)) return null
        return try {
            MediaStore.getMediaUri(activity, uri)
        } catch (e: RuntimeException) {
            null
        }
    }
}
