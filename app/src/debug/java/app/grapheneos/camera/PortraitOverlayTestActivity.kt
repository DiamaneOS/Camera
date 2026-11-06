package app.grapheneos.camera

import android.app.Activity
import android.graphics.*
import android.os.*
import android.view.View
import android.view.PixelCopy
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import app.grapheneos.camera.ui.PortraitBackgroundOverlay
import app.grapheneos.camera.ui.PortraitRenderShader
import org.json.JSONObject
import java.io.File

/** DUMP-only generated rendering test. No camera, external path, URI or user photo input. */
class PortraitOverlayTestActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var source:PreviewView
    private var overlay:PortraitBackgroundOverlay?=null
    private lateinit var directory:File
    private lateinit var input:Bitmap
    private lateinit var control:Bitmap
    private lateinit var imageView:ImageView
    private var radius=8
    private var size=0
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        radius=intent.getIntExtra("strength",8);require(radius==8 || radius==20)
        directory=File(filesDir,"processing-test/overlay-$radius").apply {mkdirs()}
        size=minOf(768,resources.displayMetrics.widthPixels-64,resources.displayMetrics.heightPixels-250)
        val values=IntArray(512*512) {i ->
            val x=i%512;val y=i/512
            if(x in 192 until 320 && y in 160 until 352) {
                if((x+y)%2==0)Color.rgb(30,220,240) else Color.rgb(220,30,100)
            } else Color.rgb(154,148,133)
        }
        input=Bitmap.createBitmap(values,512,512,Bitmap.Config.ARGB_8888)
        control=Bitmap.createBitmap(IntArray(512*512) {i ->
            val x=i%512;val y=i/512
            if(x in 192 until 320 && y in 160 until 352)0xffff0000.toInt() else 0xff00ffff.toInt()
        },512,512,Bitmap.Config.ARGB_8888)
        val root=ConstraintLayout(this).apply {setBackgroundColor(Color.BLACK)}
        source=PreviewView(this).apply {id=View.generateViewId();addView(ImageView(this@PortraitOverlayTestActivity).apply {
            imageView=this
            scaleType=ImageView.ScaleType.FIT_XY;setImageBitmap(input)
        },android.widget.FrameLayout.LayoutParams(-1,-1))}
        root.addView(source,ConstraintLayout.LayoutParams(size,size).apply {
            topToTop=ConstraintLayout.LayoutParams.PARENT_ID;bottomToBottom=ConstraintLayout.LayoutParams.PARENT_ID
            startToStart=ConstraintLayout.LayoutParams.PARENT_ID;endToEnd=ConstraintLayout.LayoutParams.PARENT_ID
        })
        setContentView(root)
        capture("base") {
            val reference=PortraitRenderShader();reference.mask(control,size.toFloat(),size.toFloat(),true,1f)
            reference.strength(radius,size.toFloat(),size.toFloat());source.setRenderEffect(reference.effect())
            capture("reference") {
                source.setRenderEffect(null)
                overlay=PortraitBackgroundOverlay(this,source) {complete("renderer-unavailable")}.also {
                    it.mask(control,true,1f);it.effect(radius,true)
                }
                capture("overlay") {
                    // A cached recording must still see a changing child's pixels.
                    val updated=input.copy(Bitmap.Config.ARGB_8888,true)
                    val canvas=Canvas(updated)
                    canvas.drawColor(Color.rgb(88,112,145),PorterDuff.Mode.SRC)
                    val paint=Paint().apply {color=Color.rgb(220,130,40)}
                    canvas.drawRect(192f,160f,320f,352f,paint)
                    imageView.setImageBitmap(updated)
                    capture("overlay-updated") {
                        overlay?.close();overlay=null
                        capture("base-updated") {
                            source.setRenderEffect(reference.effect())
                            capture("reference-updated") {complete("ok")}
                        }
                    }
                }
            }
        }
    }
    private fun capture(name:String,next:()->Unit) {
        handler.postDelayed({
            val xy=IntArray(2);source.getLocationInWindow(xy)
            val image=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            PixelCopy.request(window,Rect(xy[0],xy[1],xy[0]+size,xy[1]+size),image,{status ->
                if(status==PixelCopy.SUCCESS)File(directory,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
                if(status==PixelCopy.SUCCESS)next() else complete("pixel-copy-$status")
            },handler)
        },650)
    }
    private fun complete(status:String) {
        File(directory,"result.json").writeText(JSONObject().put("status",status).put("size",size).put("strength",radius).toString())
        overlay?.close();overlay=null;finish()
    }
    override fun onDestroy() {handler.removeCallbacksAndMessages(null);overlay?.close();overlay=null;super.onDestroy()}
}
