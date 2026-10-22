package de.diamaneos.camera.processor

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/** An offscreen context owned by the serial inference thread. Never raises GPU priority. */
internal class PreviewGpuContext:AutoCloseable {
    private val owner=Thread.currentThread()
    private var display:EGLDisplay=EGL14.EGL_NO_DISPLAY
    private var context:EGLContext=EGL14.EGL_NO_CONTEXT
    private var surface:EGLSurface=EGL14.EGL_NO_SURFACE
    private var initialized=false
    private var closed=false
    var lowPriority=false
        private set
    init {
        try {
            check(EGL14.eglGetCurrentContext()==EGL14.EGL_NO_CONTEXT)
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display!=EGL14.EGL_NO_DISPLAY)
            val versions=IntArray(2)
            check(EGL14.eglInitialize(display,versions,0,versions,1));initialized=true
            val attributes=intArrayOf(EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE)
            val configurations=arrayOfNulls<EGLConfig>(1);val count=IntArray(1)
            check(EGL14.eglChooseConfig(display,attributes,0,configurations,0,1,count,0) && count[0]>0)
            val configuration=checkNotNull(configurations[0])
            val supportsPriority=EGL14.eglQueryString(display,EGL14.EGL_EXTENSIONS)?.split(' ')?.contains("EGL_IMG_context_priority")==true
            val normal=intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE)
            if(supportsPriority) {
                context=EGL14.eglCreateContext(display,configuration,EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,PRIORITY_LEVEL,PRIORITY_LOW,EGL14.EGL_NONE),0)
            }
            if(context==EGL14.EGL_NO_CONTEXT) {
                EGL14.eglGetError()
                context=EGL14.eglCreateContext(display,configuration,EGL14.EGL_NO_CONTEXT,normal,0)
            }
            check(context!=EGL14.EGL_NO_CONTEXT)
            surface=EGL14.eglCreatePbufferSurface(display,configuration,
                intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
            check(surface!=EGL14.EGL_NO_SURFACE)
            makeCurrent()
            val actual=IntArray(1)
            lowPriority=supportsPriority && EGL14.eglQueryContext(display,context,PRIORITY_LEVEL,actual,0) && actual[0]==PRIORITY_LOW
        } catch(error:Throwable) {close();throw error}
    }
    fun makeCurrent() {
        check(Thread.currentThread()===owner && !closed)
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
    }
    fun isCurrent()=Thread.currentThread()===owner && !closed && EGL14.eglGetCurrentContext()==context
    override fun close() {
        check(Thread.currentThread()===owner)
        if(closed)return
        closed=true
        if(display!=EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface)
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context)
            if(initialized)EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
        surface=EGL14.EGL_NO_SURFACE;context=EGL14.EGL_NO_CONTEXT;display=EGL14.EGL_NO_DISPLAY
    }
    private companion object {
        const val PRIORITY_LEVEL=0x3100 // EGL_CONTEXT_PRIORITY_LEVEL_IMG
        const val PRIORITY_LOW=0x3103 // EGL_CONTEXT_PRIORITY_LOW_IMG
    }
}
