package com.armia.app.data

/** Os ids precisam bater com enum class Style (effect_params.h). */
enum class FilterStyle(val id: Int, val label: String, val needsCapture: Boolean) {
    OFF(0, "Desligado", false),
    PHOTOREAL(1, "Fotorrealista", true),
    CINEMA(2, "Cinema", false),
    REAL_LIFE(3, "Vida Real", false),
    VIBRANT_HDR(4, "Vibrante/HDR", true),
    FILM(5, "Filme/Grain", false);

    companion object {
        fun fromId(id: Int): FilterStyle = values().firstOrNull { it.id == id } ?: CINEMA
    }
}

/**
 * Ids batem com enum class Quality (effect_params.h).
 * overlayScale: fração da resolução da tela usada para desenhar os efeitos (o sistema estica).
 * captureScale: fração da resolução da tela capturada no modo laboratório.
 */
enum class PerformanceMode(
    val id: Int,
    val label: String,
    val overlayFps: Int,
    val overlayScale: Float,
    val captureFps: Int,
    val captureScale: Float
) {
    PERFORMANCE(0, "Performance", 15, 0.35f, 24, 0.40f),
    BALANCED(1, "Equilibrado", 24, 0.50f, 30, 0.50f),
    QUALITY(2, "Qualidade", 30, 0.75f, 30, 0.75f);

    /** Resolução da imagem final no laboratório: um pouco maior que a captura (upscale + nitidez). */
    val captureOutputScale: Float get() = (captureScale * 1.5f).coerceAtMost(1f)

    companion object {
        fun fromId(id: Int): PerformanceMode = values().firstOrNull { it.id == id } ?: BALANCED
    }
}

data class GameProfile(
    val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val style: FilterStyle = FilterStyle.CINEMA,
    val intensity: Float = 0.6f,
    val mode: PerformanceMode = PerformanceMode.BALANCED
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isLikelyGame: Boolean
)
