package app.grapheneos.camera

import android.app.Activity
import android.graphics.*
import android.os.*
import android.view.Gravity
import android.view.PixelCopy
import android.widget.FrameLayout
import android.widget.ImageView
import app.grapheneos.camera.ui.PortraitRenderShader
import org.json.JSONObject
import java.io.File

/** DUMP-only generated-pattern test. No camera frames or file/URI input. */
class PreviewShaderTestActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val kind=intent.getStringExtra("case") ?: "raw"
        require(kind in setOf("raw","squared","gate","quarter","half"))
        val size=minOf(768,resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels/2)
        val source=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(154,148,133))}
        val values=IntArray(512*512) {index ->
            val x=index%512;val y=index/512
            if(kind=="raw") when {
                y<256 && x<256->Color.RED
                y<256->Color.GREEN
                x<256->Color.BLUE
                else->Color.rgb(64,128,192)
            } else {
                val amount=x/511f*(if(kind=="quarter").24f else if(kind=="half").49f else 1f);val weight=if(kind=="squared")amount*amount else if((amount*255).toInt()>0)1f else 0f
                -0x1000000 or ((amount*255).toInt() shl 8) or (weight*255).toInt()
            }
        }
        val control=Bitmap.createBitmap(values,512,512,Bitmap.Config.ARGB_8888)
        val image=ImageView(this).apply {scaleType=ImageView.ScaleType.FIT_XY;setImageBitmap(source)}
        val root=FrameLayout(this).apply {setBackgroundColor(Color.BLACK);addView(image,FrameLayout.LayoutParams(size,size,Gravity.CENTER))}
        setContentView(root)
        if(kind=="raw") {
            val shader=RuntimeShader("uniform shader image; uniform shader data; half4 main(float2 p) {return half4(data.eval(p).rgb,image.eval(p).a);}")
            val data=BitmapShader(control,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {setLocalMatrix(Matrix().apply {setScale(size/512f,size/512f)})}
            shader.setInputBuffer("data",data);image.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader,"image"))
        } else {
            val shader=PortraitRenderShader();shader.mask(control,size.toFloat(),size.toFloat(),true,values.maxOf {it ushr 8 and 255}/255f)
            shader.strength(20,size.toFloat(),size.toFloat());image.setRenderEffect(shader.effect())
        }
        image.postDelayed({
            val location=IntArray(2);image.getLocationInWindow(location)
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            PixelCopy.request(window,Rect(location[0],location[1],location[0]+size,location[1]+size),bitmap,{result ->
                val directory=File(filesDir,"processing-test/shader").apply {mkdirs()}
                if(result==PixelCopy.SUCCESS)File(directory,kind+".png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                File(directory,kind+".json").writeText(JSONObject().put("status",result).put("size",size).put("case",kind).toString())
                bitmap.recycle();finish()
            },handler)
        },800)
    }
}
