package app.grapheneos.camera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Native-rendered preview levels blend immediately; the saved renderer defines their appearance. */
class PortraitEditPreview(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {
    private var levels: Map<Int,Bitmap> = emptyMap()
    private var radius=8
    private val brush=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination=RectF()
    internal fun installLevels(rendered: Map<Int,Bitmap>): Boolean {levels=rendered;invalidate();return true}
    fun strength(value: Int) {radius=value.coerceIn(0,20);invalidate()}
    fun clearPortrait() {levels=emptyMap()}
    override fun onDraw(canvas: Canvas) {
        if(levels.isEmpty()) {super.onDraw(canvas);return}
        var lower=0
        var upper=Int.MAX_VALUE
        for(level in levels.keys) {
            if(level<=radius && level>lower)lower=level
            if(level>=radius && level<upper)upper=level
        }
        if(upper==Int.MAX_VALUE)upper=lower
        val low=checkNotNull(levels[lower]);val high=checkNotNull(levels[upper])
        val scale=minOf(width.toFloat()/low.width,height.toFloat()/low.height)
        val w=low.width*scale;val h=low.height*scale
        destination.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        brush.alpha=255;canvas.drawBitmap(low,null,destination,brush)
        if(upper>lower) {brush.alpha=(255f*(radius-lower)/(upper-lower)).toInt();canvas.drawBitmap(high,null,destination,brush);brush.alpha=255}
    }
}
