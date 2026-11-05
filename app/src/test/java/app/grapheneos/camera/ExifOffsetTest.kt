package app.grapheneos.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOffsetTest {
    private fun offset(hours: Int, minutes: Int = 0) = (hours * 60 + minutes) * 60_000

    @Test fun eastOfUtc() {
        assertEquals("+00:00", exifOffsetTime(0))
        assertEquals("+01:00", exifOffsetTime(offset(1)))
        assertEquals("+05:30", exifOffsetTime(offset(5, 30)))
        assertEquals("+05:45", exifOffsetTime(offset(5, 45)))
        assertEquals("+14:00", exifOffsetTime(offset(14)))
    }

    @Test fun westOfUtcKeepsTwoDigitsAndOneSign() {
        assertEquals("-05:00", exifOffsetTime(offset(-5)))
        assertEquals("-03:30", exifOffsetTime(offset(-3, -30)))
        assertEquals("-10:00", exifOffsetTime(offset(-10)))
        assertEquals("-09:30", exifOffsetTime(offset(-9, -30)))
    }
}
