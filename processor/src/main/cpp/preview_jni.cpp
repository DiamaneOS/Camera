// SPDX-License-Identifier: MIT
#include "portraitcore.h"
#include <jni.h>
#include <exception>
#include <new>
#include <vector>
extern "C" JNIEXPORT jintArray JNICALL
Java_app_grapheneos_camera_processor_PortraitProcessorService_controls(JNIEnv* env,jobject,jintArray source,jfloatArray mask,jfloatArray depth,jint dw,jint dh,jfloat x,jfloat y) {
    auto fail=[&](const char* type,const char* message) {env->ThrowNew(env->FindClass(type),message);};
    constexpr int size=512*512;
    if(!source || !mask || env->GetArrayLength(source)!=size || env->GetArrayLength(mask)!=size) {
        fail("java/lang/IllegalArgumentException","Invalid live Portrait buffers");return nullptr;
    }
    try {
        std::vector<uint32_t> pixels(size);std::vector<float> matte(size),inverseDepth;std::vector<uint32_t> output(size);
        env->GetIntArrayRegion(source,0,size,reinterpret_cast<jint*>(pixels.data()));
        env->GetFloatArrayRegion(mask,0,size,matte.data());
        if(depth) {
            if(dw<2 || dh<2 || dw>512 || dh>512 || env->GetArrayLength(depth)!=dw*dh) {
                fail("java/lang/IllegalArgumentException","Invalid preview depth");return nullptr;
            }
            inverseDepth.resize(size_t(dw)*dh);env->GetFloatArrayRegion(depth,0,dw*dh,inverseDepth.data());
        }
        if(env->ExceptionCheck())return nullptr;
        std::atomic_bool cancelled(false);
        if(!portrait::previewControls(pixels.data(),512,512,matte.data(),512,512,
                depth?inverseDepth.data():nullptr,dw,dh,x,y,output.data(),cancelled)) {
            fail("java/lang/IllegalStateException","Invalid live Portrait refinement");return nullptr;
        }
        jintArray result=env->NewIntArray(size);
        if(result)env->SetIntArrayRegion(result,0,size,reinterpret_cast<jint*>(output.data()));
        return result;
    } catch(const std::bad_alloc&) {fail("java/lang/OutOfMemoryError","Live Portrait memory exhausted");return nullptr;}
    catch (const std::exception&) { fail("java/lang/IllegalStateException", "Portrait native failure"); return nullptr; }
}
