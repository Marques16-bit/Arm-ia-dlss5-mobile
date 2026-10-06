#pragma once
// Todos os shaders do Arm-IA (GLSL ES 3.00). O "#version" precisa ser o primeiro caractere.
// Explicação de cada etapa: docs/ARQUITETURA.md

namespace armia::shaders {

// Triângulo que cobre a tela inteira (sem buffer de vértices): gl_VertexID gera 3 pontos.
static const char* const kVertFullscreen = R"GLSL(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)GLSL";

// Igual, mas aplica a matriz de transformação da textura externa (corrige inversão/corte).
static const char* const kVertFullscreenMat = R"GLSL(#version 300 es
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = (uTexMatrix * vec4(p, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)GLSL";

// ---------------------------------------------------------------------------------------------
// MODO SOBREPOSIÇÃO: saída em alpha PRÉ-MULTIPLICADO (é o que o SurfaceFlinger espera).
// Camadas, de baixo para cima: cor (tint) -> vinheta -> grão -> barras de cinema.
// ---------------------------------------------------------------------------------------------
static const char* const kFragOverlay = R"GLSL(#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
out vec4 outColor;

uniform vec2 uRes;
uniform uint uFrame;
uniform float uGrain;
uniform float uGrainSize;
uniform float uVignette;
uniform float uVignetteStart;
uniform float uLetterbox;
uniform vec4 uTint;   // rgb + força

// Hash inteiro PCG: ruído bom e barato, sem os artefatos de sin()/fract() em GPUs Mali.
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336653u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float rand01(uvec2 p, uint seed) {
    uint h = pcg(p.x + pcg(p.y + pcg(seed)));
    return float(h) * (1.0 / 4294967295.0);
}
// "over" com alpha pré-multiplicado: src por cima de dst.
vec4 over(vec4 src, vec4 dst) { return src + dst * (1.0 - src.a); }

void main() {
    vec4 acc = vec4(0.0);

    // 1) cor
    if (uTint.a > 0.0) {
        acc = over(vec4(uTint.rgb * uTint.a, uTint.a), acc);
    }

    // 2) vinheta (preto cuja opacidade cresce para os cantos; 1.0 = canto da tela)
    if (uVignette > 0.0) {
        float aspect = uRes.x / uRes.y;
        vec2 d = (vUv - 0.5) * vec2(aspect, 1.0);
        float r = length(d) / (0.5 * length(vec2(aspect, 1.0)));
        float v = smoothstep(uVignetteStart, 1.0, r) * uVignette;
        acc = over(vec4(0.0, 0.0, 0.0, v), acc);
    }

    // 3) grão de filme: cada célula vira um ponto claro ou escuro com opacidade aleatória
    if (uGrain > 0.0) {
        vec2 cell = floor(gl_FragCoord.xy / max(uGrainSize, 1.0));
        float n = rand01(uvec2(cell), uFrame) - 0.5;
        float a = abs(n) * 2.0 * uGrain;
        float c = n > 0.0 ? 1.0 : 0.0;
        acc = over(vec4(vec3(c) * a, a), acc);
    }

    // 4) barras de cinema
    if (uLetterbox > 0.0) {
        float bar = step(vUv.y, uLetterbox) + step(1.0 - uLetterbox, vUv.y);
        acc = over(vec4(0.0, 0.0, 0.0, clamp(bar, 0.0, 1.0)), acc);
    }

    // A janela de sobreposição é desenhada com alpha 0,8 (regra de toques do Android 12+).
    // Compensamos aqui para o efeito aparecer com a força que o preset pediu.
    outColor = min(acc * 1.25, vec4(1.0));
}
)GLSL";

// ---------------------------------------------------------------------------------------------
// MODO CAPTURA
// ---------------------------------------------------------------------------------------------

// Passo 1: copia a textura externa (buffer da captura) para uma textura normal RGBA8.
static const char* const kFragCopyOes = R"GLSL(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform samplerExternalOES uSrc;
void main() {
    outColor = vec4(texture(uSrc, vUv).rgb, 1.0);
}
)GLSL";

// Passo 2: denoise bilateral 3x3 (suaviza ruído sem borrar bordas: vizinhos de cor muito
// diferente recebem peso quase zero).
static const char* const kFragDenoise = R"GLSL(#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uStrength;   // 0..1

const vec2 OFFS[8] = vec2[8](
    vec2(-1.0, -1.0), vec2(0.0, -1.0), vec2(1.0, -1.0),
    vec2(-1.0,  0.0),                  vec2(1.0,  0.0),
    vec2(-1.0,  1.0), vec2(0.0,  1.0), vec2(1.0,  1.0));

void main() {
    vec3 c = textureLod(uSrc, vUv, 0.0).rgb;
    float sigma2 = mix(0.0010, 0.0120, uStrength);   // quanto de diferença de cor ainda "conta"
    vec3 acc = c;
    float wsum = 1.0;
    for (int i = 0; i < 8; ++i) {
        vec3 n = textureLod(uSrc, vUv + OFFS[i] * uTexel, 0.0).rgb;
        vec3 d = n - c;
        float w = exp(-dot(d, d) / sigma2);
        acc += n * w;
        wsum += w;
    }
    outColor = vec4(mix(c, acc / wsum, clamp(uStrength * 1.5, 0.0, 1.0)), 1.0);
}
)GLSL";

// Passo 3 (opcional): reduz a imagem a 32x18 lendo um nível de mipmap -> a CPU mede o brilho.
static const char* const kFragStats = R"GLSL(#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uSrc;
uniform float uLod;
void main() {
    outColor = vec4(textureLod(uSrc, vUv, uLod).rgb, 1.0);
}
)GLSL";

// Passo 4: o passe final. Nitidez adaptativa (inspirada no AMD CAS, licença MIT) + "neural look":
// contraste local (clarity), sombras de contato (microShadow), glow (bloom), cor e iluminação.
// Contraste local, glow e sombras usam níveis de mipmap da própria imagem como "desfoque barato".
static const char* const kFragFinal = R"GLSL(#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
out vec4 outColor;

uniform sampler2D uSrc;
uniform vec2 uTexel;     // 1 / tamanho da textura de entrada
uniform vec2 uRes;       // tamanho da saída
uniform uint uFrame;
uniform float uSharpen;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uTemperature;
uniform float uShadow;
uniform float uHighlight;
uniform float uGrain;
uniform float uVignette;
uniform float uAutoGain;
uniform float uClarity;
uniform float uBloom;
uniform float uMicro;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336653u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float rand01(uvec2 p, uint seed) {
    uint h = pcg(p.x + pcg(p.y + pcg(seed)));
    return float(h) * (1.0 / 4294967295.0);
}
vec3 tap(vec2 uv) { return textureLod(uSrc, uv, 0.0).rgb; }

void main() {
    vec2 uv = vUv;
    vec3 e = tap(uv);

    // --- realce de bordas (CAS simplificado) ---
    if (uSharpen > 0.0) {
        vec3 b = tap(uv + vec2(0.0, -uTexel.y));
        vec3 d = tap(uv + vec2(-uTexel.x, 0.0));
        vec3 f = tap(uv + vec2(uTexel.x, 0.0));
        vec3 h = tap(uv + vec2(0.0, uTexel.y));
        vec3 mn = min(e, min(min(b, d), min(f, h)));
        vec3 mx = max(e, max(max(b, d), max(f, h)));
        vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
        float peak = -1.0 / mix(8.0, 5.0, uSharpen);
        vec3 w = amp * peak;
        e = clamp(((b + d + f + h) * w + e) / (1.0 + 4.0 * w), 0.0, 1.0);
    }

    // --- "neural look": contraste local e sombras de contato ---
    if (uClarity > 0.0 || uMicro > 0.0) {
        vec3 blur = textureLod(uSrc, uv, 3.0).rgb;
        if (uClarity > 0.0) {
            // realça o que é mais fino que o desfoque, limitado para não criar halos
            vec3 detail = clamp(e - blur, -0.25, 0.25);
            e = clamp(e + detail * uClarity * 1.2, 0.0, 1.0);
        }
        if (uMicro > 0.0) {
            float diff = dot(e, LUMA) - dot(blur, LUMA);          // < 0: mais escuro que a vizinhança
            e *= 1.0 + uMicro * clamp(diff * 1.6, -0.40, 0.10);
        }
    }

    // --- iluminação ---
    vec3 c = e * exp2(uExposure) * uAutoGain;
    c *= vec3(1.0 + 0.12 * uTemperature, 1.0 + 0.02 * uTemperature, 1.0 - 0.14 * uTemperature);
    float l = dot(c, LUMA);
    c += uShadow * 0.12 * (1.0 - smoothstep(0.0, 0.45, l));           // abre sombras
    c *= 1.0 - 0.25 * uHighlight * smoothstep(0.65, 1.0, l);          // comprime realces

    // --- cor ---
    vec3 cc = clamp(c, 0.0, 1.0);
    vec3 sc = cc * cc * (3.0 - 2.0 * cc);                              // curva em S
    c = mix(cc, sc, uContrast);
    float lum = dot(c, LUMA);
    float satPx = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float k = uSaturation + uVibrance * (1.0 - satPx);                 // vibrance: poupa o que já é vivo
    c = mix(vec3(lum), c, k);

    // --- glow (bloom): só as áreas bem claras "vazam" luz ---
    if (uBloom > 0.0) {
        vec3 glow = textureLod(uSrc, uv, 4.0).rgb;
        c += uBloom * 0.8 * max(glow - vec3(0.60), vec3(0.0));
    }

    // --- vinheta ---
    if (uVignette > 0.0) {
        float aspect = uRes.x / uRes.y;
        vec2 dv = (uv - 0.5) * vec2(aspect, 1.0);
        float r = length(dv) / (0.5 * length(vec2(aspect, 1.0)));
        c *= 1.0 - uVignette * smoothstep(0.55, 1.0, r);
    }

    // --- grão (mais forte nos tons médios) ---
    if (uGrain > 0.0) {
        float n = rand01(uvec2(gl_FragCoord.xy), uFrame) - 0.5;
        float mid = 1.0 - abs(lum * 2.0 - 1.0);
        c += n * 2.0 * uGrain * mix(0.5, 1.0, mid);
    }

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
)GLSL";

// Passo 4 (RESERVA): versão sem contraste local/bloom/sombras. Só entra se a nova não compilar.
// Era: nitidez adaptativa (inspirada no AMD FidelityFX CAS, licença MIT) + correção de cor
// + iluminação + vinheta + grão, tudo num passe só para gastar menos banda de memória.
static const char* const kFragFinalSafe = R"GLSL(#version 300 es
precision highp float;
precision highp int;
in vec2 vUv;
out vec4 outColor;

uniform sampler2D uSrc;
uniform vec2 uTexel;     // 1 / tamanho da textura de entrada
uniform vec2 uRes;       // tamanho da saída
uniform uint uFrame;
uniform float uSharpen;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uTemperature;
uniform float uShadow;
uniform float uHighlight;
uniform float uGrain;
uniform float uVignette;
uniform float uAutoGain;

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336653u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float rand01(uvec2 p, uint seed) {
    uint h = pcg(p.x + pcg(p.y + pcg(seed)));
    return float(h) * (1.0 / 4294967295.0);
}
vec3 tap(vec2 uv) { return textureLod(uSrc, uv, 0.0).rgb; }

void main() {
    vec2 uv = vUv;
    vec3 e = tap(uv);

    // --- realce de bordas (CAS simplificado) ---
    if (uSharpen > 0.0) {
        vec3 b = tap(uv + vec2(0.0, -uTexel.y));
        vec3 d = tap(uv + vec2(-uTexel.x, 0.0));
        vec3 f = tap(uv + vec2(uTexel.x, 0.0));
        vec3 h = tap(uv + vec2(0.0, uTexel.y));
        vec3 mn = min(e, min(min(b, d), min(f, h)));
        vec3 mx = max(e, max(max(b, d), max(f, h)));
        // Onde já há muito contraste local (perto de 0 ou 1) a nitidez diminui: evita "halos".
        vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-4)), 0.0, 1.0));
        float peak = -1.0 / mix(8.0, 5.0, uSharpen);
        vec3 w = amp * peak;
        e = clamp(((b + d + f + h) * w + e) / (1.0 + 4.0 * w), 0.0, 1.0);
    }

    // --- iluminação ---
    vec3 c = e * exp2(uExposure) * uAutoGain;
    c *= vec3(1.0 + 0.12 * uTemperature, 1.0 + 0.02 * uTemperature, 1.0 - 0.14 * uTemperature);
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += uShadow * 0.12 * (1.0 - smoothstep(0.0, 0.45, l));           // abre sombras
    c *= 1.0 - 0.25 * uHighlight * smoothstep(0.65, 1.0, l);          // comprime realces

    // --- cor ---
    vec3 cc = clamp(c, 0.0, 1.0);
    vec3 sc = cc * cc * (3.0 - 2.0 * cc);                              // curva em S
    c = mix(cc, sc, uContrast);
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float satPx = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float k = uSaturation + uVibrance * (1.0 - satPx);                 // vibrance: poupa o que já é vivo
    c = mix(vec3(lum), c, k);

    // --- vinheta ---
    if (uVignette > 0.0) {
        float aspect = uRes.x / uRes.y;
        vec2 dv = (uv - 0.5) * vec2(aspect, 1.0);
        float r = length(dv) / (0.5 * length(vec2(aspect, 1.0)));
        c *= 1.0 - uVignette * smoothstep(0.55, 1.0, r);
    }

    // --- grão (mais forte nos tons médios) ---
    if (uGrain > 0.0) {
        float n = rand01(uvec2(gl_FragCoord.xy), uFrame) - 0.5;
        float mid = 1.0 - abs(lum * 2.0 - 1.0);
        c += n * 2.0 * uGrain * mix(0.5, 1.0, mid);
    }

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
)GLSL";

}  // namespace armia::shaders
