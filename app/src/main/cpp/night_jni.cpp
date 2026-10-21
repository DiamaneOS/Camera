// SPDX-License-Identifier: MIT
#include "nightcore.h"
#include "nightfinish.h"
#include "photocolor.h"
#include "rawshading.h"
#include <jni.h>
#include <algorithm>
#include <new>
#include <stdexcept>
#include <vector>
#include "jpegburst.h"

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    jclass exception = env->FindClass(type);
    if (exception) env->ThrowNew(exception, message);
}
std::atomic_bool* flag(jlong handle) { return reinterpret_cast<std::atomic_bool*>(static_cast<uintptr_t>(handle)); }
struct JpegMergeState {
    int width, height;
    jpeg_burst::Merger merger;
    JpegMergeState(int w,int h,const std::atomic_bool& stop):width(w),height(h),merger(w,h,stop){}
};
JpegMergeState* jpegState(jlong handle) { return reinterpret_cast<JpegMergeState*>(static_cast<uintptr_t>(handle)); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_createJpegMerge(JNIEnv* env,jclass,jlong task,jint width,jint height) {
    if(!task){fail(env,"java/lang/IllegalArgumentException","Missing JPEG task");return 0;}
    try { return static_cast<jlong>(reinterpret_cast<uintptr_t>(new JpegMergeState(width,height,*flag(task)))); }
    catch(const std::bad_alloc&){fail(env,"java/lang/OutOfMemoryError","JPEG merger allocation failed");}
    catch(const std::exception&){fail(env,"java/lang/IllegalArgumentException","Invalid JPEG dimensions");}
    return 0;
}
extern "C" JNIEXPORT void JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_addJpegMerge(JNIEnv* env,jclass,jlong task,jlong handle,jintArray source) {
    if(!task||!handle||!source){fail(env,"java/lang/IllegalArgumentException","Missing JPEG merge input");return;}
    auto* state=jpegState(handle);int count=env->GetArrayLength(source);
    if(count!=int64_t(state->width)*state->height||count<64||count>16'000'000){fail(env,"java/lang/IllegalArgumentException","JPEG merge input dimensions");return;}
    try {
        std::vector<uint32_t> pixels(size_t(count),0);
        env->GetIntArrayRegion(source,0,count,reinterpret_cast<jint*>(pixels.data()));if(env->ExceptionCheck())return;
        if(!state->merger.add(pixels.data(),pixels.size()))fail(env,"java/lang/IllegalStateException","JPEG processing cancelled");
    } catch(const std::bad_alloc&){fail(env,"java/lang/OutOfMemoryError","JPEG merge allocation failed");}
    catch(const std::exception&){fail(env,"java/lang/IllegalArgumentException","Invalid JPEG merge state");}
}
extern "C" JNIEXPORT jintArray JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_finishJpegMerge(JNIEnv* env,jclass,jlong task,jlong handle,jdoubleArray stats) {
    if(!task||!handle||!stats||env->GetArrayLength(stats)<4){fail(env,"java/lang/IllegalArgumentException","Invalid JPEG merge result");return nullptr;}
    try {
        auto* state=jpegState(handle);std::vector<uint32_t> pixels;jpeg_burst::Stats measured;
        if(!state->merger.finish(pixels,measured)||!night::finish(pixels.data(),state->width,state->height,0,*flag(task),1,4.f/3,nullptr,true)) {
            fail(env,"java/lang/IllegalStateException","JPEG processing cancelled");return nullptr;
        }
        auto result=env->NewIntArray(jint(pixels.size()));if(!result)return nullptr;
        env->SetIntArrayRegion(result,0,jint(pixels.size()),reinterpret_cast<const jint*>(pixels.data()));
        if(env->ExceptionCheck())return nullptr;
        double values[4]={measured.effectiveFrames,double(measured.rejectedFrames),measured.maximumAcceptedShift,double(measured.registration.size()+1)};
        env->SetDoubleArrayRegion(stats,0,4,values);
        return env->ExceptionCheck()?nullptr:result;
    } catch(const std::bad_alloc&){fail(env,"java/lang/OutOfMemoryError","JPEG result allocation failed");}
    catch(const std::exception&){fail(env,"java/lang/IllegalArgumentException","Invalid JPEG merge state");}
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_destroyJpegMerge(JNIEnv*,jclass,jlong handle) { delete jpegState(handle); }

extern "C" JNIEXPORT jlong JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_create(JNIEnv* env, jclass) {
    auto* value = new (std::nothrow) std::atomic_bool(false);
    if (!value) fail(env, "java/lang/OutOfMemoryError", "cannot allocate RAW task");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_cancel(JNIEnv*, jclass, jlong handle) {
    if (handle) flag(handle)->store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_destroy(JNIEnv*, jclass, jlong handle) { delete flag(handle); }

extern "C" JNIEXPORT jintArray JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_process(JNIEnv* env, jclass, jlong handle,
        jobjectArray buffers, jintArray rowStrides, jfloatArray calibrations, jint width, jint height,
        jint cfa, jint reference, jfloat exposure, jfloatArray color, jdoubleArray statistics, jfloat targetLuminance, jfloat zoom, jfloat aspect, jfloatArray toneTargets, jintArray colorReference, jint referenceRotation, jintArray periodicGrid, jboolean edgeAwareDemosaic, jboolean frequencyDenoise, jboolean steadyPhotoFinish, jobject rawShading) {
    if (!handle || !buffers || !rowStrides || !calibrations || !color || !statistics || width < 8 || height < 8 ||
        uint64_t(width) * uint64_t(height) > 16'000'000) {
        fail(env, "java/lang/IllegalArgumentException", "invalid RAW task buffers or dimensions"); return nullptr;
    }
    int count = env->GetArrayLength(buffers);
    if (count < 2 || count > 8 || env->GetArrayLength(rowStrides) != count ||
        env->GetArrayLength(calibrations) != count * 13 || env->GetArrayLength(color) != 9 ||
        env->GetArrayLength(statistics) < 2) {
        fail(env, "java/lang/IllegalArgumentException", "invalid RAW metadata dimensions"); return nullptr;
    }
    if (colorReference && (env->GetArrayLength(colorReference) != 96 * 96 ||
        (referenceRotation != 0 && referenceRotation != 90 && referenceRotation != 180 && referenceRotation != 270))) {
        fail(env, "java/lang/IllegalArgumentException", "invalid photo color reference"); return nullptr;
    }
    try {
        std::array<jint, 8> strides{};
        std::array<jfloat, 8 * 13> calibration{};
        night::Config config{width, height, cfa, reference, exposure, {}};
        config.highPrecision = true;
        config.spatialDenoise = true;
        config.edgeAwareDemosaic = edgeAwareDemosaic == JNI_TRUE;
        config.frequencyDenoise = frequencyDenoise == JNI_TRUE;
        env->GetIntArrayRegion(rowStrides, 0, count, strides.data());
        env->GetFloatArrayRegion(calibrations, 0, count * 13, calibration.data());
        env->GetFloatArrayRegion(color, 0, 9, config.color.data());
        if (env->ExceptionCheck()) return nullptr;
        std::array<night::Frame, 8> frames{};
        std::array<jint,4> grid{};
        if(periodicGrid) {
            if(env->GetArrayLength(periodicGrid)!=4) {fail(env,"java/lang/IllegalArgumentException","invalid RAW site calibration");return nullptr;}
            env->GetIntArrayRegion(periodicGrid,0,4,grid.data());if(env->ExceptionCheck()) return nullptr;
        }
        for (int i = 0; i < count; ++i) {
            jobject buffer = env->GetObjectArrayElement(buffers, i);
            if (!buffer) {
                fail(env, "java/lang/IllegalArgumentException", "missing RAW frame"); return nullptr;
            }
            void* address = env->GetDirectBufferAddress(buffer);
            jlong bytes = env->GetDirectBufferCapacity(buffer);
            env->DeleteLocalRef(buffer);
            if (!address || bytes <= 0) {
                fail(env, "java/lang/IllegalArgumentException", "RAW frames must be direct buffers"); return nullptr;
            }
            auto& frame = frames[size_t(i)];
            frame.data = static_cast<const uint8_t*>(address);
            frame.bytes = static_cast<size_t>(bytes);
            frame.rowStride = strides[size_t(i)];
            for (int c = 0; c < 4; ++c) {
                frame.black[size_t(c)] = calibration[size_t(i) * 13 + c];
                frame.noise[size_t(c)] = {calibration[size_t(i) * 13 + 5 + c * 2], calibration[size_t(i) * 13 + 6 + c * 2]};
            }
            frame.white = calibration[size_t(i) * 13 + 4];
            std::copy(grid.begin(),grid.end(),frame.periodicGrid.begin());
        }
        std::array<std::vector<float>, 8> shadingStorage;
        if (rawShading) {
            auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rawShading));
            jlong size = env->GetDirectBufferCapacity(rawShading);
            if (!bytes || size < 24 || size > jlong(night::maxShadingBytes) ||
                !night::decodeShading(bytes, size_t(size), count, width, height, cfa, frames, shadingStorage)) {
                fail(env, "java/lang/IllegalArgumentException", "Invalid RAW shading input"); return nullptr;
            }
        }
        std::vector<uint32_t> pixels(size_t(width) * height);
        night::Stats stats;
        if (!night::process(frames.data(), count, config, pixels.data(), *flag(handle), stats)) {
            fail(env, "java/lang/IllegalStateException", flag(handle)->load() ? "RAW processing cancelled" : "RAW calibration or frame validation failed");
            return nullptr;
        }
        float targets[3];
        if (toneTargets) {
            if (env->GetArrayLength(toneTargets) != 3) { fail(env, "java/lang/IllegalArgumentException", "Invalid tone targets"); return nullptr; }
            env->GetFloatArrayRegion(toneTargets, 0, 3, targets);
            if (env->ExceptionCheck()) return nullptr;
        }
        if (!night::finish(pixels.data(), width, height, targetLuminance, *flag(handle), zoom, aspect, toneTargets ? targets : nullptr, false, true, steadyPhotoFinish == JNI_TRUE)) {
            fail(env, "java/lang/IllegalStateException", "Night finishing cancelled or invalid"); return nullptr;
        }
        photo::ColorStats colorStats;
        if (colorReference) {
            std::array<jint, 96 * 96> preview{};
            env->GetIntArrayRegion(colorReference, 0, 96 * 96, preview.data());
            if (env->ExceptionCheck()) return nullptr;
            if (!photo::match(pixels.data(), width, height, reinterpret_cast<const uint32_t*>(preview.data()), referenceRotation, zoom, aspect, *flag(handle), colorStats)) {
                fail(env, "java/lang/IllegalStateException", "photo color matching cancelled or invalid"); return nullptr;
            }
        }
        jintArray result = env->NewIntArray(width * height);
        if (!result) return nullptr;
        env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<const jint*>(pixels.data()));
        jdouble values[2] = {stats.effectiveFrames, double(stats.inputFrames)};
        env->SetDoubleArrayRegion(statistics, 0, 2, values);
        if (env->GetArrayLength(statistics) >= 6) {
            jdouble colorValues[4] = {colorStats.applied ? 1. : 0., colorStats.before, colorStats.after, colorStats.inliers};
            env->SetDoubleArrayRegion(statistics, 2, 4, colorValues);
        }
        return result;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "RAW processing memory exhausted");
    } catch (const std::exception&) {
        fail(env, "java/lang/IllegalStateException", "RAW processing failed");
    }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_denoise(JNIEnv* env,jclass,jlong handle,jintArray source,jint width,jint height) {
    if(!handle || !source || width<8 || height<8 || width>8192 || height>8192 || int64_t(width)*height>16'000'000 || env->GetArrayLength(source)!=width*height) {
        fail(env,"java/lang/IllegalArgumentException","Invalid JPEG processing input");return nullptr;
    }
    try {
        std::vector<uint32_t> pixels(size_t(width)*height);
        env->GetIntArrayRegion(source,0,width*height,reinterpret_cast<jint*>(pixels.data()));
        if(env->ExceptionCheck()) return nullptr;
        if(!night::finish(pixels.data(),width,height,0,*flag(handle),1,4.f/3,nullptr,true)) {
            fail(env,"java/lang/IllegalStateException","JPEG finishing cancelled or invalid");return nullptr;
        }
        jintArray result=env->NewIntArray(width*height);
        if(result) env->SetIntArrayRegion(result,0,width*height,reinterpret_cast<const jint*>(pixels.data()));
        return result;
    } catch(const std::bad_alloc&) {fail(env,"java/lang/OutOfMemoryError","JPEG processing memory exhausted");}
    catch(const std::exception&) {fail(env,"java/lang/IllegalStateException","JPEG processing failed");}
    return nullptr;
}
