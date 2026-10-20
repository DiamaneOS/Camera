package app.grapheneos.camera.ui.seekbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import app.grapheneos.camera.ui.activities.MainActivity

/** A vertical zoom control whose geometry and range stay fixed for the whole gesture. */
class ZoomBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private lateinit var activity: MainActivity
    private val handler = Handler(Looper.getMainLooper())
    private val close = Runnable { hidePanel() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var minimum = 1f
    private var maximum = 1f
    private var value = 1f
    var isDragging = false
        private set
    val gestureZoom: Float? get() = value.takeIf { isDragging }
    init { isFocusable = true; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun setMainActivity(mainActivity: MainActivity) { activity = mainActivity }
    fun showPanel() {
        if (!::activity.isInitialized) return
        activity.zoomBarPanel.visibility = VISIBLE
        handler.removeCallbacks(close)
        if (!isDragging) handler.postDelayed(close, 2000)
    }
    internal fun hidePanel() {
        if (!isDragging && ::activity.isInitialized) activity.zoomBarPanel.visibility = GONE
    }
    fun updateThumb(shouldShowPanel: Boolean = true) {
        if (isDragging) return
        activity.camConfig.zoomState?.let { minimum = it.minZoomRatio; maximum = it.maxZoomRatio }
        value = activity.camConfig.displayZoom
        activity.updateLensControls()
        invalidate()
        if (shouldShowPanel) showPanel() else hidePanel()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((56*density).toInt(), widthMeasureSpec), resolveSize((250*density).toInt(),heightMeasureSpec))
    }
    private val endPadding get() = 20*density
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width/2f; val top = endPadding; val bottom = maxOf(top+1,height-endPadding)
        paint.color = 0x66ffffff; paint.strokeWidth = 2*density
        canvas.drawLine(x,top,x,bottom,paint)
        val y = bottom-ZoomScale.fractionOf(value,minimum,maximum)*(bottom-top)
        paint.color=Color.WHITE
        canvas.drawRoundRect(2*density,y-18*density,width-2*density,y+18*density,18*density,18*density,paint)
        paint.color=Color.DKGRAY;paint.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,14f,resources.displayMetrics);paint.textAlign=Paint.Align.CENTER
        val label=String.format("%.1f×",value)
        val available=(width-12*density).coerceAtLeast(1f)
        if(paint.measureText(label)>available) paint.textSize*=available/paint.measureText(label)
        canvas.drawText(label,x,y-(paint.ascent()+paint.descent())/2,paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !::activity.isInitialized || activity.imageCapturer.isTakingPicture) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val state=activity.camConfig.zoomState ?: return false
                minimum=state.minZoomRatio;maximum=state.maxZoomRatio;isDragging=true
                parent.requestDisallowInterceptTouchEvent(true);handler.removeCallbacks(close)
            }
            MotionEvent.ACTION_CANCEL -> { isDragging=false;parent.requestDisallowInterceptTouchEvent(false);updateThumb(false);return true }
            MotionEvent.ACTION_UP -> {
                isDragging=false;parent.requestDisallowInterceptTouchEvent(false)
                activity.camConfig.setZoomRatio(value);performClick();showPanel();return true
            }
        }
        if (isDragging) {
            val fraction=1-(event.y-endPadding)/maxOf(1f,height-2*endPadding)
            value=ZoomScale.ratioAt(fraction,minimum,maximum)
            activity.camConfig.setZoomRatio(value, gesture = true)
            activity.updateLensControls();invalidate()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.widget.SeekBar"
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,minimum,maximum,value)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    private fun step(direction: Int): Boolean {
        if(!isEnabled || !::activity.isInitialized || activity.imageCapturer.isTakingPicture) return false
        val state=activity.camConfig.zoomState ?: return false
        val fraction=ZoomScale.fractionOf(activity.camConfig.displayZoom,state.minZoomRatio,state.maxZoomRatio)
        activity.camConfig.setZoomRatio(ZoomScale.ratioAt(fraction+direction*.025f,state.minZoomRatio,state.maxZoomRatio))
        return true
    }
    override fun onKeyDown(keyCode: Int,event: android.view.KeyEvent): Boolean = when(keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP,android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
        android.view.KeyEvent.KEYCODE_DPAD_DOWN,android.view.KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
        else -> super.onKeyDown(keyCode,event)
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id) return step(1)
        if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id) return step(-1)
        if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id && arguments!=null) {
            activity.camConfig.setZoomRatio(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE));return true
        }
        return super.performAccessibilityAction(action,arguments)
    }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null);super.onDetachedFromWindow() }
}
