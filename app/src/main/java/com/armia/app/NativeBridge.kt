package com.armia.app

import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * Funções nativas (C++). Os nomes aqui precisam bater com jni_bridge.cpp:
 * Java_com_armia_app_NativeBridge_<nome>.
 */
object NativeBridge {
    init {
        System.loadLibrary("armia")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeSetSurface(handle: Long, surface: Surface?)
    external fun nativeSetStyle(handle: Long, style: Int, intensity: Float, quality: Int)
    external fun nativeSetStyleTargets(handle: Long, style: Int, values: FloatArray?)
    external fun nativeSetTargetFps(handle: Long, fps: Int)
    external fun nativeSetThermalStatus(handle: Long, status: Int)
    external fun nativeSetCaptureSource(handle: Long, surfaceTexture: SurfaceTexture?, width: Int, height: Int)
    external fun nativeNotifyFrame(handle: Long)
    external fun nativeGetStats(handle: Long): FloatArray?
}

/**
 * Embrulho seguro em volta do motor nativo: depois de [close] qualquer chamada vira "não faz nada"
 * (evita crash se um callback atrasado chegar depois do fim).
 */
class NativeEngine {
    private var handle: Long = NativeBridge.nativeCreate()

    @Synchronized
    fun setSurface(surface: Surface?) {
        if (handle != 0L) NativeBridge.nativeSetSurface(handle, surface)
    }

    @Synchronized
    fun setStyle(styleId: Int, intensity: Float, qualityId: Int) {
        if (handle != 0L) NativeBridge.nativeSetStyle(handle, styleId, intensity, qualityId)
    }

    /** Números do estilo vindos do pacote de efeitos (24 valores; NaN mantém o padrão). null = padrão. */
    @Synchronized
    fun setStyleTargets(styleId: Int, values: FloatArray?) {
        if (handle != 0L) NativeBridge.nativeSetStyleTargets(handle, styleId, values)
    }

    @Synchronized
    fun setTargetFps(fps: Int) {
        if (handle != 0L) NativeBridge.nativeSetTargetFps(handle, fps)
    }

    @Synchronized
    fun setThermalStatus(status: Int) {
        if (handle != 0L) NativeBridge.nativeSetThermalStatus(handle, status)
    }

    @Synchronized
    fun setCaptureSource(texture: SurfaceTexture?, width: Int, height: Int) {
        if (handle != 0L) NativeBridge.nativeSetCaptureSource(handle, texture, width, height)
    }

    @Synchronized
    fun notifyFrame() {
        if (handle != 0L) NativeBridge.nativeNotifyFrame(handle)
    }

    /** [fps, ms de CPU por quadro] */
    @Synchronized
    fun stats(): FloatArray {
        if (handle == 0L) return FloatArray(2)
        return NativeBridge.nativeGetStats(handle) ?: FloatArray(2)
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroy(handle)
            handle = 0L
        }
    }
}
