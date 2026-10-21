package app.grapheneos.camera.ui

import android.graphics.*
import androidx.annotation.RequiresApi

/** Normalized background convolution, with depth-dependent blur levels when available. */
@RequiresApi(33)
internal class PortraitRenderShader {
    private val foreground=RuntimeShader("""
        uniform shader image; uniform shader matte; uniform float controls;
        half4 main(float2 p) {
            half4 c=matte.eval(p);float alpha=clamp(c.r,0.,1.);
            float amount=controls>.5?clamp(c.g,0.,1.):1.;
            return image.eval(p)*(alpha+(1.-alpha)*max(1.-4.*amount,0.));
        }
    """.trimIndent())
    private val extract=RuntimeShader("""
        uniform shader image; uniform shader matte; uniform float controls;
        half4 main(float2 p) {
            half4 c=matte.eval(p);
            float weight=controls>.5?clamp(c.b,0.,1.):1.-clamp(c.r,0.,1.);
            return image.eval(p)*weight;
        }
    """.trimIndent())
    private fun normalizer(level:Int)=RuntimeShader("""
        uniform shader image; uniform shader matte; uniform float controls;
        half4 main(float2 p) {
            half4 blurred=image.eval(p);half4 c=matte.eval(p);
            float a=controls>.5?clamp(c.g,0.,1.):1.;
            float weight;
            if($level==0) weight=a<=.25?4.*a:a<=.5?2.-4.*a:0.;
            else if($level==1) weight=a<=.25?0.:a<=.5?4.*a-1.:2.-2.*a;
            else weight=max(2.*a-1.,0.);
            float alpha=(1.-clamp(c.r,0.,1.))*clamp(weight,0.,1.);
            return half4(blurred.rgb/max(blurred.a,.0001)*alpha,alpha);
        }
    """.trimIndent())
    private val normalize=List(3) {normalizer(it)}
    private var radius=0f
    private var depthControls=false
    private var maximumAmount=1f
    init {mask(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),1f,1f)}
    fun mask(bitmap:Bitmap,width:Float,height:Float,controls:Boolean=false,maxBlurAmount:Float=1f) {
        require(maxBlurAmount.isFinite() && maxBlurAmount in 0f..1f)
        depthControls=controls;maximumAmount=if(controls)maxBlurAmount else 1f
        val mapped=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {setScale(width.coerceAtLeast(1f)/bitmap.width,height.coerceAtLeast(1f)/bitmap.height)})
            filterMode=BitmapShader.FILTER_MODE_LINEAR
        }
        (listOf(foreground,extract)+normalize).forEach {
            // Matte, blur radius and convolution weights are data, not display colours.
            it.setInputBuffer("matte",mapped);it.setFloatUniform("controls",if(controls)1f else 0f)
        }
    }
    fun strength(value:Int,width:Float,height:Float) {radius=value*maxOf(width,height)/1024f}
    fun effect(backgroundOnly:Boolean=false):RenderEffect? {
        if(radius<.1f || depthControls && maximumAmount==0f)return null
        val extracted=RenderEffect.createRuntimeShaderEffect(extract,"image")
        fun blurred(scale:Float,level:Int)=RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(normalize[level],"image"),
            RenderEffect.createBlurEffect(maxOf(.1f,radius*scale),maxOf(.1f,radius*scale),extracted,Shader.TileMode.CLAMP))
        // A bilinearly sampled control cannot exceed its texture maximum.
        // Levels with zero weight everywhere can be skipped without changing the image.
        val background=if(depthControls) {
            var result=blurred(.25f,0)
            if(maximumAmount>.25f)result=RenderEffect.createBlendModeEffect(result,blurred(.5f,1),BlendMode.PLUS)
            if(maximumAmount>.5f)result=RenderEffect.createBlendModeEffect(result,blurred(1f,2),BlendMode.PLUS)
            result
        } else blurred(1f,2)
        return if(backgroundOnly)background else RenderEffect.createBlendModeEffect(background,RenderEffect.createRuntimeShaderEffect(foreground,"image"),BlendMode.PLUS)
    }
}
