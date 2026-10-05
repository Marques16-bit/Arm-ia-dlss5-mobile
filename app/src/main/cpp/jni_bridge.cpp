// Ponte JNI: o Kotlin (NativeBridge.kt) conversa com o RenderEngine por aqui.
// O "handle" é só o ponteiro do RenderEngine guardado num Long.
#include <android/native_window_jni.h>
#include <android/surface_texture_jni.h>
#include <jni.h>

#include "log.h"
#include "render_engine.h"

using armia::Quality;
using armia::RenderEngine;
using armia::Style;

namespace {
inline RenderEngine* engine(jlong handle) { return reinterpret_cast<RenderEngine*>(handle); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_armia_app_NativeBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new RenderEngine());
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeDestroy(JNIEnv*, jobject,
                                                                     jlong handle) {
    delete engine(handle);
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeSetSurface(JNIEnv* env, jobject,
                                                                        jlong handle,
                                                                        jobject surface) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    engine(handle)->setSurface(window);  // o engine assume a referência
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeSetStyle(JNIEnv*, jobject,
                                                                      jlong handle, jint style,
                                                                      jfloat intensity,
                                                                      jint quality) {
    engine(handle)->setStyle(static_cast<Style>(style), intensity, static_cast<Quality>(quality));
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeSetTargetFps(JNIEnv*, jobject,
                                                                          jlong handle,
                                                                          jint fps) {
    engine(handle)->setTargetFps(fps);
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeSetThermalStatus(JNIEnv*, jobject,
                                                                              jlong handle,
                                                                              jint status) {
    engine(handle)->setThermalStatus(status);
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeSetCaptureSource(
    JNIEnv* env, jobject, jlong handle, jobject surfaceTexture, jint width, jint height) {
    ASurfaceTexture* st =
        surfaceTexture ? ASurfaceTexture_fromSurfaceTexture(env, surfaceTexture) : nullptr;
    engine(handle)->setCaptureSource(st, width, height);  // o engine assume a referência
}

JNIEXPORT void JNICALL Java_com_armia_app_NativeBridge_nativeNotifyFrame(JNIEnv*, jobject,
                                                                         jlong handle) {
    engine(handle)->notifyFrameAvailable();
}

JNIEXPORT jfloatArray JNICALL Java_com_armia_app_NativeBridge_nativeGetStats(JNIEnv* env,
                                                                             jobject,
                                                                             jlong handle) {
    const armia::EngineStats s = engine(handle)->stats();
    jfloatArray out = env->NewFloatArray(2);
    if (!out) return nullptr;
    const jfloat values[2] = {s.fps, s.cpuMs};
    env->SetFloatArrayRegion(out, 0, 2, values);
    return out;
}

}  // extern "C"
