LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := portrait_preview
LOCAL_SRC_FILES := preview_jni.cpp ../../../../app/src/main/cpp/portraitcore.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../app/src/main/cpp
LOCAL_CPPFLAGS += -std=c++17 -O3 -fexceptions -fvisibility=hidden -fstack-protector-strong
# Hardening: zeroed locals, stack clash probes and trapping bounds and integer overflow checks.
LOCAL_CFLAGS += -ftrivial-auto-var-init=zero -fstack-clash-protection
LOCAL_CFLAGS += -fsanitize=bounds,signed-integer-overflow,unsigned-integer-overflow -fsanitize-trap=all
LOCAL_CFLAGS += -Wall -Werror
LOCAL_LDFLAGS += -Wl,-z,relro,-z,now,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
