package app.grapheneos.camera.capturer

/** A bounded luminance curve lifts dark Night references while protecting black and white. */
internal object NightPreviewTonePolicy {
    // Below this level 8-bit preview chroma is dominated by quantization/noise.
    // It is not a reliable target for correcting a higher-bit-depth RAW burst.
    fun reliableColor(mean: Float)=mean.isFinite() && mean in .04f..1f
    fun gain(mean: Float): Float {
        if(!mean.isFinite() || mean !in 0f..1f || mean>=.24f) return 1f
        val value=mean.coerceAtLeast(.001f)
        return (.24f*(1-value)/(value*(1-.24f))).coerceIn(1f,12f)
    }
    fun lift(pixel: Int,gain: Float): Int {
        if(gain<=1f) return pixel
        val r=pixel shr 16 and 255;val g=pixel shr 8 and 255;val b=pixel and 255
        val y=(r+2*g+b)/1020f
        if(y<=0) return pixel
        val mapped=gain*y/(1+(gain-1)*y)
        val scale=minOf(mapped/y,255f/maxOf(r,g,b))
        return -0x1000000 or ((r*scale).toInt().coerceIn(0,255) shl 16) or
            ((g*scale).toInt().coerceIn(0,255) shl 8) or (b*scale).toInt().coerceIn(0,255)
    }
}
