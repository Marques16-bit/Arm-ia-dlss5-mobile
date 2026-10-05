#pragma once
// Atalhos de log para o logcat (filtre por "ArmIA").
#include <android/log.h>

#define ARMIA_TAG "ArmIA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ARMIA_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, ARMIA_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ARMIA_TAG, __VA_ARGS__)
