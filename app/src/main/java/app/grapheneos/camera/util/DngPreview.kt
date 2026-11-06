package app.grapheneos.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Point
import android.provider.DocumentsContract
import android.util.Size
import android.net.Uri
import java.io.File
import java.io.InputStream

/** Normalize a private decode copy: Android's RAW codec does not consistently apply DNG orientation. */
object DngPreview {
    /**
     * A DNG from the gallery may come from anyone who can write to the photo folder. The camera
     * process holds the camera, microphone and location, so it never parses such a file itself:
     * MediaProvider or the document provider renders the preview in its own process.
     */
    fun thumbnail(context: Context, uri: Uri, width: Int, height: Int): Bitmap {
        val w = width.coerceIn(1, 4096); val h = height.coerceIn(1, 4096)
        val document = DocumentsContract.isDocumentUri(context, uri)
        val bitmap = if (document)
            checkNotNull(DocumentsContract.getDocumentThumbnail(context.contentResolver, uri, Point(w, h), null))
        else context.contentResolver.loadThumbnail(uri, Size(w, h), null)
        // Thumbnails of DNGs come back in sensor orientation. MediaStore knows the rotation.
        // Documents have no such column, so read just the tag with the Java EXIF reader.
        val (degrees, flipped) = if (!document) mediaRotation(context, uri) to false else try {
            context.contentResolver.openInputStream(uri)!!.use {
                val exif = androidxc.exifinterface.media.ExifInterface(it)
                exif.rotationDegrees to exif.isFlipped
            }
        } catch (_: Exception) { 0 to false }
        val matrix = Matrix().apply { if (flipped) postScale(-1f, 1f); postRotate(degrees.toFloat()) }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun mediaRotation(context: Context, uri: Uri): Int = try {
        context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.ORIENTATION), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else 0
        } ?: 0
    } catch (_: Exception) { 0 }

    /** Only for a DNG this app has just written to its own private storage. */

    fun decode(context: Context, file: File, listener: ImageDecoder.OnHeaderDecodedListener): Bitmap =
        file.inputStream().use { decode(context, it, listener) }

    private fun decode(context: Context, input: InputStream, listener: ImageDecoder.OnHeaderDecodedListener): Bitmap {
        val copy = File.createTempFile("dng-preview-", ".dng", context.cacheDir)
        try {
            copy.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 128L * 1024 * 1024) { "DNG preview input too large" }
                    output.write(buffer, 0, count)
                }
            }
            val orientation = DngOrientation.normalizeCopy(copy)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(copy)) { decoder, info, source ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                listener.onHeaderDecoded(decoder, info, source)
            }
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } finally { copy.delete() }
    }

}
