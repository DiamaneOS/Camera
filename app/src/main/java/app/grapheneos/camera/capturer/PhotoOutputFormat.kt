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

        /**
         * JPEG + RAW as two requests, JPEG first, where the device says its HAL drops its
         * processing when both share a request. Needs RAW alone to be supported.
         */
        fun splitsRawJpeg(active: PhotoOutputFormat, supported: List<PhotoOutputFormat>,
                          ultraWide: Boolean, splitUltraWide: Boolean): Boolean =
            active == JPEG_RAW && ultraWide && splitUltraWide && RAW in supported

        /** The ImageCapture format of each phase of a capture in [active] format. */
        fun bindingFormat(active: PhotoOutputFormat, split: Boolean, rawPhase: Boolean): Int = when {
            !split -> active.outputFormat
            rawPhase -> RAW.outputFormat
            else -> JPEG.outputFormat
        }
    }
}
