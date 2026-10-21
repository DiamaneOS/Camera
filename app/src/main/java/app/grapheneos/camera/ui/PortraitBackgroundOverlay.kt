package app.grapheneos.camera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.roundToInt

/** Draw only the blurred background over the original, full-resolution camera image. */
@RequiresApi(33)
internal class PortraitBackgroundOverlay(context:Context,private val source:PreviewView,
    private val unavailable:()->Unit):View(context),AutoCloseable {
    private val node=RenderNode("Portrait background")
    private val shader=PortraitRenderShader()
    private val parentView=source.parent as ConstraintLayout
    private var mask:Bitmap?=null
    private var controls=false
    private var maximum=1f
    private var strength=0
    private var wanted=false
    private var active=false
    private var closed=false
    private var framePosted=false
    private var lastFrame=0L
    private var renderWidth=0
    private var renderHeight=0
    private var recordedChild:View?=null
    private val frames=object:Choreographer.FrameCallback {
        override fun doFrame(time:Long) {
            framePosted=false
            if(!active || closed || !isAttachedToWindow)return
            if(time-lastFrame>=33_000_000L) {lastFrame=time;invalidate()}
            postFrame()
        }
    }
    init {
        isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility=INVISIBLE
        layoutParams=ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=source.id;bottomToBottom=source.id;startToStart=source.id;endToEnd=source.id
        }
        // A sibling avoids recording this overlay recursively when source.draw() is called.
        parentView.addView(this,parentView.indexOfChild(source)+1)
    }
    fun mask(bitmap:Bitmap,depthControls:Boolean,maxBlurAmount:Float) {
        mask=bitmap;controls=depthControls;maximum=maxBlurAmount;updateEffect()
    }
    fun effect(radius:Int,enabled:Boolean) {
        strength=radius;wanted=enabled;updateEffect()
    }
    private fun updateEffect() {
        if(closed)return
        val bitmap=mask
        val ready=wanted && strength>0 && bitmap!=null && !bitmap.isRecycled && source.width>0 && source.height>0
        var effect:android.graphics.RenderEffect?=null
        if(ready) {
            // The weakest blur retains full sampling. Larger blurs are band-limited
            // enough for a smaller background layer; the foreground is never resized.
            val scale=if(strength>=4).5f else 1f
            val nextWidth=maxOf(2,(source.width*scale).roundToInt())
            val nextHeight=maxOf(2,(source.height*scale).roundToInt())
            if(nextWidth!=renderWidth || nextHeight!=renderHeight)node.discardDisplayList()
            renderWidth=nextWidth
            renderHeight=nextHeight
            node.setPosition(0,0,renderWidth,renderHeight)
            shader.mask(checkNotNull(bitmap),renderWidth.toFloat(),renderHeight.toFloat(),controls,maximum)
            shader.strength(strength,renderWidth.toFloat(),renderHeight.toFloat())
            effect=shader.effect(backgroundOnly=true)
        }
        node.setRenderEffect(effect)
        active=ready && effect!=null
        visibility=if(active)VISIBLE else INVISIBLE
        if(active) {invalidate();postFrame()} else stopFrames()
    }
    private fun postFrame() {
        if(!framePosted && active && !closed && isAttachedToWindow) {
            framePosted=true;Choreographer.getInstance().postFrameCallback(frames)
        }
    }
    private fun stopFrames() {
        Choreographer.getInstance().removeFrameCallback(frames);framePosted=false
    }
    override fun onDraw(canvas:Canvas) {
        if(!active || closed)return
        if(!canvas.isHardwareAccelerated) {fail();return}
        try {
            // Child RenderNodes retain their live texture content and transforms.
            // Reuse that display list rather than traversing PreviewView on every
            // display frame. A replacement child or new geometry records it again.
            val child=source.getChildAt(0)
            if(!node.hasDisplayList() || child!==recordedChild) {
                val recording=node.beginRecording(renderWidth,renderHeight)
                try {
                    recording.scale(renderWidth.toFloat()/source.width,renderHeight.toFloat()/source.height)
                    source.draw(recording)
                } finally {node.endRecording()}
                recordedChild=child
            }
            val saved=canvas.save()
            try {
                canvas.scale(width.toFloat()/renderWidth,height.toFloat()/renderHeight)
                canvas.drawRenderNode(node)
            } finally {canvas.restoreToCount(saved)}
        } catch(_:RuntimeException) {fail()}
    }
    private fun fail() {
        active=false;wanted=false;stopFrames()
        post {if(!closed) {visibility=INVISIBLE;unavailable()}}
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {super.onSizeChanged(w,h,oldw,oldh);node.discardDisplayList();updateEffect()}
    override fun onAttachedToWindow() {super.onAttachedToWindow();postFrame()}
    override fun onDetachedFromWindow() {stopFrames();super.onDetachedFromWindow()}
    override fun close() {
        if(closed)return
        closed=true;active=false;stopFrames();node.setRenderEffect(null);node.discardDisplayList();mask=null;recordedChild=null
        parentView.removeView(this)
    }
}
