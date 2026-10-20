package app.grapheneos.camera.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ToggleButton
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.CameraMode
import app.grapheneos.camera.R
import app.grapheneos.camera.capturer.PhotoOutputFormat
import app.grapheneos.camera.capturer.VideoFrameRate
import app.grapheneos.camera.databinding.SettingsBinding
import app.grapheneos.camera.ui.activities.MainActivity
import app.grapheneos.camera.ui.activities.MoreSettings
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.util.Collections
import kotlin.math.max

@SuppressLint("ClickableViewAccessibility")
class SettingsDialog(val mActivity: MainActivity, themedContext: Context) :
    ComponentDialog(themedContext) {
    val camConfig = mActivity.camConfig

    private val binding: SettingsBinding by lazy { SettingsBinding.inflate(layoutInflater) }
    private var dialog: View
    var locToggle: ToggleButton
    private var flashToggle: ImageView
    private var aRToggle: ToggleButton
    var torchToggle: ToggleButton
    private var gridToggle: ImageView
    var videoQualitySpinner: Spinner
    var videoFrameRateSpinner: Spinner
    private lateinit var vQAdapter: ArrayAdapter<String>
    private var updatingVideoChoices = false
    private var displayedVideoQuality: Quality? = null
    private var frameRateChoices: List<VideoFrameRate?> = listOf(null)
    private var focusTimeoutSpinner: Spinner
    private var timerSpinner: Spinner

    var mScrollView: ScrollView
    var mScrollViewContent: View

    var includeAudioToggle: MaterialSwitch
    var enableEISToggle: MaterialSwitch

    var selfIlluminationToggle: MaterialSwitch

    var waitForFocusLockSwitch: MaterialSwitch

    private val timeOptions = mActivity.resources.getStringArray(R.array.time_options)

    private var includeAudioSetting: View
    private var enableEISSetting: View
    private var selfIlluminationSetting: View
    private var videoQualitySetting: View
    private var timerSetting: View

    var settingsFrame: View

    // Region the panel was last sized for, so the sizing runs when it moves and not on every
    // frame the panel is drawn in.
    private val sizedForRegion = Rect()

    private var moreSettingsButton: View

    private val tabSelectedColor =
        MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)

    private fun getString(@StringRes id: Int) = mActivity.getString(id)

    // Own Back in the focusable panel, including gesture Back, while keeping slide dismissal.
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            slideDialogUp()
        }
    }

    init {
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, backCallback)
        setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CAMERA,
                KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT -> {
                    if (event.action == KeyEvent.ACTION_UP) mActivity.onKeyUp(code, event)
                    else mActivity.onKeyDown(code, event)
                    true
                }
                else -> false
            }
        }

        dialog = binding.settingsDialog
        dialog.setOnClickListener {}

        moreSettingsButton = binding.moreSettings
        moreSettingsButton.setOnClickListener {
            if (!mActivity.videoCapturer.isRecording) {
                MoreSettings.start(mActivity)
            } else {
                mActivity.showMessage(getString(R.string.more_settings_unavailable_during_recording))
            }
        }

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0f)

        setOnDismissListener {
            mActivity.settingsIcon.visibility = View.VISIBLE
            mActivity.refreshCaptureHints()
        }

        val background: View = binding.background
        background.setOnClickListener {
            slideDialogUp()
        }

        val rootView = binding.root
        var scrollingSettings = false
        rootView.setOnInterceptTouchEventListener(
            object : SettingsFrameLayout.OnInterceptTouchEventListener {

                override fun onInterceptTouchEvent(
                    view: SettingsFrameLayout?,
                    ev: MotionEvent?,
                    disallowIntercept: Boolean
                ): Boolean {
                    val event = ev ?: return false
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val bounds = Rect()
                        binding.settingsScrollview.getGlobalVisibleRect(bounds)
                        scrollingSettings = bounds.contains(event.rawX.toInt(), event.rawY.toInt()) &&
                            (binding.settingsScrollview.canScrollVertically(-1) || binding.settingsScrollview.canScrollVertically(1))
                    }
                    // The camera's fling handler dismisses the panel as a side effect.
                    // Keep gestures that begin in overflowing settings with the ScrollView.
                    if (scrollingSettings || disallowIntercept) {
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scrollingSettings = false
                        return false
                    }
                    return mActivity.gestureDetector.onTouchEvent(event)
                }

                override fun onTouchEvent(
                    view: SettingsFrameLayout?,
                    event: MotionEvent?
                ): Boolean {
                    return false
                }
            }
        )

        settingsFrame = binding.settingsFrame

        binding.root.viewTreeObserver.addOnPreDrawListener { updatePanelRegion() }

        // The preview belongs to the activity's window, so resizing it schedules no traversal in
        // this one: without this the panel would keep the bounds of the preview it was opened over.
        mActivity.previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePanelRegion()
        }

        locToggle = binding.locationToggle
        locToggle.setOnClickListener {
            if (mActivity.videoCapturer.isRecording) {
                locToggle.isChecked = !locToggle.isChecked
                mActivity.showMessage(
                    getString(R.string.toggle_geo_tagging_unsupported_while_recording)
                )
            } else {
                camConfig.requireLocation = locToggle.isChecked
            }
        }

        flashToggle = binding.flashToggleOption
        flashToggle.setOnClickListener {
            if (mActivity.requiresVideoModeOnly) {
                mActivity.showMessage(
                    getString(R.string.flash_switch_unsupported)
                )
            } else {
                camConfig.toggleFlashMode()
            }
        }

        aRToggle = binding.aspectRatioToggle
        aRToggle.setOnClickListener {
            if (camConfig.isVideoMode) {
                updateAspectRatioToggle(is16by9 = true)
                mActivity.showMessage(
                    getString(R.string.four_by_three_unsupported_in_video)
                )
            } else {
                camConfig.toggleAspectRatio()
                updateAspectRatioToggle(camConfig.aspectRatio == AspectRatio.RATIO_16_9)
            }
        }

        torchToggle = binding.torchToggleOption
        torchToggle.setOnClickListener {
            if (camConfig.isFlashAvailable) {
                camConfig.toggleTorchState()
            } else {
                torchToggle.isChecked = false
                mActivity.showMessage(
                    getString(R.string.flash_unavailable_in_current_mode)
                )
            }
        }

        gridToggle = binding.gridToggleOption
        gridToggle.setOnClickListener {
            camConfig.gridType = when (camConfig.gridType) {
                CamConfig.GridType.NONE -> CamConfig.GridType.THREE_BY_THREE
                CamConfig.GridType.THREE_BY_THREE -> CamConfig.GridType.FOUR_BY_FOUR
                CamConfig.GridType.FOUR_BY_FOUR -> CamConfig.GridType.GOLDEN_RATIO
                CamConfig.GridType.GOLDEN_RATIO -> CamConfig.GridType.NONE
            }
            updateGridToggleUI()
        }

        videoQualitySpinner = binding.videoQualitySpinner

        videoQualitySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long
                ) {

                    if (updatingVideoChoices || mActivity.videoCapturer.isRecording ||
                        position !in 0 until vQAdapter.count) return
                    val choice = vQAdapter.getItem(position) ?: return
                    if (videoQualitySpinner.selectedItem != choice) return
                    val quality = titleToQuality(choice)
                    if (quality == displayedVideoQuality) return
                    displayedVideoQuality = quality
                    updateVideoQuality(choice)
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

        videoFrameRateSpinner = binding.videoFrameRateSpinner
        videoFrameRateSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (updatingVideoChoices || mActivity.videoCapturer.isRecording ||
                        position !in frameRateChoices.indices ||
                        videoFrameRateSpinner.selectedItemPosition != position) return
                    camConfig.setVideoFrameRate(frameRateChoices[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        if (mActivity.requiresVideoModeOnly) {
            binding.waitForFocusLockSetting.visibility = View.GONE
        }

        waitForFocusLockSwitch = binding.waitForFocusLockSwitch
        waitForFocusLockSwitch.isChecked = camConfig.prioritizeCaptureQuality
        waitForFocusLockSwitch.setOnClickListener {
            camConfig.prioritizeCaptureQuality = waitForFocusLockSwitch.isChecked
            if (camConfig.cameraProvider != null) {
                camConfig.startCamera(true)
            }
        }

        selfIlluminationToggle = binding.selfIlluminationSwitch
        selfIlluminationToggle.setOnCheckedChangeListener { _, isChecked ->
            camConfig.selfIlluminate = isChecked
        }
        binding.selfIlluminationSwitchContainer.setOnTouchListener { _, event ->
            event.setLocation(0f, 0f)
            selfIlluminationToggle.dispatchTouchEvent(event)
            true
        }

        focusTimeoutSpinner = binding.focusTimeoutSpinner
        focusTimeoutSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long
                ) {

                    val selectedOption = focusTimeoutSpinner.selectedItem.toString()
                    updateFocusTimeout(selectedOption)

                }

                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

        focusTimeoutSpinner.setSelection(2)

        timerSpinner = binding.timerSpinner
        timerSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    position: Int,
                    p3: Long
                ) {

                    val selectedOption = timerSpinner.selectedItem.toString()

                    if (selectedOption == timeOptions[0]) {
                        updateTimerDuration(0)
                    } else {
                        try {
                            val durS = selectedOption.substring(0, selectedOption.length - 1)
                            updateTimerDuration(durS.toInt())
                        } catch (exception: Exception) {
                            mActivity.showMessage(
                                getString(R.string.unexpected_error_while_setting_timer_duration)
                            )
                        }
                    }

                }

                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

        restoreTimerDuration()

        mScrollView = binding.settingsScrollview
        mScrollViewContent = binding.settingsScrollviewContent

        includeAudioSetting = binding.includeAudioSetting
        enableEISSetting = binding.enableEisSetting
        selfIlluminationSetting = binding.selfIlluminationSetting
        videoQualitySetting = binding.videoQualitySetting
        timerSetting = binding.timerSetting

        includeAudioToggle = binding.includeAudioSwitch
        includeAudioToggle.setOnCheckedChangeListener { _, _ ->
            if (mActivity.videoCapturer.isRecording) {
                if (ActivityCompat.checkSelfPermission(mActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {

                    // Inform the user why enabling this option isn't possible
                    mActivity.showMessage(context.getString(R.string.audio_permission_failed_in_recording))

                    // Ensure the option is visually off
                    includeAudioToggle.isChecked = false
                    return@setOnCheckedChangeListener
                }

                if (!mActivity.videoCapturer.includeAudio) {
                    mActivity.showMessage("Enabling audio while recording is not currently supported when it was disabled at the start")
                    includeAudioToggle.isChecked = false
                    return@setOnCheckedChangeListener
                }

                if  (includeAudioToggle.isChecked) {
                    mActivity.videoCapturer.unmuteRecording()
                } else {
                    mActivity.videoCapturer.muteRecording()
                }
            }
        }

        includeAudioToggle.setOnClickListener {
            mActivity.micOffIcon.visibility = if (includeAudioToggle.isChecked) {
                View.GONE
            } else {
                View.VISIBLE
            }

            camConfig.includeAudio = includeAudioToggle.isChecked
        }
        binding.includeAudioSwitchContainer.setOnTouchListener { _, event ->
            event.setLocation(0f, 0f)
            includeAudioToggle.dispatchTouchEvent(event)
            true
        }

        enableEISToggle = binding.enableEisSwitch
        enableEISToggle.setOnCheckedChangeListener { _, isChecked ->
            camConfig.enableEIS = isChecked
            camConfig.startCamera(true)
        }
        binding.enableEisSwitchContainer.setOnTouchListener { _, event ->
            event.setLocation(0f, 0f)
            enableEISToggle.dispatchTouchEvent(event)
            true
        }

        window?.attributes?.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

        var backgroundColor = ContextCompat.getColor(context, android.R.color.black)
        backgroundColor = ColorUtils.setAlphaComponent(backgroundColor, 150)
        val settingsDialogBackgroundDrawable =
            ContextCompat.getDrawable(context, R.drawable.settings_bg)
        settingsDialogBackgroundDrawable?.setTint(backgroundColor)
        binding.settingsDialog.background = settingsDialogBackgroundDrawable

        val moreSettingsBackgroundDrawable =
            ContextCompat.getDrawable(context, R.drawable.settings_bg)
        moreSettingsBackgroundDrawable?.setTint(backgroundColor)
        binding.moreSettings.background = moreSettingsBackgroundDrawable
    }

    /**
     * The preview's rectangle, in the dialog window's coordinates. The panel is centred within
     * the preview, and reading the preview is what keeps that true at any window size and aspect
     * ratio — recomputing the geometry here would only be a second copy of it, free to disagree.
     */
    private fun previewRegion(): Rect? {
        val preview = mActivity.previewView
        if (preview.width == 0 || preview.height == 0) {
            return null
        }

        val previewLocation = IntArray(2)
        preview.getLocationOnScreen(previewLocation)

        val rootLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)

        val left = previewLocation[0] - rootLocation[0]
        val top = previewLocation[1] - rootLocation[1]

        return Rect(left, top, left + preview.width, top + preview.height)
    }

    /**
     * Moves the panel onto the preview whenever the preview is not where it was last sized for.
     * The activity declares orientation as a config change it handles itself rather than being
     * recreated, so nothing else tells the panel it has been rotated — and it can be rotated, or
     * have its aspect ratio toggled, while it is open rather than between [show]s.
     */
    private fun updatePanelRegion(): Boolean {
        val region = previewRegion() ?: return true
        if (region == sizedForRegion) {
            return true
        }

        sizedForRegion.set(region)

        settingsFrame.layoutParams = (settingsFrame.layoutParams as FrameLayout.LayoutParams)
            .also {
                it.gravity = Gravity.TOP or Gravity.START
                it.leftMargin = region.left
                it.topMargin = region.top
                it.width = region.width()
                it.height = region.height()
            }

        // The list is capped against the region, so it has to be measured against the new one too.
        resize()

        // Drop the frame instead of drawing the panel where it does not belong: the new bounds
        // only take effect in the traversal the layout params above schedule.
        return false
    }

    private fun resize() {
        mScrollViewContent.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {

                mScrollViewContent.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val settingsDialogHorizontalMargin =
                    mActivity.resources.getDimensionPixelSize(R.dimen.settings_dialog_horizontal_margin)
                val moreSettingsButtonTopPadding =
                    (8 * mActivity.resources.displayMetrics.density).toInt()
                val totalDialogHeight = moreSettingsButton.height + moreSettingsButtonTopPadding +
                        dialog.height
                val availableWidth = dialog.width - (settingsDialogHorizontalMargin * 4)
                val regionHeight = previewRegion()?.height() ?: binding.root.measuredHeight
                val availableHeight = availableWidth.coerceAtMost(regionHeight) -
                        (totalDialogHeight - mScrollView.height)

                val height = if (mScrollViewContent.height < mScrollView.height) {
                    mScrollViewContent.height
                } else {
                    max(
                        mScrollView.height.coerceAtMost(availableHeight),
                        mScrollViewContent.height.coerceAtMost(availableHeight),
                    )
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    height,
                )

                mScrollView.layoutParams = lp
            }
        })
    }

    private fun reloadPhotoFormats() {
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

    fun showOnlyRelevantSettings() {
        reloadPhotoFormats()
        val qualityLabel = getString(if (camConfig.activePhotoOutputFormat == PhotoOutputFormat.JPEG &&
            camConfig.enhancedPhotoAvailable) R.string.enhanced_quality else R.string.prioritize_capture_quality)
        binding.captureQualityLabel.text = qualityLabel
        binding.waitForFocusLockSwitch.contentDescription = qualityLabel
        binding.aspectRatioToggle.isEnabled = !camConfig.softwareNight &&
            camConfig.activePhotoOutputFormat == PhotoOutputFormat.JPEG
        binding.flashToggleOption.isEnabled = !camConfig.softwareNight && !camConfig.softwarePhoto
        binding.torchToggleOption.isEnabled = !camConfig.softwareNight && !camConfig.softwarePhoto
        binding.enhancedPhotoInfo.visibility = if (camConfig.currentMode == CameraMode.CAMERA &&
            !camConfig.isVideoMode && camConfig.activePhotoOutputFormat == PhotoOutputFormat.JPEG &&
            camConfig.enhancedPhotoAvailable) View.VISIBLE else View.GONE
        binding.waitForFocusLockSetting.visibility =
            if (camConfig.currentMode == CameraMode.CAMERA && !camConfig.isVideoMode) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (camConfig.isVideoMode) {
            includeAudioSetting.visibility = if (camConfig.currentMode == CameraMode.SLOW_MOTION)
                View.GONE else View.VISIBLE
            videoQualitySetting.visibility = View.VISIBLE
            binding.videoFrameRateSetting.visibility = View.VISIBLE
            enableEISSetting.visibility = if (camConfig.canApplyVideoStabilization()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        } else {
            includeAudioSetting.visibility = View.GONE
            enableEISSetting.visibility = View.GONE
            videoQualitySetting.visibility = View.GONE
            binding.videoFrameRateSetting.visibility = View.GONE
        }

        selfIlluminationSetting.visibility =
            if (camConfig.lensFacing == CameraSelector.LENS_FACING_FRONT) {
                View.VISIBLE
            } else {
                View.GONE
            }

        timerSetting.visibility = if (camConfig.isVideoMode) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }


    fun updateFocusTimeout(selectedOption: String) {

        if (selectedOption == timeOptions[0]) {
            camConfig.focusTimeout = 0
        } else {

            try {
                val durS = selectedOption.substring(0, selectedOption.length - 1)
                val dur = durS.toLong()

                camConfig.focusTimeout = dur

            } catch (exception: Exception) {

                mActivity.showMessage(
                    getString(R.string.unexpected_error_while_setting_focus_timeout)
                )

            }
        }

        focusTimeoutSpinner.setSelection(timeOptions.indexOf(selectedOption), false)
    }

    private fun updateTimerDuration(duration: Int) {
        mActivity.timerDuration = duration
        mActivity.updateSelfTimerBadge()
        // commonPref rather than modePref: the self-timer is not per-mode, and modePref is not
        // assigned until the camera starts, which happens after this dialog is built.
        camConfig.commonPref.edit()
            .putInt(CamConfig.SettingValues.Key.SELF_TIMER_DURATION, duration)
            .apply()
    }

    private fun restoreTimerDuration() {
        val duration = camConfig.commonPref.getInt(
            CamConfig.SettingValues.Key.SELF_TIMER_DURATION,
            CamConfig.SettingValues.Default.SELF_TIMER_DURATION
        )
        // Apply directly: Spinner.setSelection() only posts its selection callback, so the duration
        // would otherwise stay unset for a looper pass.
        updateTimerDuration(duration)

        val option = if (duration == 0) timeOptions[0] else "${duration}s"
        timerSpinner.setSelection(timeOptions.indexOf(option).coerceAtLeast(0), false)
    }

    fun updateVideoQuality(choice: String, resCam: Boolean = true) {

        val quality = titleToQuality(choice)

        if (quality == camConfig.videoQuality) return

        camConfig.videoQuality = quality

        if (resCam) {
            camConfig.startCamera(true)
        } else {
            videoQualitySpinner.setSelection(getAvailableQTitles().indexOf(choice))

        }
    }

    fun titleToQuality(title: String): Quality {
        return when (title) {
            "2160p (UHD)", getString(R.string.video_quality_uhd) -> Quality.UHD
            "1080p (FHD)" -> Quality.FHD
            "720p (HD)" -> Quality.HD
            "480p (SD)" -> Quality.SD
            else -> {
                Log.e("TAG", "Unknown quality: $title")
                Quality.SD
            }
        }
    }

    private var wasSelfIlluminationOn = false

    fun selfIllumination() {

        if (camConfig.selfIlluminate) {

            val colorFrom: Int = Color.BLACK
            val colorTo: Int = mActivity.getColor(R.color.self_illumination_light)

            val colorAnimation1 = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
            colorAnimation1.duration = 300
            colorAnimation1.addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                mActivity.previewView.setBackgroundColor(color)
                mActivity.rootView.setBackgroundColor(color)
                mActivity.bottomOverlay.setBackgroundColor(color)
            }

            val colorAnimation2 = ValueAnimator.ofObject(ArgbEvaluator(), Color.WHITE, Color.BLACK)
            colorAnimation2.duration = 300

            val selectedTextColor =
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
            val colorAnimation3 = ValueAnimator.ofObject(ArgbEvaluator(), selectedTextColor, Color.WHITE)
            colorAnimation3.duration = 300

            var currentUnselectedColor = Color.WHITE
            colorAnimation2.addUpdateListener { animator ->
                currentUnselectedColor = animator.animatedValue as Int
            }
            colorAnimation3.addUpdateListener { animator ->
                mActivity.tabLayout.setTabTextColors(
                    currentUnselectedColor,
                    animator.animatedValue as Int
                )
            }

            val colorAnimation4 =
                ValueAnimator.ofObject(ArgbEvaluator(), tabSelectedColor, Color.BLACK)
            colorAnimation4.duration = 300
            colorAnimation4.addUpdateListener { animator ->
                mActivity.tabLayout.setSelectedTabIndicatorColor(animator.animatedValue as Int)
            }

            colorAnimation1.start()
            colorAnimation2.start()
            colorAnimation3.start()
            colorAnimation4.start()

            setBrightness(1f)

        } else if (wasSelfIlluminationOn) {

            val colorFrom: Int = mActivity.getColor(R.color.self_illumination_light)
            val colorTo: Int = Color.BLACK

            val colorAnimation1 = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
            colorAnimation1.duration = 300
            colorAnimation1.addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                mActivity.previewView.setBackgroundColor(color)
                mActivity.rootView.setBackgroundColor(color)
                mActivity.bottomOverlay.setBackgroundColor(color)
            }

            val colorAnimation2 = ValueAnimator.ofObject(ArgbEvaluator(), Color.BLACK, Color.WHITE)
            colorAnimation2.duration = 300

            val selectedTextColor =
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimary)
            val colorAnimation3 = ValueAnimator.ofObject(ArgbEvaluator(), Color.WHITE, selectedTextColor)
            colorAnimation3.duration = 300

            var currentUnselectedTextColor = Color.BLACK
            colorAnimation2.addUpdateListener { animator ->
                currentUnselectedTextColor = animator.animatedValue as Int
            }
            colorAnimation3.addUpdateListener { animator ->
                mActivity.tabLayout.setTabTextColors(
                    currentUnselectedTextColor,
                    animator.animatedValue as Int
                )
            }

            val colorAnimation4 = ValueAnimator.ofObject(ArgbEvaluator(), Color.BLACK, tabSelectedColor)
            colorAnimation4.duration = 300
            colorAnimation4.addUpdateListener { animator ->
                mActivity.tabLayout.setSelectedTabIndicatorColor(animator.animatedValue as Int)
            }

            colorAnimation1.start()
            colorAnimation2.start()
            colorAnimation3.start()
            colorAnimation4.start()

            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }

        wasSelfIlluminationOn = camConfig.selfIlluminate
    }

    private val slideDownAnimation: Animation by lazy {
        val anim = AnimationUtils.loadAnimation(
            mActivity,
            R.anim.slide_down
        )

        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(p0: Animation?) {}

            override fun onAnimationEnd(p0: Animation?) {
                moreSettingsButton.visibility = View.VISIBLE
            }

            override fun onAnimationRepeat(p0: Animation?) {}

        })

        anim
    }

    val dismissHandler = Handler(Looper.myLooper()!!)
    val dismissCallback = Runnable {
        dismiss()
    }

    private val slideUpAnimation: Animation by lazy {
        val anim = AnimationUtils.loadAnimation(
            mActivity,
            R.anim.slide_up
        )

        anim.setAnimationListener(
            object : Animation.AnimationListener {

                override fun onAnimationStart(p0: Animation?) {
                    moreSettingsButton.visibility = View.INVISIBLE
                }

                override fun onAnimationEnd(p0: Animation?) {
                    dismissHandler.removeCallbacks(dismissCallback)
                    dismissHandler.post(
                        dismissCallback
                    )
                }

                override fun onAnimationRepeat(p0: Animation?) {}

            }
        )

        anim
    }

    private fun setBrightness(brightness: Float) {

        val layout = mActivity.window.attributes
        layout.screenBrightness = brightness
        mActivity.window.attributes = layout

        window?.let {
            val dialogLayout = it.attributes
            dialogLayout.screenBrightness = brightness
            it.attributes = dialogLayout
        }

    }

    private fun slideDialogDown() {
        settingsFrame.startAnimation(slideDownAnimation)
    }

    fun slideDialogUp() {
        // Restarting the animation would bounce the panel back into view and postpone the dismissal
        // its end schedules, so ignore any further request to close while it plays out.
        if (slideUpAnimation.hasStarted() && !slideUpAnimation.hasEnded()) {
            return
        }
        settingsFrame.startAnimation(slideUpAnimation)
    }

    private fun getAvailableQualities(): List<Quality> {
        val cameraInfo = camConfig.camera?.cameraInfo ?: return Collections.emptyList()
        return if(camConfig.currentMode==app.grapheneos.camera.CameraMode.SLOW_MOTION)
            camConfig.eligibleSlowMotionQualities(cameraInfo)
        else Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)
    }

    private fun getAvailableQTitles(): List<String> {
        val titles = arrayListOf<String>()

        getAvailableQualities().forEach {
            titles.add(getTitleFor(it))
        }

        return titles
    }

    private fun getTitleFor(quality: Quality): String {
        return when (quality) {
            Quality.UHD -> getString(R.string.video_quality_uhd)
            Quality.FHD -> "1080p (FHD)"
            Quality.HD -> "720p (HD)"
            Quality.SD -> "480p (SD)"
            else -> {
                Log.i("TAG", "Unknown constant: $quality")
                "Unknown"
            }
        }
    }

    fun updateGridToggleUI() {
        mActivity.previewGrid.postInvalidate()
        // The description has to travel with the drawable: this control cycles through four
        // states, so a fixed "Grid Toggle" label left a screen reader unable to report any of them
        val (icon, description) = when (camConfig.gridType) {
            CamConfig.GridType.NONE -> R.drawable.grid_off_circle to R.string.grid_off
            CamConfig.GridType.THREE_BY_THREE -> R.drawable.grid_3x3_circle to R.string.grid_3x3
            CamConfig.GridType.FOUR_BY_FOUR -> R.drawable.grid_4x4_circle to R.string.grid_4x4
            CamConfig.GridType.GOLDEN_RATIO ->
                R.drawable.grid_goldenratio_circle to R.string.grid_golden_ratio
        }
        gridToggle.setImageResource(icon)
        gridToggle.contentDescription = mActivity.getString(description)
    }

    fun updateFlashMode() {
        val (icon, description) = if (camConfig.isFlashAvailable) {
            when (camConfig.flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.flash_on_circle to R.string.flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.flash_auto_circle to R.string.flash_auto
                else -> R.drawable.flash_off_circle to R.string.flash_off
            }
        } else {
            R.drawable.flash_off_circle to R.string.flash_off
        }
        flashToggle.setImageResource(icon)
        flashToggle.contentDescription = mActivity.getString(description)
    }

    /**
     * The ratio itself is the toggle's on/off text, which its content description hides from
     * accessibility services: announce it as the toggle's state instead.
     */
    private fun updateAspectRatioToggle(is16by9: Boolean) {
        aRToggle.isChecked = is16by9
        val ratio = if (is16by9) R.string.aspect_ratio_16_9 else R.string.aspect_ratio_4_3
        ViewCompat.setStateDescription(aRToggle, mActivity.getString(ratio))
    }

    override fun show() {

        this.resize()

        updateFlashMode()

        if (camConfig.isVideoMode) {
            updateAspectRatioToggle(is16by9 = true)
        } else {
            updateAspectRatioToggle(camConfig.aspectRatio == AspectRatio.RATIO_16_9)
        }

        torchToggle.isChecked = camConfig.isTorchOn

        updateGridToggleUI()

        mActivity.settingsIcon.visibility = View.INVISIBLE
        mActivity.hideCaptureHints()
        super.show()
        backCallback.isEnabled = true

        slideDialogDown()
    }

    override fun dismiss() {
        backCallback.isEnabled = false
        super.dismiss()
    }

    fun reloadQualities() {

        val titles = getAvailableQTitles()

        vQAdapter = ArrayAdapter<String>(
            mActivity,
            android.R.layout.simple_spinner_item,
            titles
        )

        vQAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        val selection = if (camConfig.videoQuality == Quality.HIGHEST) 0 else
            titles.indexOf(getTitleFor(camConfig.videoQuality)).coerceAtLeast(0)
        displayedVideoQuality = titles.getOrNull(selection)?.let { titleToQuality(it) }
        updatingVideoChoices = true
        videoQualitySpinner.adapter = vQAdapter
        if (titles.isNotEmpty()) videoQualitySpinner.setSelection(selection, false)
        updatingVideoChoices = false
    }

    fun reloadFrameRates() {
        frameRateChoices = (if(camConfig.currentMode==app.grapheneos.camera.CameraMode.SLOW_MOTION) emptyList() else listOf(null)) + camConfig.availableVideoFrameRates
        val labels = frameRateChoices.map { rate ->
            when {
                rate == null -> getString(R.string.video_frame_rate_auto)
                rate in camConfig.slowMotionVideoFrameRates ->
                    mActivity.getString(R.string.video_frame_rate_slow_motion, rate.upper)
                rate.lower == rate.upper -> mActivity.getString(R.string.video_frame_rate_fixed, rate.lower)
                else -> mActivity.getString(R.string.video_frame_rate_range, rate.lower, rate.upper)
            }
        }
        val adapter = ArrayAdapter(mActivity, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        updatingVideoChoices = true
        videoFrameRateSpinner.adapter = adapter
        videoFrameRateSpinner.setSelection(
            frameRateChoices.indexOf(camConfig.selectedVideoFrameRate).coerceAtLeast(0), false)
        videoFrameRateSpinner.isEnabled = !mActivity.videoCapturer.isRecording && frameRateChoices.size > 1
        updatingVideoChoices = false
    }
}
