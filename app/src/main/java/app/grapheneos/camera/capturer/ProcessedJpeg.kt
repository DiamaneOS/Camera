package app.grapheneos.camera.capturer

import androidxc.camera.core.impl.utils.Exif
import app.grapheneos.camera.clearExif
import app.grapheneos.camera.fixExif
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date

/** Processing results no longer hold an Activity or its current shutter operation. */
internal object ProcessedJpeg {
    fun metadata(bytes: ByteArray, spec: ProcessedPhotoSpec): ByteArray {
        require(bytes.size in 1..20 * 1024 * 1024)
        val exif = Exif.createFromInputStream(ByteArrayInputStream(bytes))
        exif.rotate(spec.rotation)
        if (spec.mirrorHorizontal) exif.flipHorizontally()
        if (spec.mirrorVertical) exif.flipVertically()
        val attributes = exif.exifInterface
        if (spec.stripExif) attributes.clearExif() else attributes.fixExif(Date(spec.capturedAt))
        spec.location?.let(exif::attachLocation)
        return ByteArrayOutputStream(bytes.size + 100 * 1024).use { output ->
            attributes.saveAttributes(ByteArrayInputStream(bytes), output)
            output.toByteArray()
        }
    }
}
