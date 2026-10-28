LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := camera_processing
LOCAL_SRC_FILES := nightcore.cpp nightfinish.cpp photocolor.cpp night_jni.cpp portraitcore.cpp portrait_jni.cpp jpegburst.cpp
LOCAL_CPPFLAGS += -std=c++17 -O3 -fexceptions -fvisibility=hidden -fstack-protector-strong
# Hardening: zeroed locals, stack clash probes and trapping bounds and integer overflow checks.
LOCAL_CFLAGS += -ftrivial-auto-var-init=zero -fstack-clash-protection
LOCAL_CFLAGS += -fsanitize=bounds,signed-integer-overflow,unsigned-integer-overflow -fsanitize-trap=all
LOCAL_CFLAGS += -Wall -Werror
LOCAL_LDFLAGS += -Wl,-z,relro,-z,now,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
