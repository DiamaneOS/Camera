package app.grapheneos.camera.capturer

import android.location.Location
import org.json.JSONObject
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

internal data class ProcessedPhotoSpec(
    val capturedAt: Long,
    val dateString: String,
    val quality: Int,
    val storage: String,
    val stripExif: Boolean,
    val rotation: Int,
    val mirrorHorizontal: Boolean,
    val mirrorVertical: Boolean,
    val location: Location?,
    val depthEnabled: Boolean,
) {
    fun json(): JSONObject = JSONObject().apply {
        put("capturedAt", capturedAt); put("date", dateString); put("quality", quality); put("storage", storage)
        put("stripExif", stripExif); put("rotation", rotation); put("mirrorH", mirrorHorizontal); put("mirrorV", mirrorVertical)
        put("depthEnabled", depthEnabled)
        location?.let { point -> put("location", JSONObject().put("latitude", point.latitude).put("longitude", point.longitude)
            .put("altitude", if (point.hasAltitude()) point.altitude else JSONObject.NULL).put("time", point.time)) }
    }

    companion object {
        fun date(time: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(time))
        fun fromJson(value: JSONObject): ProcessedPhotoSpec {
            val point = value.optJSONObject("location")?.let {
                Location("camera").apply {
                    latitude = it.getDouble("latitude"); longitude = it.getDouble("longitude"); time = it.getLong("time")
                    require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
                    if (!it.isNull("altitude")) altitude = it.getDouble("altitude").also { altitude -> require(altitude.isFinite()) }
                }
            }
            val date = value.getString("date"); require(date.matches(Regex("[0-9]{8}_[0-9]{6}_[0-9]{3}")))
            val rotation = value.getInt("rotation"); require(rotation in listOf(0, 90, 180, 270))
            val quality = value.getInt("quality"); require(quality in 1..100)
            return ProcessedPhotoSpec(value.getLong("capturedAt"), date, quality, value.getString("storage"),
                value.getBoolean("stripExif"), rotation, value.getBoolean("mirrorH"), value.getBoolean("mirrorV"), point,
                value.optBoolean("depthEnabled", value.optBoolean("depthExperiment")))
        }
    }
}
