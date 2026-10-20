package app.grapheneos.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.io.InputStream

/** Normalize a private decode copy: Android's RAW codec does not consistently apply DNG orientation. */
object DngPreview {
    fun decode(context: Context, uri: Uri, listener: ImageDecoder.OnHeaderDecodedListener): Bitmap =
        context.contentResolver.openInputStream(uri)!!.use { decode(context, it, listener) }

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
