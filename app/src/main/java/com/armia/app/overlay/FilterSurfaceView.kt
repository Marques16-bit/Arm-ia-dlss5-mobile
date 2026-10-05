package com.armia.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.armia.app.NativeEngine

/**
 * Superfície onde o motor nativo (OpenGL ES) desenha. Fica sozinha dentro de uma janela de
 * sobreposição. O tamanho do buffer é uma fração da tela ([setScale]): o sistema estica para
 * preencher a janela, então desenhar em resolução menor economiza GPU.
 */
@SuppressLint("ViewConstructor")
class FilterSurfaceView(
    context: Context,
    private val engine: NativeEngine
) : SurfaceView(context), SurfaceHolder.Callback {

    private var scale = 0.5f

    init {
        setZOrderOnTop(true)                       // necessário para a superfície ser translúcida
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    fun setScale(value: Float) {
        val v = value.coerceIn(0.2f, 1f)
        if (v == scale) return
        scale = v
        applyFixedSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFixedSize()
    }

    private fun applyFixedSize() {
        if (width <= 0 || height <= 0) return
        holder.setFixedSize(
            (width * scale).toInt().coerceAtLeast(16),
            (height * scale).toInt().coerceAtLeast(16)
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine.setSurface(null)   // só volta quando o motor parou de usar a superfície
    }
}
