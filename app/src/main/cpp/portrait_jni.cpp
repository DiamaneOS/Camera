// SPDX-License-Identifier: MIT
#include "portraitcore.h"
#include <jni.h>
#include <exception>
#include <new>
#include <vector>
extern "C" JNIEXPORT jintArray JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_portrait(JNIEnv* env, jclass, jlong handle,
        jintArray source, jint width, jint height, jfloatArray mask, jint mw, jint mh, jint radius,
        jfloatArray depth, jint dw, jint dh, jfloat subjectX, jfloat subjectY) {
    auto fail = [&](const char* type, const char* text) { env->ThrowNew(env->FindClass(type), text); };
    if (!handle || !source || !mask || width < 2 || height < 2 || mw < 2 || mh < 2 ||
        int64_t(width) * height > 16'000'000 || int64_t(mw) * mh > 1'000'000 || radius < 0 || radius > 40 ||
        env->GetArrayLength(source) != width * height || env->GetArrayLength(mask) != mw * mh) {
        fail("java/lang/IllegalArgumentException", "Invalid Portrait buffers"); return nullptr;
    }
    if (depth && (dw < 2 || dh < 2 || int64_t(dw) * dh > 1'000'000 || env->GetArrayLength(depth) != dw * dh)) {
        fail("java/lang/IllegalArgumentException", "Invalid Portrait depth"); return nullptr;
    }
    try {
        std::vector<uint32_t> pixels(size_t(width) * height), output(pixels.size());
        std::vector<float> matte(size_t(mw) * mh);
        env->GetIntArrayRegion(source, 0, width * height, reinterpret_cast<jint*>(pixels.data()));
        env->GetFloatArrayRegion(mask, 0, mw * mh, matte.data());
        std::vector<float> inverseDepth;
        if (depth) { inverseDepth.resize(size_t(dw) * dh); env->GetFloatArrayRegion(depth, 0, dw * dh, inverseDepth.data()); }
        if (env->ExceptionCheck()) return nullptr;
        auto* cancel = reinterpret_cast<std::atomic_bool*>(static_cast<uintptr_t>(handle));
        if (!portrait::render(pixels.data(), width, height, matte.data(), mw, mh, radius, output.data(), *cancel,
                depth ? inverseDepth.data() : nullptr, dw, dh, subjectX, subjectY)) {
            fail("java/lang/IllegalStateException", "Portrait rendering cancelled or invalid"); return nullptr;
        }
        jintArray result = env->NewIntArray(width * height);
        if (result) env->SetIntArrayRegion(result, 0, width * height, reinterpret_cast<jint*>(output.data()));
        return result;
    } catch (const std::bad_alloc&) { fail("java/lang/OutOfMemoryError", "Portrait memory exhausted"); }
    catch (const std::exception&) { fail("java/lang/IllegalStateException", "Portrait native failure"); return nullptr; }
    return nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_grapheneos_camera_capturer_RawNativeCore_refineMask(JNIEnv* env, jclass, jlong handle,
        jintArray source, jint width, jint height, jfloatArray mask, jint mw, jint mh) {
    auto fail = [&](const char* type, const char* text) { env->ThrowNew(env->FindClass(type), text); };
    if (!handle || !source || !mask || width < 2 || height < 2 || width > 512 || height > 512 ||
        mw < 2 || mh < 2 || mw > 512 || mh > 512 || env->GetArrayLength(source) != width * height ||
        env->GetArrayLength(mask) != mw * mh) {
        fail("java/lang/IllegalArgumentException", "Invalid live Portrait buffers"); return nullptr;
    }
    try {
        std::vector<uint32_t> pixels(size_t(width) * height);
        std::vector<float> matte(size_t(mw) * mh), output(size_t(width) * height);
        env->GetIntArrayRegion(source, 0, width * height, reinterpret_cast<jint*>(pixels.data()));
        env->GetFloatArrayRegion(mask, 0, mw * mh, matte.data());
        if (env->ExceptionCheck()) return nullptr;
        auto* cancel = reinterpret_cast<std::atomic_bool*>(static_cast<uintptr_t>(handle));
        if (!portrait::refineMask(pixels.data(), width, height, matte.data(), mw, mh, output.data(), *cancel)) {
            fail("java/lang/IllegalStateException", "Live Portrait refinement cancelled or invalid"); return nullptr;
        }
        jfloatArray result = env->NewFloatArray(width * height);
        if (result) env->SetFloatArrayRegion(result, 0, width * height, output.data());
        return result;
    } catch (const std::bad_alloc&) { fail("java/lang/OutOfMemoryError", "Live Portrait memory exhausted"); }
    catch (const std::exception&) { fail("java/lang/IllegalStateException", "Portrait native failure"); return nullptr; }
    return nullptr;
}
