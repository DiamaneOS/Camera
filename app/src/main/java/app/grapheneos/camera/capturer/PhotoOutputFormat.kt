package app.grapheneos.camera.capturer

import androidx.camera.core.ImageCapture

enum class PhotoOutputFormat(val outputFormat: Int) {
    JPEG(ImageCapture.OUTPUT_FORMAT_JPEG),
    JPEG_RAW(ImageCapture.OUTPUT_FORMAT_RAW_JPEG),
    RAW(ImageCapture.OUTPUT_FORMAT_RAW);

    companion object {
        fun stored(value: String?): PhotoOutputFormat = entries.firstOrNull { it.name == value } ?: JPEG

        fun supported(formats: Set<Int>): List<PhotoOutputFormat> =
            entries.filter { it == JPEG || it.outputFormat in formats }

        fun effective(requested: PhotoOutputFormat, supported: List<PhotoOutputFormat>, processedMode: Boolean): PhotoOutputFormat =
            if (!processedMode && requested in supported) requested else JPEG
    }
}
