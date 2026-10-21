package app.grapheneos.camera.capturer

import androidx.camera.core.ImageCapture
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoOutputFormatTest {
    @Test fun jpegIsAlwaysAvailableAndIsTheSafeDefault() {
        assertEquals(listOf(PhotoOutputFormat.JPEG), PhotoOutputFormat.supported(emptySet()))
        assertEquals(PhotoOutputFormat.JPEG, PhotoOutputFormat.stored(null))
        assertEquals(PhotoOutputFormat.JPEG, PhotoOutputFormat.stored("unsupported"))
    }

    @Test fun rawAndPairedFormatsAreAdvertisedIndependently() {
        assertEquals(listOf(PhotoOutputFormat.JPEG, PhotoOutputFormat.RAW),
            PhotoOutputFormat.supported(setOf(ImageCapture.OUTPUT_FORMAT_RAW)))
        assertEquals(listOf(PhotoOutputFormat.JPEG, PhotoOutputFormat.JPEG_RAW, PhotoOutputFormat.RAW),
            PhotoOutputFormat.supported(setOf(ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG)))
    }

    @Test fun jpegFallbackDoesNotRequireChangingStoredPreference() {
        val requested = PhotoOutputFormat.RAW
        assertEquals(PhotoOutputFormat.JPEG,
            PhotoOutputFormat.effective(requested, listOf(PhotoOutputFormat.JPEG), false))
        assertEquals(PhotoOutputFormat.RAW,
            PhotoOutputFormat.effective(requested, PhotoOutputFormat.entries, false))
        assertEquals(PhotoOutputFormat.JPEG,
            PhotoOutputFormat.effective(requested, PhotoOutputFormat.entries, true))
    }

    @Test fun pairedCaptureSplitsOnlyOnTheOptedInUltraWide() {
        val all = PhotoOutputFormat.entries
        assertEquals(true, PhotoOutputFormat.splitsRawJpeg(PhotoOutputFormat.JPEG_RAW, all, true, true))
        assertEquals(false, PhotoOutputFormat.splitsRawJpeg(PhotoOutputFormat.JPEG_RAW, all, false, true))
        assertEquals(false, PhotoOutputFormat.splitsRawJpeg(PhotoOutputFormat.JPEG_RAW, all, true, false))
        assertEquals(false, PhotoOutputFormat.splitsRawJpeg(PhotoOutputFormat.JPEG, all, true, true))
        assertEquals(false, PhotoOutputFormat.splitsRawJpeg(PhotoOutputFormat.RAW, all, true, true))
        assertEquals(false, PhotoOutputFormat.splitsRawJpeg(PhotoOutputFormat.JPEG_RAW,
            listOf(PhotoOutputFormat.JPEG, PhotoOutputFormat.JPEG_RAW), true, true))
    }

    @Test fun splitCaptureBindsJpegFirstThenRawAlone() {
        assertEquals(ImageCapture.OUTPUT_FORMAT_JPEG, PhotoOutputFormat.bindingFormat(PhotoOutputFormat.JPEG_RAW, true, false))
        assertEquals(ImageCapture.OUTPUT_FORMAT_RAW, PhotoOutputFormat.bindingFormat(PhotoOutputFormat.JPEG_RAW, true, true))
        assertEquals(ImageCapture.OUTPUT_FORMAT_RAW_JPEG, PhotoOutputFormat.bindingFormat(PhotoOutputFormat.JPEG_RAW, false, false))
        assertEquals(ImageCapture.OUTPUT_FORMAT_RAW, PhotoOutputFormat.bindingFormat(PhotoOutputFormat.RAW, false, false))
    }
}
