#include "effect_params.h"

#include <algorithm>
#include <cmath>

namespace armia {
namespace {

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float clamp01(float v) { return std::min(1.f, std::max(0.f, v)); }

}  // namespace

// Valores "no máximo" (intensidade = 1) de cada estilo. Intensidade 0 = neutro.
// Estes números são o padrão de fábrica; o pacote de efeitos (pack.json) pode trocar qualquer um.
StyleTarget defaultTarget(Style s) {
    StyleTarget t;
    switch (s) {
        case Style::Photoreal:
            t.overlay = {0.06f, 1.3f, 0.28f, 0.6f, 0.0f,
                         {1.0f, 1.0f, 1.0f, 0.0f}};
            t.grade = {0.5f, 0.7f, 0.02f, 0.24f, 1.1f, 0.3f, 0.05f, 0.16f, 0.3f, 0.012f, 0.14f, 0.5f, 0.45f, 0.22f, 0.35f};
            break;
        case Style::Cinema:
            t.overlay = {0.09f, 1.6f, 0.55f, 0.45f, 0.11f,
                         {1.0f, 0.72f, 0.45f, 0.14f}};
            t.grade = {0.3f, 0.4f, 0.0f, 0.34f, 0.9f, 0.0f, 0.14f, 0.05f, 0.38f, 0.05f, 0.45f, 0.3f, 0.25f, 0.28f, 0.3f};
            break;
        case Style::RealLife:
            t.overlay = {0.03f, 1.4f, 0.2f, 0.6f, 0.0f,
                         {1.0f, 0.85f, 0.65f, 0.06f}};
            t.grade = {0.4f, 0.55f, 0.0f, 0.14f, 1.02f, 0.28f, 0.04f, 0.12f, 0.18f, 0.01f, 0.1f, 0.6f, 0.35f, 0.12f, 0.25f};
            break;
        case Style::VibrantHdr:
            t.overlay = {0.0f, 1.5f, 0.15f, 0.65f, 0.0f,
                         {1.0f, 1.0f, 1.0f, 0.0f}};
            t.grade = {0.3f, 0.6f, 0.06f, 0.34f, 1.38f, 0.5f, 0.0f, 0.24f, 0.5f, 0.0f, 0.06f, 0.7f, 0.55f, 0.38f, 0.3f};
            break;
        case Style::Film:
            t.overlay = {0.24f, 2.0f, 0.36f, 0.5f, 0.0f,
                         {1.0f, 0.8f, 0.5f, 0.08f}};
            t.grade = {0.2f, 0.2f, 0.0f, 0.24f, 0.88f, 0.0f, 0.16f, 0.1f, 0.32f, 0.16f, 0.32f, 0.2f, 0.15f, 0.18f, 0.2f};
            break;
        case Style::Dlss5Look:
            t.overlay = {0.04f, 1.3f, 0.26f, 0.58f, 0.0f,
                         {1.0f, 0.95f, 0.88f, 0.03f}};
            t.grade = {0.45f, 0.75f, 0.05f, 0.3f, 1.12f, 0.4f, 0.06f, 0.12f, 0.45f, 0.012f, 0.18f, 0.55f, 0.65f, 0.4f, 0.55f};
            break;
        case Style::Off:
        default:
            break;
    }
    return t;
}

void applyOverrides(StyleTarget& t, const float* v) {
    if (!v) return;
    auto take = [&](float& dst, int i) {
        if (std::isfinite(v[i])) dst = v[i];
    };
    int i = 0;
    take(t.overlay.grain, i++);
    take(t.overlay.grainSize, i++);
    take(t.overlay.vignette, i++);
    take(t.overlay.vignetteStart, i++);
    take(t.overlay.letterbox, i++);
    take(t.overlay.tint[0], i++);
    take(t.overlay.tint[1], i++);
    take(t.overlay.tint[2], i++);
    take(t.overlay.tint[3], i++);
    take(t.grade.denoise, i++);
    take(t.grade.sharpen, i++);
    take(t.grade.exposure, i++);
    take(t.grade.contrast, i++);
    take(t.grade.saturation, i++);
    take(t.grade.vibrance, i++);
    take(t.grade.temperature, i++);
    take(t.grade.shadowLift, i++);
    take(t.grade.highlightRoll, i++);
    take(t.grade.grain, i++);
    take(t.grade.vignette, i++);
    take(t.grade.autoExposure, i++);
    take(t.grade.clarity, i++);
    take(t.grade.bloom, i++);
    take(t.grade.microShadow, i++);
}

EffectParams makePreset(const StyleTarget& t, float intensity, Quality quality) {
    const float k = clamp01(intensity);

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
    p.grade.clarity = lerp(0.f, t.grade.clarity, k);
    p.grade.bloom = lerp(0.f, t.grade.bloom, k);
    p.grade.microShadow = lerp(0.f, t.grade.microShadow, k);

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

EffectParams makePreset(Style style, float intensity, Quality quality) {
    return makePreset(defaultTarget(style), intensity, quality);
}

}  // namespace armia
