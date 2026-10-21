package app.grapheneos.camera.ui

import android.graphics.SurfaceTexture
import android.opengl.EGL14 as Egl
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES30 as Gl
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.DynamicRange
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Preview-only SDR filtering through CameraX-owned transforms and surface lifetimes. */
internal class TemporalPreviewEffect private constructor(private val processor:Processor):
    CameraEffect(CameraEffect.PREVIEW,executor,processor,Consumer {processor.fail()}),AutoCloseable {
    constructor(retention:Float,spatialLuma:Float=0f,onFailure:()->Unit):this(Processor(retention,spatialLuma,onFailure))
    fun parameters(enabled:Boolean,reset:Boolean) {processor.parameters(enabled,reset)}
    fun luminance(value:Float) {processor.luminance(value)}
    override fun close() {processor.close()}

    companion object {
        // One idle thread per app, with no Activity reference. It remains available
        // for late CameraX release callbacks after a particular effect is closed.
        private val thread by lazy {HandlerThread("camera-preview-gl").apply {start()}}
        private val handler by lazy {Handler(thread.looper)}
        private val executor=Executor {handler.post(it)}
        private val sharedDisplay by lazy {
            Egl.eglGetDisplay(Egl.EGL_DEFAULT_DISPLAY).also {
                check(it!=Egl.EGL_NO_DISPLAY && Egl.eglInitialize(it,IntArray(2),0,IntArray(2),0))
            }
        }
    }

    private class Input(val texture:Int,val image:SurfaceTexture,val surface:Surface) {
        var closed=false
    }
    private class Output(val request:SurfaceOutput,val surface:android.opengl.EGLSurface) {
        val matrix=FloatArray(16)
        val adjusted=FloatArray(16)
    }
    private class Processor(private val retention:Float,@Volatile private var spatialLuma:Float,private val failure:()->Unit):SurfaceProcessor,AutoCloseable {
        private val stopped=AtomicBoolean()
        private val failed=AtomicBoolean()
        private val resetGeneration=AtomicInteger()
        @Volatile private var enabled=false
        private var generation=0
        private var display=Egl.EGL_NO_DISPLAY
        private var context=Egl.EGL_NO_CONTEXT
        private var pbuffer=Egl.EGL_NO_SURFACE
        private var config:android.opengl.EGLConfig?=null
        private var released=false
        private var blit=0
        private var plainBlit=0
        private val inputs=mutableSetOf<Input>()
        private val outputs=mutableMapOf<SurfaceOutput,Output>()
        private var active:Input?=null
        private var filter:TemporalPreviewFilter?=null
        private var inputWidth=0;private var inputHeight=0
        private var filterDisabled=false
        private var timestamp=0L
        private var frames=0
        private val transform=FloatArray(16)
        private val previousTransform=FloatArray(16)
        private val inverse=FloatArray(16)
        init {require(retention.isFinite() && retention in 0f..0.8f && spatialLuma.isFinite() && spatialLuma in 0f..1f)}

        fun parameters(value:Boolean,reset:Boolean) {
            if(enabled!=value || reset)resetGeneration.incrementAndGet()
            enabled=value
        }
        fun luminance(value:Float) {
            require(value.isFinite() && value in 0f..1f)
            if(spatialLuma!=value)resetGeneration.incrementAndGet()
            spatialLuma=value
        }
        fun fail() {
            if(!stopped.get() && failed.compareAndSet(false,true))failure()
        }
        private fun ready() {
            check(!released)
            if(context!=Egl.EGL_NO_CONTEXT) {current(pbuffer);return}
            display=sharedDisplay
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1);val count=IntArray(1)
            val attributes=intArrayOf(Egl.EGL_RED_SIZE,8,Egl.EGL_GREEN_SIZE,8,Egl.EGL_BLUE_SIZE,8,
                Egl.EGL_ALPHA_SIZE,8,Egl.EGL_SURFACE_TYPE,Egl.EGL_WINDOW_BIT or Egl.EGL_PBUFFER_BIT,
                Egl.EGL_RENDERABLE_TYPE,0x40,0x3142,1,Egl.EGL_NONE)
            check(Egl.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0)
            config=configs[0]
            context=Egl.eglCreateContext(display,config,Egl.EGL_NO_CONTEXT,intArrayOf(Egl.EGL_CONTEXT_CLIENT_VERSION,3,Egl.EGL_NONE),0)
            check(context!=Egl.EGL_NO_CONTEXT)
            pbuffer=Egl.eglCreatePbufferSurface(display,config,intArrayOf(Egl.EGL_WIDTH,1,Egl.EGL_HEIGHT,1,Egl.EGL_NONE),0)
            check(pbuffer!=Egl.EGL_NO_SURFACE);current(pbuffer)
            fun fragment(external:Boolean):String {
                val extension=if(external)"#extension GL_OES_EGL_image_external_essl3 : require" else ""
                val sampler=if(external)"samplerExternalOES" else "sampler2D"
                return """#version 300 es
                    $extension
                    precision highp float;
                    uniform $sampler image;
                    uniform mat4 transform;
                    in vec2 uv;
                    out vec4 result;
                    void main() {result=vec4(texture(image,(transform*vec4(uv,0.,1.)).xy).rgb,1.);}
                """.trimIndent()
            }
            blit=TemporalPreviewFilter.link(TemporalPreviewFilter.VERTEX,fragment(false))
            plainBlit=TemporalPreviewFilter.link(TemporalPreviewFilter.VERTEX,fragment(true))
        }
        private fun current(surface:android.opengl.EGLSurface) {
            check(Egl.eglMakeCurrent(display,surface,surface,context))
        }
        override fun onInputSurface(request:SurfaceRequest) {
            if(stopped.get()) {request.willNotProvideSurface();return}
            val size=request.resolution
            if(app.grapheneos.camera.BuildConfig.DEBUG)android.util.Log.i("TemporalPreview","input width="+size.width+" height="+size.height+" encoding="+request.dynamicRange.encoding+" bits="+request.dynamicRange.bitDepth)
            if(request.dynamicRange!=DynamicRange.SDR || size.width !in 2..4096 || size.height !in 2..4096 ||
                size.width.toLong()*size.height>4_194_304 || inputs.size>=2) {
                request.willNotProvideSurface();fail();return
            }
            var input:Input?=null
            try {
                ready()
                val texture=IntArray(1);Gl.glGenTextures(1,texture,0)
                Gl.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture[0])
                Gl.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,Gl.GL_TEXTURE_MIN_FILTER,Gl.GL_LINEAR)
                Gl.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,Gl.GL_TEXTURE_MAG_FILTER,Gl.GL_LINEAR)
                Gl.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,Gl.GL_TEXTURE_WRAP_S,Gl.GL_CLAMP_TO_EDGE)
                Gl.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,Gl.GL_TEXTURE_WRAP_T,Gl.GL_CLAMP_TO_EDGE)
                val image=SurfaceTexture(texture[0]);image.setDefaultBufferSize(size.width,size.height)
                val lease=Input(texture[0],image,Surface(image));input=lease;inputs.add(lease);active=lease
                filter?.close();filter=null;filterDisabled=false;timestamp=0
                inputWidth=size.width;inputHeight=size.height;resetGeneration.incrementAndGet()
                image.setOnFrameAvailableListener({frame(lease)},handler)
                request.provideSurface(lease.surface,executor) {release(lease)}
            } catch(error:RuntimeException) {
                if(app.grapheneos.camera.BuildConfig.DEBUG)android.util.Log.w("TemporalPreview","Input setup failed",error)
                input?.let {release(it)};request.willNotProvideSurface();fail()
            }
        }
        override fun onOutputSurface(request:SurfaceOutput) {
            if(stopped.get()) {request.close();return}
            try {
                ready()
                val surface=request.getSurface(executor) {release(request)}
                if(!surface.isValid) {request.close();return}
                val window=Egl.eglCreateWindowSurface(display,config,surface,intArrayOf(Egl.EGL_NONE),0)
                check(window!=Egl.EGL_NO_SURFACE)
                outputs[request]=Output(request,window)
            } catch(error:RuntimeException) {
                if(app.grapheneos.camera.BuildConfig.DEBUG)android.util.Log.w("TemporalPreview","Output setup failed",error)
                request.close();fail()
            }
        }
        private fun frame(input:Input) {
            if(released || input.closed)return
            try {
                current(pbuffer);input.image.updateTexImage()
                // Drain obsolete producers until CameraX releases their surfaces.
                if(stopped.get() || active!==input || outputs.isEmpty())return
                input.image.getTransformMatrix(transform)
                check(transform.all {it.isFinite()} && Matrix.invertM(inverse,0,transform,0))
                val now=input.image.timestamp;val version=resetGeneration.get()
                val reset=version!=generation || now<=timestamp || now-timestamp>120_000_000L ||
                    transform.indices.any {abs(transform[it]-previousTransform[it])>.0001f}
                generation=version;timestamp=now;transform.copyInto(previousTransform)
                var filtered=0
                if(enabled && !filterDisabled) {
                    try {
                        val renderer=filter ?: TemporalPreviewFilter(inputWidth,inputHeight,true,spatialLuma).also {filter=it}
                        filtered=renderer.render(input.texture,transform,retention,reset,spatialLuma)
                    } catch(error:RuntimeException) {
                        if(app.grapheneos.camera.BuildConfig.DEBUG)android.util.Log.w("TemporalPreview","Filter disabled",error)
                        runCatching {filter?.close()};filter=null;filterDisabled=true
                    }
                } else filter?.reset()
                if(app.grapheneos.camera.BuildConfig.DEBUG && frames++%60==0)
                    android.util.Log.i("TemporalPreview","filtered="+(filtered!=0)+" width="+inputWidth+" height="+inputHeight+" reset="+reset)
                for(output in outputs.values.toList()) {
                    current(output.surface);Gl.glBindFramebuffer(Gl.GL_FRAMEBUFFER,0)
                    Gl.glViewport(0,0,output.request.size.width,output.request.size.height)
                    output.request.updateTransformMatrix(output.matrix,transform)
                    val program=if(filtered==0)plainBlit else blit
                    val coordinates=if(filtered==0)output.matrix else output.adjusted.also {
                        Matrix.multiplyMM(it,0,inverse,0,output.matrix,0)
                    }
                    Gl.glUseProgram(program);Gl.glActiveTexture(Gl.GL_TEXTURE0)
                    Gl.glBindTexture(if(filtered==0)GLES11Ext.GL_TEXTURE_EXTERNAL_OES else Gl.GL_TEXTURE_2D,
                        if(filtered==0)input.texture else filtered)
                    Gl.glUniform1i(Gl.glGetUniformLocation(program,"image"),0)
                    Gl.glUniformMatrix4fv(Gl.glGetUniformLocation(program,"transform"),1,false,coordinates,0)
                    Gl.glDrawArrays(Gl.GL_TRIANGLES,0,3);TemporalPreviewFilter.checkGl()
                    EGLExt.eglPresentationTimeANDROID(display,output.surface,now)
                    if(!Egl.eglSwapBuffers(display,output.surface)) {
                        release(output.request)
                        if(outputs.isEmpty())fail()
                    }
                }
            } catch(error:RuntimeException) {
                if(app.grapheneos.camera.BuildConfig.DEBUG)android.util.Log.w("TemporalPreview","Frame processing failed",error)
                fail()
            }
        }
        private fun release(input:Input) {
            if(input.closed)return
            input.closed=true;inputs.remove(input)
            val hasContext=context!=Egl.EGL_NO_CONTEXT && runCatching {current(pbuffer)}.isSuccess
            runCatching {input.image.setOnFrameAvailableListener(null)}
            runCatching {input.surface.release()};runCatching {input.image.release()}
            if(hasContext)Gl.glDeleteTextures(1,intArrayOf(input.texture),0)
            if(active===input) {active=null;runCatching {filter?.close()};filter=null}
            cleanup()
        }
        private fun release(request:SurfaceOutput) {
            val output=outputs.remove(request)
            if(output!=null && display!=Egl.EGL_NO_DISPLAY) {
                runCatching {current(pbuffer)};Egl.eglDestroySurface(display,output.surface)
            }
            runCatching {request.close()}
        }
        override fun close() {
            if(!stopped.compareAndSet(false,true))return
            executor.execute {
                if(context!=Egl.EGL_NO_CONTEXT)runCatching {current(pbuffer)}
                outputs.keys.toList().forEach {release(it)}
                runCatching {filter?.close()};filter=null
                cleanup()
            }
        }
        private fun cleanup() {
            if(released || !stopped.get() || inputs.isNotEmpty())return
            released=true
            if(context!=Egl.EGL_NO_CONTEXT) {
                if(Egl.eglMakeCurrent(display,pbuffer,pbuffer,context)) {
                    if(blit!=0)Gl.glDeleteProgram(blit)
                    if(plainBlit!=0)Gl.glDeleteProgram(plainBlit)
                }
                Egl.eglMakeCurrent(display,Egl.EGL_NO_SURFACE,Egl.EGL_NO_SURFACE,Egl.EGL_NO_CONTEXT)
                Egl.eglDestroySurface(display,pbuffer);Egl.eglDestroyContext(display,context)
            }
            // EGL_DEFAULT_DISPLAY may also be used by the app's UI renderer.
            // Release only this effect's context and surfaces, not the display.
            context=Egl.EGL_NO_CONTEXT;pbuffer=Egl.EGL_NO_SURFACE
        }
    }
}
