package app.grapheneos.camera.ui

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.grapheneos.camera.R
import app.grapheneos.camera.ui.activities.MainActivity
import com.google.android.material.color.DynamicColorsOptions

/**
 * DiamaneOS: the Tally look, laid over Material's dynamic colours right after they are applied to an
 * activity. The viewfinder screens also draw what is on in the lamp colour. The styles and colours live in
 * themes_diamaneos.xml and colors_diamaneos.xml, so GrapheneOS's own themes and layouts stay untouched.
 */
object TallyTheme {
    val options: DynamicColorsOptions = DynamicColorsOptions.Builder()
        .setOnAppliedCallback { activity ->
            activity.theme.applyStyle(
                if (activity is MainActivity) R.style.ThemeOverlay_Camera_Tally_Viewfinder
                else R.style.ThemeOverlay_Camera_Tally,
                true,
            )
        }
        .build()

    /**
     * A barcode format toggle (black code on white): lit while the format is scanned for, the lamp with the
     * code in the onLamp colour, otherwise the code in white on the controls' dark scrim. Replaces fading
     * the unselected toggles, which left their codes hard to read.
     */
    fun styleQrToggle(view: ImageView, selected: Boolean) {
        view.alpha = 1f
        view.colorFilter = if (selected) {
            val lamp = ContextCompat.getColor(view.context, R.color.tally_lamp)
            val ink = ContextCompat.getColor(view.context, R.color.tally_on_lamp)
            ColorMatrixColorFilter(grey(Color.red(ink), Color.red(lamp), Color.green(ink), Color.green(lamp),
                Color.blue(ink), Color.blue(lamp), 255, 255))
        } else {
            val scrim = ContextCompat.getColor(view.context, R.color.camera_control_scrim)
            ColorMatrixColorFilter(grey(255, 0, 255, 0, 255, 0, 255, Color.alpha(scrim)))
        }
    }

    // Maps a grey image: black (the code) to the first value of each pair, white (the background) to the second.
    private fun grey(r0: Int, r1: Int, g0: Int, g1: Int, b0: Int, b1: Int, a0: Int, a1: Int) = ColorMatrix(floatArrayOf(
        (r1 - r0) / 255f, 0f, 0f, 0f, r0.toFloat(),
        (g1 - g0) / 255f, 0f, 0f, 0f, g0.toFloat(),
        (b1 - b0) / 255f, 0f, 0f, 0f, b0.toFloat(),
        (a1 - a0) / 255f, 0f, 0f, 0f, a0.toFloat(),
    ))
}
