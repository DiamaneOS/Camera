package app.grapheneos.camera.ui

import android.opengl.GLES11Ext
import android.opengl.GLES30 as Gl
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded SDR history for CameraX PREVIEW; capture/video streams retain their own processing. */
internal class TemporalPreviewFilter(private val width:Int,private val height:Int,
    externalTexture:Boolean,private val spatialLuma:Float=0f):AutoCloseable {
    private val owner=Thread.currentThread()
    private val textures=IntArray(2)
    private val buffers=IntArray(2)
    private var program=0
    private var current=0
    private var valid=false
    private var closed=false
    private val target=if(externalTexture)GLES11Ext.GL_TEXTURE_EXTERNAL_OES else Gl.GL_TEXTURE_2D
    init {
        require(width in 2..4096 && height in 2..4096 && width.toLong()*height<=4_194_304)
        require(spatialLuma.isFinite() && spatialLuma in 0f..1f)
        try {
            program=program(externalTexture)
            Gl.glGenTextures(2,textures,0);Gl.glGenFramebuffers(2,buffers,0)
            for(i in 0..1) {
                Gl.glBindTexture(Gl.GL_TEXTURE_2D,textures[i])
                Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_MIN_FILTER,Gl.GL_LINEAR)
                Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_MAG_FILTER,Gl.GL_LINEAR)
                Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_WRAP_S,Gl.GL_CLAMP_TO_EDGE)
                Gl.glTexParameteri(Gl.GL_TEXTURE_2D,Gl.GL_TEXTURE_WRAP_T,Gl.GL_CLAMP_TO_EDGE)
                Gl.glTexImage2D(Gl.GL_TEXTURE_2D,0,Gl.GL_RGBA16F,width,height,0,Gl.GL_RGBA,Gl.GL_HALF_FLOAT,null)
                Gl.glBindFramebuffer(Gl.GL_FRAMEBUFFER,buffers[i])
                Gl.glFramebufferTexture2D(Gl.GL_FRAMEBUFFER,Gl.GL_COLOR_ATTACHMENT0,Gl.GL_TEXTURE_2D,textures[i],0)
                check(Gl.glCheckFramebufferStatus(Gl.GL_FRAMEBUFFER)==Gl.GL_FRAMEBUFFER_COMPLETE)
            }
            checkGl()
        } catch(error:RuntimeException) {close();throw error}
    }

    /** Called on the owning GL thread after updating the input image. */
    fun render(input:Int,transform:FloatArray,retention:Float,reset:Boolean=false,lumaStrength:Float=spatialLuma):Int {
        checkOwner();require(input>0 && transform.size==16 && transform.all {it.isFinite()})
        require(retention.isFinite() && retention in 0f..0.8f)
        require(lumaStrength.isFinite() && lumaStrength in 0f..1f)
        val next=1-current
        Gl.glBindFramebuffer(Gl.GL_FRAMEBUFFER,buffers[next]);Gl.glViewport(0,0,width,height)
        Gl.glUseProgram(program)
        Gl.glActiveTexture(Gl.GL_TEXTURE0);Gl.glBindTexture(target,input)
        Gl.glUniform1i(Gl.glGetUniformLocation(program,"image"),0)
        Gl.glActiveTexture(Gl.GL_TEXTURE1);Gl.glBindTexture(Gl.GL_TEXTURE_2D,textures[current])
        Gl.glUniform1i(Gl.glGetUniformLocation(program,"history"),1)
        Gl.glUniformMatrix4fv(Gl.glGetUniformLocation(program,"transform"),1,false,transform,0)
        Gl.glUniform2f(Gl.glGetUniformLocation(program,"texel"),1f/width,1f/height)
        Gl.glUniform1f(Gl.glGetUniformLocation(program,"retention"),if(valid && !reset)retention else 0f)
        Gl.glUniform1f(Gl.glGetUniformLocation(program,"spatialLuma"),lumaStrength)
        Gl.glDrawArrays(Gl.GL_TRIANGLES,0,3);checkGl()
        current=next;valid=true
        return textures[current]
    }

    fun reset() {checkOwner();valid=false}

    /** Only the generated debug test reads pixels; live rendering keeps them on the GPU. */
    fun readForTest():FloatArray {
        checkOwner()
        val bytes=ByteBuffer.allocateDirect(width*height*16).order(ByteOrder.nativeOrder())
        Gl.glBindFramebuffer(Gl.GL_FRAMEBUFFER,buffers[current])
        Gl.glReadPixels(0,0,width,height,Gl.GL_RGBA,Gl.GL_FLOAT,bytes);checkGl()
        return FloatArray(width*height*4).also {bytes.asFloatBuffer().get(it)}
    }

    private fun checkOwner() {check(!closed && Thread.currentThread()===owner)}
    override fun close() {
        check(Thread.currentThread()===owner)
        if(closed)return
        closed=true
        Gl.glDeleteFramebuffers(2,buffers,0);Gl.glDeleteTextures(2,textures,0)
        if(program!=0)Gl.glDeleteProgram(program)
    }

    companion object {
        internal const val VERTEX="""#version 300 es
            precision highp float;
            out vec2 uv;
            void main() {
                vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
                uv=p;gl_Position=vec4(p*2.-1.,0.,1.);
            }
        """
        private fun program(external:Boolean):Int {
            val extension=if(external)"#extension GL_OES_EGL_image_external_essl3 : require" else ""
            val sampler=if(external)"samplerExternalOES" else "sampler2D"
            return link(VERTEX,"""#version 300 es
                $extension
                precision highp float;
                uniform $sampler image;
                uniform sampler2D history;
                uniform mat4 transform;
                uniform vec2 texel;
                uniform float retention;
                uniform float spatialLuma;
                in vec2 uv;
                out vec4 result;
                vec3 sampleCurrent(vec2 p) {return texture(image,(transform*vec4(p,0.,1.)).xy).rgb;}
                float maximum(vec3 p) {return max(p.r,max(p.g,p.b));}
                void main() {
                    vec3 now=sampleCurrent(uv);
                    vec3 low=now,high=now;
                    const vec3 lumaWeights=vec3(.2126,.7152,.0722);
                    float luma=dot(now,lumaWeights);
                    vec3 chroma=now-vec3(luma),chromaSum=vec3(0.);
                    float chromaWeight=0.;
                    float lumaSum=0.,lumaWeight=0.;
                    for(int y=-1;y<=1;++y)for(int x=-1;x<=1;++x) {
                        vec3 value=sampleCurrent(uv+vec2(x,y)*texel);
                        low=min(low,value);high=max(high,value);
                        float otherLuma=dot(value,lumaWeights);
                        vec3 otherChroma=value-vec3(otherLuma);
                        float dl=(otherLuma-luma)/.06;
                        vec3 dc=otherChroma-chroma;
                        float kernel=float((x==0?2:1)*(y==0?2:1));
                        float weight=kernel/(1.+dl*dl+dot(dc,dc)/.01);
                        chromaSum+=weight*otherChroma;chromaWeight+=weight;
                        if(spatialLuma>0.) {
                            float distance=(otherLuma-luma)/.04;
                            float lw=kernel/(1.+distance*distance);
                            lumaSum+=lw*otherLuma;lumaWeight+=lw;
                        }
                    }
                    // A small joint bilateral chroma pass suppresses colour
                    // speckle without spatially smoothing luminance detail.
                    float cleanLuma=luma;
                    if(spatialLuma>0.)cleanLuma=mix(luma,lumaSum/lumaWeight,spatialLuma);
                    now=clamp(vec3(cleanLuma)+chromaSum/chromaWeight,0.,1.);
                    if(retention==0.) {result=vec4(now,1.);return;}
                    vec3 old=texture(history,uv).rgb;
                    // Neighborhood clipping confines disocclusion trails to the
                    // current local colours. Strong detail favors the current image.
                    vec3 clipped=clamp(old,low-1./255.,high+1./255.);
                    float motion=1.-smoothstep(.01,.05,maximum(abs(old-clipped)));
                    float detail=1.-smoothstep(.08,.20,maximum(high-low));
                    float weight=retention*motion*mix(.35,1.,detail);
                    result=vec4(clamp(mix(now,clipped,weight),0.,1.),1.);
                }
            """.trimIndent())
        }
        internal fun link(vertex:String,fragment:String):Int {
            fun compile(kind:Int,text:String):Int {
                val id=Gl.glCreateShader(kind)
                try {
                    Gl.glShaderSource(id,text);Gl.glCompileShader(id)
                    val status=IntArray(1);Gl.glGetShaderiv(id,Gl.GL_COMPILE_STATUS,status,0)
                    check(status[0]!=0) {"Preview shader compilation failed"}
                    return id
                } catch(error:RuntimeException) {Gl.glDeleteShader(id);throw error}
            }
            val v=compile(Gl.GL_VERTEX_SHADER,vertex)
            var f=0;var p=0
            try {
                f=compile(Gl.GL_FRAGMENT_SHADER,fragment);p=Gl.glCreateProgram()
                Gl.glAttachShader(p,v);Gl.glAttachShader(p,f);Gl.glLinkProgram(p)
                val status=IntArray(1);Gl.glGetProgramiv(p,Gl.GL_LINK_STATUS,status,0)
                check(status[0]!=0) {"Preview shader linking failed"}
                return p
            } catch(error:RuntimeException) {if(p!=0)Gl.glDeleteProgram(p);throw error}
            finally {Gl.glDeleteShader(v);if(f!=0)Gl.glDeleteShader(f)}
        }
        internal fun checkGl() {check(Gl.glGetError()==Gl.GL_NO_ERROR) {"Preview GL operation failed"}}
    }
}
