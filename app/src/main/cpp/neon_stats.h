#pragma once
#include <cstdint>

namespace armia {

// Brilho médio (luma Rec.709, 0..1) de `pixelCount` pixels RGBA8.
// No ARM64 usa NEON (16 pixels por iteração); em outras arquiteturas cai na versão escalar.
float averageLuma(const uint8_t* rgba, int pixelCount);

// Versão escalar de referência (também usada para conferir a NEON).
float averageLumaScalar(const uint8_t* rgba, int pixelCount);

}  // namespace armia
