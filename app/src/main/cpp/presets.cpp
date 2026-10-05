#include "effect_params.h"

#include <algorithm>

namespace armia {
namespace {

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float clamp01(float v) { return std::min(1.f, std::max(0.f, v)); }

// Valores "no máximo" (intensidade = 1) de cada estilo. Intensidade 0 = neutro.
struct StyleTarget {
    OverlayParams overlay;
    GradeParams grade;
};

StyleTarget targetFor(Style s) {
    StyleTarget t;
    switch (s) {
        case Style::Photoreal:
            // Sobreposição só consegue aproximar: vinheta leve + grão fino.
            t.overlay.vignette = 0.22f;
            t.overlay.vignetteStart = 0.60f;
            t.overlay.grain = 0.05f;
            t.overlay.grainSize = 1.3f;
            t.grade = {/*denoise*/ 0.50f, /*sharpen*/ 0.60f, /*exposure*/ 0.00f, /*contrast*/ 0.18f,
                       /*saturation*/ 1.06f, /*vibrance*/ 0.20f, /*temperature*/ 0.05f,
                       /*shadowLift*/ 0.18f, /*highlightRoll*/ 0.25f, /*grain*/ 0.015f,
                       /*vignette*/ 0.12f, /*autoExposure*/ 0.50f};
            break;
        case Style::Cinema:
            t.overlay.letterbox = 0.10f;
            t.overlay.vignette = 0.45f;
            t.overlay.vignetteStart = 0.50f;
            t.overlay.grain = 0.07f;
            t.overlay.grainSize = 1.6f;
            t.overlay.tint[0] = 1.00f; t.overlay.tint[1] = 0.72f;
            t.overlay.tint[2] = 0.45f; t.overlay.tint[3] = 0.10f;
            t.grade = {0.30f, 0.35f, 0.00f, 0.30f, 0.92f, 0.00f, 0.12f,
                       0.05f, 0.35f, 0.05f, 0.40f, 0.30f};
            break;
        case Style::RealLife:
            t.overlay.vignette = 0.15f;
            t.overlay.vignetteStart = 0.62f;
            t.overlay.grain = 0.02f;
            t.overlay.tint[0] = 1.00f; t.overlay.tint[1] = 0.85f;
            t.overlay.tint[2] = 0.65f; t.overlay.tint[3] = 0.04f;
            t.grade = {0.40f, 0.50f, 0.00f, 0.12f, 1.00f, 0.25f, 0.03f,
                       0.12f, 0.15f, 0.01f, 0.08f, 0.60f};
            break;
        case Style::VibrantHdr:
            // Saturar/contrastar de verdade exige ler os pixels (modo captura).
            t.overlay.vignette = 0.12f;
            t.overlay.vignetteStart = 0.65f;
            t.grade = {0.30f, 0.55f, 0.05f, 0.28f, 1.25f, 0.45f, 0.00f,
                       0.22f, 0.45f, 0.00f, 0.05f, 0.70f};
            break;
        case Style::Film:
            t.overlay.grain = 0.20f;
            t.overlay.grainSize = 2.0f;
            t.overlay.vignette = 0.30f;
            t.overlay.vignetteStart = 0.55f;
            t.overlay.tint[0] = 1.00f; t.overlay.tint[1] = 0.80f;
            t.overlay.tint[2] = 0.50f; t.overlay.tint[3] = 0.06f;
            t.grade = {0.20f, 0.20f, 0.00f, 0.22f, 0.88f, 0.00f, 0.15f,
                       0.10f, 0.30f, 0.16f, 0.30f, 0.20f};
            break;
        case Style::Off:
        default:
            break;
    }
    return t;
}

}  // namespace

EffectParams makePreset(Style style, float intensity, Quality quality) {
    const float k = clamp01(intensity);
    const StyleTarget t = targetFor(style);

    EffectParams p;  // começa neutro

    // --- sobreposição ---
    p.overlay.grain = lerp(0.f, t.overlay.grain, k);
    p.overlay.grainSize = t.overlay.grainSize > 0.f ? t.overlay.grainSize : 1.5f;
    p.overlay.vignette = lerp(0.f, t.overlay.vignette, k);
    p.overlay.vignetteStart = t.overlay.vignetteStart;
    p.overlay.letterbox = lerp(0.f, t.overlay.letterbox, k);
    for (int i = 0; i < 3; ++i) p.overlay.tint[i] = t.overlay.tint[i];
    p.overlay.tint[3] = lerp(0.f, t.overlay.tint[3], k);

    // --- captura ---
    p.grade.denoise = lerp(0.f, t.grade.denoise, k);
    p.grade.sharpen = lerp(0.f, t.grade.sharpen, k);
    p.grade.exposure = lerp(0.f, t.grade.exposure, k);
    p.grade.contrast = lerp(0.f, t.grade.contrast, k);
    p.grade.saturation = lerp(1.f, t.grade.saturation, k);
    p.grade.vibrance = lerp(0.f, t.grade.vibrance, k);
    p.grade.temperature = lerp(0.f, t.grade.temperature, k);
    p.grade.shadowLift = lerp(0.f, t.grade.shadowLift, k);
    p.grade.highlightRoll = lerp(0.f, t.grade.highlightRoll, k);
    p.grade.grain = lerp(0.f, t.grade.grain, k);
    p.grade.vignette = lerp(0.f, t.grade.vignette, k);
    p.grade.autoExposure = lerp(0.f, t.grade.autoExposure, k);

    // Qualidade troca custo por beleza: o denoise é o passo mais caro depois da cópia.
    switch (quality) {
        case Quality::Performance:
            p.grade.denoise = 0.f;
            p.grade.autoExposure = 0.f;
            break;
        case Quality::Balanced:
            p.grade.denoise *= 0.7f;
            break;
        case Quality::Quality:
        default:
            break;
    }
    return p;
}

}  // namespace armia
