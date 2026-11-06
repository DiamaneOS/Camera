package app.grapheneos.camera.capturer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.IMAGE_NAME_PREFIX
import app.grapheneos.camera.ITEM_TYPE_IMAGE
import app.grapheneos.camera.util.getTreeDocumentUri
import app.grapheneos.camera.util.removePendingFlagFromUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Publish a capture only after all of its files have been written and synced. */
internal object PhotoFileStore {
    data class Photo(val file: File, val extension: String, val mime: String)

    fun save(context: Context, photos: List<Photo>, storageLocation: String, time: Date,
             dateString: String? = null, cancelled: () -> Boolean = { false },
             removedPhoto: ((Uri) -> Unit)? = null, createdPhoto: ((Uri) -> Unit)? = null): List<CapturedItem> {
        require(photos.isNotEmpty() && photos.size <= 2)
        val resolver = context.contentResolver
        val date = dateString ?: SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(time)
        require(date.matches(Regex("[0-9]{8}_[0-9]{6}_[0-9]{3}")))
        val mediaStore = storageLocation == CamConfig.SettingValues.Default.STORAGE_LOCATION
        val created = arrayListOf<Uri>()
        try {
            for (photo in photos) {
                check(!cancelled())
                check(photo.file.isFile && photo.file.length() > 0)
                val name = IMAGE_NAME_PREFIX + date + photo.extension
                val uri = if (mediaStore) {
                    resolver.insert(CamConfig.imageCollectionUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, photo.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, DEFAULT_MEDIA_STORE_CAPTURE_PATH)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        put(MediaStore.Images.ImageColumns.DATE_TAKEN, time.time)
                    })
                } else {
                    DocumentsContract.createDocument(resolver, getTreeDocumentUri(Uri.parse(storageLocation)), photo.mime, name)
                } ?: error("Cannot create photo")
                created.add(uri)
                createdPhoto?.invoke(uri)
                resolver.openAssetFileDescriptor(uri, "w")!!.use { output ->
                    photo.file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val length = input.read(buffer)
                            if (length < 0) break
                            check(!cancelled())
                            var offset = 0
                            while (offset < length) {
                                val written = Os.write(output.fileDescriptor, buffer, offset, length - offset)
                                check(written > 0)
                                offset += written
                            }
                        }
                    }
                    if (uri.authority == MediaStore.AUTHORITY || uri.authority == SAF_URI_HOST_EXTERNAL_STORAGE) {
                        Os.fsync(output.fileDescriptor)
                    }
                }
            }
            check(!cancelled())
            if (mediaStore) created.forEach { removePendingFlagFromUri(resolver, it) }
            check(!cancelled())
            return created.mapIndexed { index, uri -> CapturedItem(ITEM_TYPE_IMAGE, date, uri, photos[index].extension == ".dng") }
        } catch (failure: Exception) {
            for (uri in created) {
                try {
                    val removed=if (mediaStore) resolver.delete(uri, null, null)>0
                        else DocumentsContract.deleteDocument(resolver, uri)
                    if(removed)removedPhoto?.invoke(uri)
                } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}
