package app.grapheneos.camera.capturer

import kotlin.math.abs

internal data class PhotoResolution(val width:Int,val height:Int) {
    init {require(width in 1..32768 && height in 1..32768 && width.toLong()*height<=512_000_000)}
    val pixels:Long get()=width.toLong()*height
    val preference:String get()="${width}x${height}"
    companion object {
        fun parse(value:String?):PhotoResolution? {
            if(value==null || !value.matches(Regex("[0-9]{1,5}x[0-9]{1,5}")))return null
            val parts=value.split('x');val w=parts[0].toIntOrNull() ?: return null;val h=parts[1].toIntOrNull() ?: return null
            return try {PhotoResolution(w,h)} catch(_:IllegalArgumentException) {null}
        }
    }
}

/** A short list of real advertised sizes, never a synthesized megapixel target. */
internal object PhotoResolutionPolicy {
    fun advertised(sizes:List<PhotoResolution>,aspect:Double):List<PhotoResolution> {
        require(aspect.isFinite() && aspect in 1.0..2.0)
        val unique=sizes.distinct().sortedByDescending {it.pixels}
        val matching=unique.filter {abs(maxOf(it.width,it.height).toDouble()/minOf(it.width,it.height)-aspect)<.02}
        return matching.ifEmpty {unique}
    }
    fun choices(sizes:List<PhotoResolution>):List<PhotoResolution> {
        if(sizes.isEmpty())return emptyList()
        val largest=sizes.maxBy {it.pixels};val result=linkedSetOf(largest)
        for(target in listOf(8_000_000L,5_000_000L,3_000_000L,2_000_000L)) {
            if(target>=largest.pixels*.9)continue
            val closest=sizes.minBy {abs(it.pixels-target)}
            if(abs(closest.pixels-target)<=target*.2)result.add(closest)
        }
        return result.sortedByDescending {it.pixels}
    }
}
