package app.grapheneos.camera.capturer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Bounded RAW highlight metering. Probe samples are never saved or modified. */
internal object RawHighlightPolicy {
    data class Reading(val highlight: Float, val clippedFraction: Float, val scale: Float)
    data class Setting(val exposureNs: Long, val iso: Int)

    fun measure(buffer: ByteBuffer, width: Int, height: Int, stride: Int,
                calibration: FloatArray, zoom: Float=1f, aspect: Float=4f/3): Reading {
        require(width in 8..8192 && height in 8..8192 && width.toLong()*height<=16_000_000 &&
            width%2==0 && height%2==0 && stride in width*2..width*2+4096 && stride%2==0)
        require(calibration.size==13 && calibration[4].isFinite() && calibration[4] in 1f..65535f)
        require((0..3).all {calibration[it].isFinite() && calibration[it] in 0f..calibration[4]-1})
        require(buffer.remaining().toLong()>=stride.toLong()*(height-1)+width*2)
        require(zoom.isFinite() && zoom in 1f..100f && aspect.isFinite() && aspect in .5f..2f)
        val raw=buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val histogram=IntArray(1024)
        var count=0;var clipped=0
        // Sample all four CFA phases in a regular, bounded grid. Histogram tails
        // ignore isolated defects and permit a small number of specular points.
        val cropWidth=(minOf(width.toFloat(),height*aspect)/zoom).toInt().coerceAtLeast(2)/2*2
        val cropHeight=(minOf(height.toFloat(),width/aspect)/zoom).toInt().coerceAtLeast(2)/2*2
        val left=(width-cropWidth)/4*2;val top=(height-cropHeight)/4*2
        val stepX=maxOf(2,(cropWidth/96)/2*2);val stepY=maxOf(2,(cropHeight/72)/2*2)
        for(y in top until top+cropHeight-1 step stepY) for(x in left until left+cropWidth-1 step stepX) {
            for(dy in 0..1) for(dx in 0..1) {
                val black=calibration[dy*2+dx]
                val sample=raw.getShort((y+dy)*stride+(x+dx)*2).toInt() and 65535
                val value=((sample-black)/(calibration[4]-black)).coerceIn(0f,1f)
                histogram[(value*1023).roundToInt()]++;count++
                if(value>=.98f) clipped++
            }
        }
        val tail=(count*.995).roundToInt().coerceAtMost(count)
        var cumulative=0;var bin=1023
        for(i in histogram.indices) {cumulative+=histogram[i];if(cumulative>=tail) {bin=i;break}}
        val highlight=bin/1023f;val fraction=clipped.toFloat()/count
        // A clipped value gives only a lower bound on scene radiance. Take one
        // stop of headroom instead of pretending to reconstruct missing data.
        val scale=when {
            fraction>=.005f -> .5f
            highlight>.92f -> (.90f/highlight).coerceIn(.5f,1f)
            else -> 1f
        }
        return Reading(highlight,fraction,scale)
    }

    fun choose(exposureNs: Long, iso: Int, minExposureNs: Long, minIso: Int, scale: Float): Setting {
        require(exposureNs>=minExposureNs && minExposureNs>0 && iso>=minIso && minIso>0 &&
            scale.isFinite() && scale in .25f..1f)
        // Reduce analog sensitivity first to preserve collected photons. Once
        // minimum ISO is reached, shorten exposure for the remaining headroom.
        val target=exposureNs.toDouble()*iso*scale
        val nextIso=(iso*scale).roundToInt().coerceIn(minIso,iso)
        val nextExposure=(target/nextIso).toLong().coerceIn(minExposureNs,exposureNs)
        return Setting(nextExposure,nextIso)
    }
}
