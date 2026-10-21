package app.grapheneos.camera.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.CameraMode
import app.grapheneos.camera.R
import app.grapheneos.camera.capturer.PhotoOutputFormat
import app.grapheneos.camera.databinding.SettingsBinding
import app.grapheneos.camera.ui.activities.MainActivity

/** The photo format, Portrait blur and RAW lens rows of the settings panel. */
internal class PhotoFormatSettings(
    private val binding: SettingsBinding,
    private val camConfig: CamConfig,
    private val mActivity: MainActivity,
    private val showOnlyRelevantSettings: () -> Unit,
) {
    private fun getString(@StringRes id: Int) = mActivity.getString(id)

    fun reloadPhotoFormats() {
        val formats = camConfig.supportedPhotoOutputFormats
        val processed = camConfig.currentMode == CameraMode.NIGHT || camConfig.currentMode == CameraMode.PORTRAIT
        val visible = !camConfig.isVideoMode && ((camConfig.currentMode == CameraMode.CAMERA && formats.size > 1) || processed)
        binding.photoFormatSetting.visibility = if (visible) View.VISIBLE else View.GONE
        binding.photoFormatSpinner.isEnabled = formats.size > 1
        binding.photoFormatSpinner.onItemSelectedListener = null
        binding.photoFormatSpinner.adapter = ArrayAdapter(mActivity, android.R.layout.simple_spinner_dropdown_item,
            formats.map { getString(when (it) {
                PhotoOutputFormat.JPEG -> R.string.photo_jpeg
                PhotoOutputFormat.JPEG_RAW -> R.string.photo_jpeg_raw
                PhotoOutputFormat.RAW -> R.string.photo_raw
            }) })
        binding.photoFormatSpinner.setSelection(formats.indexOf(camConfig.activePhotoOutputFormat).coerceAtLeast(0), false)
        binding.photoFormatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val choice = formats[position]
                if (choice != camConfig.activePhotoOutputFormat) {
                    camConfig.photoOutputFormat = choice
                    camConfig.startCamera(true)
                    showOnlyRelevantSettings()
                }
            }
        }
        val raw = visible && camConfig.activePhotoOutputFormat != PhotoOutputFormat.JPEG
        val night = camConfig.softwareNight
        val portrait = camConfig.softwarePortrait
        binding.portraitBlurSetting.visibility = if (portrait) View.VISIBLE else View.GONE
        binding.portraitBlurSlider.clearOnChangeListeners()
        binding.portraitBlurSlider.value = camConfig.portraitBlur.toFloat()
        binding.portraitBlurSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) camConfig.portraitBlur = value.toInt()
        }
        binding.rawFormatInfo.setText(when {
            processed -> R.string.processed_format_info
            camConfig.activePhotoOutputFormat == PhotoOutputFormat.JPEG_RAW &&
                camConfig.enhancedPhotoAvailable -> R.string.jpeg_raw_format_info
            else -> R.string.raw_format_info
        })
        binding.rawFormatInfo.visibility = if (raw || processed) View.VISIBLE else View.GONE
        val lenses = camConfig.selectableRawLenses()
        val presets = camConfig.zoomPresets()
        val coveredByPresets = presets.any { it == 1f } && lenses.all {
            it.label == R.string.lens_main || it.label == R.string.lens_ultra_wide && presets.any { zoom -> zoom < 1f }
        }
        binding.rawLensSetting.visibility = if ((raw || night) && lenses.size > 1 && !coveredByPresets) View.VISIBLE else View.GONE
        binding.rawLensSpinner.onItemSelectedListener = null
        binding.rawLensSpinner.adapter = ArrayAdapter(mActivity, android.R.layout.simple_spinner_dropdown_item,
            lenses.map { getString(it.label) })
        val selected = lenses.indexOfFirst { it.id == camConfig.rawLensId }.coerceAtLeast(0)
        binding.rawLensSpinner.setSelection(selected, false)
        binding.rawLensSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != selected) {
                    camConfig.rawLensId = lenses[position].id
                    camConfig.startCamera(true)
                    showOnlyRelevantSettings()
                }
            }
        }
    }
}
