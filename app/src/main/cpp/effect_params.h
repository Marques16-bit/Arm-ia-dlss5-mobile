#pragma once
// Parâmetros dos efeitos. Tudo aqui é "dado puro": o Kotlin escolhe estilo/intensidade/qualidade
// e o C++ traduz isso em números para os shaders (veja presets.cpp).

namespace armia {

// Os valores precisam bater com FilterStyle / PerformanceMode no Kotlin (data/Models.kt).
enum class Style : int { Off = 0, Photoreal = 1, Cinema = 2, RealLife = 3, VibrantHdr = 4, Film = 5, Dlss5Look = 6 };
constexpr int kStyleCount = 7;

// Quantos números o pacote de efeitos pode trocar por estilo (ordem em presets.cpp::applyOverrides).
constexpr int kTargetFieldCount = 24;
enum class Quality : int { Performance = 0, Balanced = 1, Quality = 2 };

// Modo SOBREPOSIÇÃO: uma camada translúcida desenhada por cima do jogo.
// Não lê os pixels do jogo, então custa quase nada e funciona sem root em qualquer jogo.
struct OverlayParams {
    float grain = 0.f;          // 0..1  opacidade máxima do ruído de filme
    float grainSize = 1.5f;     // tamanho do grão em pixels da superfície
    float vignette = 0.f;       // 0..1  escurecimento máximo nos cantos
    float vignetteStart = 0.55f;// 0..1  onde o escurecimento começa (0 = centro, 1 = canto)
    float letterbox = 0.f;      // fração da altura de CADA barra preta (0..0.2)
    float tint[4] = {0.f, 0.f, 0.f, 0.f};  // r, g, b (0..1) e força (alpha 0..1)
};

// Modo CAPTURA (laboratório): lê os pixels capturados e passa por denoise -> nitidez -> cor.
struct GradeParams {
    float denoise = 0.f;        // 0..1  suavização que preserva bordas
    float sharpen = 0.f;        // 0..1  nitidez adaptativa (estilo AMD CAS)
    float exposure = 0.f;       // em EV (stops)
    float contrast = 0.f;       // -1..1 curva em S
    float saturation = 1.f;     // 1 = neutro
    float vibrance = 0.f;       // 0..1  satura mais o que está apagado
    float temperature = 0.f;    // -1 (frio) .. 1 (quente)
    float shadowLift = 0.f;     // 0..1  abre sombras
    float highlightRoll = 0.f;  // 0..1  comprime realces (look HDR)
    float grain = 0.f;          // 0..1
    float vignette = 0.f;       // 0..1
    float autoExposure = 0.f;   // 0..1  quanto confiar na medição de brilho (NEON)
    float clarity = 0.f;        // 0..1.5 contraste local (realça textura sem mexer no tom geral)
    float bloom = 0.f;          // 0..1  brilho suave em volta das áreas claras
    float microShadow = 0.f;    // 0..1  escurece frestas/cantos (sensação de sombra de contato)
};

struct EffectParams {
    OverlayParams overlay;
    GradeParams grade;
};

// Valores de um estilo com intensidade = 1 (o "alvo" para onde a intensidade interpola).
struct StyleTarget {
    OverlayParams overlay;
    GradeParams grade;
};

StyleTarget defaultTarget(Style style);

// Troca os campos de `t` pelos de `values` (kTargetFieldCount floats). NaN/inf = mantém o padrão.
void applyOverrides(StyleTarget& t, const float* values);

// Converte (alvo, intensidade 0..1, qualidade) nos parâmetros finais.
EffectParams makePreset(const StyleTarget& target, float intensity, Quality quality);
EffectParams makePreset(Style style, float intensity, Quality quality);

}  // namespace armia
