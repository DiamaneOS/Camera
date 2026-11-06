package app.grapheneos.camera

import android.app.Activity
import android.opengl.EGL14 as Egl
import android.opengl.GLES30 as Gl
import android.opengl.Matrix
import android.os.Bundle
import app.grapheneos.camera.ui.TemporalPreviewFilter
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.abs

/** DUMP-only generated GPU checks. No camera, URI, pathname or external image input. */
class TemporalPreviewTestActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        thread(name="generated-preview-filter") {
            val started=android.os.SystemClock.elapsedRealtime()
            val result=try {exercise().put("status","ok")}
            catch(error:Exception) {JSONObject().put("status","failed").put("failure",error.javaClass.simpleName)
                .put("message",error.message)}
            result.put("elapsedMilliseconds",android.os.SystemClock.elapsedRealtime()-started)
            File(filesDir,"processing-test").mkdirs()
            File(filesDir,"processing-test/temporal-preview-generated.json").writeText(result.toString(2))
            runOnUiThread {finish()}
        }
    }

    private fun exercise():JSONObject {
        val retention=.65f
        val spatial=intent.getBooleanExtra("spatial",false)
        val spatialLuma=if(spatial)1f else 0f
        val display=Egl.eglGetDisplay(Egl.EGL_DEFAULT_DISPLAY)
        check(display!=Egl.EGL_NO_DISPLAY && Egl.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        var context=Egl.EGL_NO_CONTEXT;var surface=Egl.EGL_NO_SURFACE
        val input=IntArray(1)
        var filter:TemporalPreviewFilter?=null
        try {
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
            val attributes=intArrayOf(Egl.EGL_RED_SIZE,8,Egl.EGL_GREEN_SIZE,8,Egl.EGL_BLUE_SIZE,8,
                Egl.EGL_SURFACE_TYPE,Egl.EGL_PBUFFER_BIT,Egl.EGL_RENDERABLE_TYPE,0x40,Egl.EGL_NONE)
            val count=IntArray(1);check(Egl.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0)
            context=Egl.eglCreateContext(display,configs[0],Egl.EGL_NO_CONTEXT,
                intArrayOf(Egl.EGL_CONTEXT_CLIENT_VERSION,3,Egl.EGL_NONE),0)
            check(context!=Egl.EGL_NO_CONTEXT)
            surface=Egl.eglCreatePbufferSurface(display,configs[0],intArrayOf(Egl.EGL_WIDTH,1,Egl.EGL_HEIGHT,1,Egl.EGL_NONE),0)
            check(surface!=Egl.EGL_NO_SURFACE && Egl.eglMakeCurrent(display,surface,surface,context))
            val width=128;val height=96;val pixels=width*height
            Gl.glGenTextures(1,input,0);Gl.glBindTexture(Gl.GL_TEXTURE_2D,input[0])
            Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_MIN_FILTER,Gl.GL_LINEAR)
            Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_MAG_FILTER,Gl.GL_LINEAR)
            Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_WRAP_S,Gl.GL_CLAMP_TO_EDGE)
            Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_WRAP_T,Gl.GL_CLAMP_TO_EDGE)
            Gl.glTexImage2D(Gl.GL_TEXTURE_2D,0,Gl.GL_RGBA8,width,height,0,Gl.GL_RGBA,Gl.GL_UNSIGNED_BYTE,null)
            val transform=FloatArray(16).also {Matrix.setIdentityM(it,0)}
            val bytes=ByteBuffer.allocateDirect(pixels*4)
            fun upload(data:ByteArray) {
                bytes.clear();bytes.put(data);bytes.flip()
                Gl.glActiveTexture(Gl.GL_TEXTURE0);Gl.glBindTexture(Gl.GL_TEXTURE_2D,input[0])
                Gl.glTexSubImage2D(Gl.GL_TEXTURE_2D,0,0,0,width,height,Gl.GL_RGBA,Gl.GL_UNSIGNED_BYTE,bytes)
            }
            fun solid(code:Int)=ByteArray(pixels*4) {if(it%4==3)255.toByte() else code.toByte()}
            filter=TemporalPreviewFilter(width,height,false,spatialLuma)
            val f=filter
            var constantError=0.0
            for(code in listOf(0,32,128,255)) {
                upload(solid(code))
                repeat(3) {f.render(input[0],transform,retention)}
                val out=f.readForTest()
                for(i in 0 until pixels)for(c in 0..2)constantError=maxOf(constantError,abs(out[i*4+c]-code/255.0))
            }
            check(constantError<1.0/255)
            f.reset()
            val checker=ByteArray(pixels*4) {i ->
                if(i%4==3)255.toByte() else (if(((i/4%width)+(i/4/width))%2==0)51 else 204).toByte()
            }
            upload(checker);f.render(input[0],transform,retention,true)
            val checkerOut=f.readForTest();var checkerError=0.0
            for(i in 0 until pixels)for(c in 0..2)checkerError=maxOf(checkerError,
                abs(checkerOut[i*4+c]-(checker[i*4+c].toInt() and 255)/255.0))
            check(checkerError<1.0/255)
            val colourStep=ByteArray(pixels*4) {i ->
                if(i%4==3)255.toByte() else {
                    val left=i/4%width<width/2
                    (if(i%4==1)102 else if(left==(i%4==0))51 else 153).toByte()
                }
            }
            upload(colourStep);f.render(input[0],transform,retention,true)
            val colourOut=f.readForTest();var colourEdgeError=0.0
            for(i in 0 until pixels)for(c in 0..2)colourEdgeError=maxOf(colourEdgeError,
                abs(colourOut[i*4+c]-(colourStep[i*4+c].toInt() and 255)/255.0))
            check(colourEdgeError<3.0/255)
            f.reset();val random=Random(190631);val truth=intArrayOf(77,140,191)
            var before=0.0;var after=0.0;var bias=0.0;var samples=0
            repeat(64) {frame ->
                val data=ByteArray(pixels*4)
                for(i in 0 until pixels) {
                    for(c in 0..2)data[i*4+c]=kotlin.math.round(truth[c]+random.nextGaussian()*5.1).toInt().coerceIn(0,255).toByte()
                    data[i*4+3]=255.toByte()
                }
                upload(data);f.render(input[0],transform,retention)
                val filtered=f.readForTest()
                if(frame>=16)for(i in 0 until pixels)for(c in 0..2) {
                    val raw=(data[i*4+c].toInt() and 255)/255.0-truth[c]/255.0
                    val delta=filtered[i*4+c]-truth[c]/255.0
                    before+=raw*raw;after+=delta*delta;bias+=delta;samples++
                }
            }
            check(after/before<.6 && abs(bias/samples)<1.0/255)
            var trail=0.0
            f.reset()
            repeat(24) {frame ->
                val left=8+(frame%12)*6
                val data=ByteArray(pixels*4) {i ->
                    if(i%4==3)255.toByte() else {
                        val x=i/4%width;val y=i/4/width
                        (if(x in left until left+24 && y in 20 until 76)204 else 51).toByte()
                    }
                }
                upload(data);f.render(input[0],transform,retention);val filtered=f.readForTest()
                for(y in 2 until height-2)for(x in 2 until width-2) {
                    // Exclude the one-pixel neighborhood at the current moving edge.
                    val near=x in left-1..left+24 && y in 19..76
                    if(!near)for(c in 0..2)trail=maxOf(trail,abs(filtered[(y*width+x)*4+c]-.2))
                }
            }
            check(trail<1.0/255)
            f.reset()
            var panError=0.0;var panSamples=0;var panCos=0.0;var panSin=0.0
            val omega=2*Math.PI/16
            repeat(48) {frame ->
                val data=ByteArray(pixels*4) {i ->
                    if(i%4==3)255.toByte() else kotlin.math.round(128+40*kotlin.math.cos((i/4%width-frame)*omega)).toInt().toByte()
                }
                upload(data);f.render(input[0],transform,retention);val filtered=f.readForTest()
                if(frame>=16)for(y in 8 until height-8)for(x in 8 until width-8) {
                    val phase=(x-frame)*omega
                    val value=filtered[(y*width+x)*4+1]*255.0
                    val truth=128+40*kotlin.math.cos(phase)
                    panError+=(value-truth)*(value-truth);panSamples++
                    panCos+=(value-128)*kotlin.math.cos(phase);panSin+=(value-128)*kotlin.math.sin(phase)
                }
            }
            val panContrast=2*kotlin.math.sqrt(panCos*panCos+panSin*panSin)/panSamples/40
            val panLag=kotlin.math.abs(kotlin.math.atan2(-panSin,panCos))/omega
            upload(solid(0));f.render(input[0],transform,retention,true)
            upload(solid(255));f.render(input[0],transform,retention)
            val cutError=f.readForTest().maxOf {abs(it-1f)}
            check(cutError<1f/255)
            f.close();filter=null
            // Full-size cost excludes CPU readback and generated random-number work.
            val fullWidth=1536;val fullHeight=2048
            val full=ByteBuffer.allocateDirect(fullWidth*fullHeight*4)
            repeat(fullWidth*fullHeight) {i ->
                full.put((96+(i*43%64)).toByte());full.put((96+(i*17%64)).toByte())
                full.put((96+(i*29%64)).toByte());full.put(255.toByte())
            }
            full.flip();Gl.glActiveTexture(Gl.GL_TEXTURE0);Gl.glBindTexture(Gl.GL_TEXTURE_2D,input[0])
            Gl.glTexImage2D(Gl.GL_TEXTURE_2D,0,Gl.GL_RGBA8,fullWidth,fullHeight,0,Gl.GL_RGBA,Gl.GL_UNSIGNED_BYTE,full)
            val large=TemporalPreviewFilter(fullWidth,fullHeight,false,spatialLuma);filter=large
            repeat(3) {large.render(input[0],transform,retention)}
            Gl.glFinish();val renderStart=System.nanoTime()
            repeat(12) {large.render(input[0],transform,retention)}
            Gl.glFinish();val renderMs=(System.nanoTime()-renderStart)/1_000_000.0/12
            return JSONObject().put("retention",retention).put("spatialLuma",spatialLuma)
                .put("movingTextureMseCode",panError/panSamples).put("movingTextureContrastRatio",panContrast)
                .put("movingTexturePhaseLagPixels",panLag).put("constantMaxError",constantError).put("noiseMseRatio",after/before)
                .put("noiseMeanBias",bias/samples).put("movingTrailOutsideOnePixelMaxError",trail)
                .put("sceneCutMaxError",cutError).put("cleanLumaCheckerMaxError",checkerError).put("cleanColourEdgeMaxError",colourEdgeError).put("frames",64).put("fullWidth",fullWidth)
                .put("fullHeight",fullHeight).put("fullFrameMeanRenderMilliseconds",renderMs)
        } finally {
            filter?.close()
            if(input[0]!=0)Gl.glDeleteTextures(1,input,0)
            Egl.eglMakeCurrent(display,Egl.EGL_NO_SURFACE,Egl.EGL_NO_SURFACE,Egl.EGL_NO_CONTEXT)
            if(surface!=Egl.EGL_NO_SURFACE)Egl.eglDestroySurface(display,surface)
            if(context!=Egl.EGL_NO_CONTEXT)Egl.eglDestroyContext(display,context)
            // The app preview can share EGL_DEFAULT_DISPLAY. Release our own
            // context and surface without terminating the shared display.
        }
    }
}
