#include "neon_stats.h"

#include <cstddef>

#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#define ARMIA_HAS_NEON 1
#endif

namespace armia {

// Pesos inteiros de Rec.709 que somam 256: 0.2126*256 ~ 54, 0.7152*256 ~ 183, 0.0722*256 ~ 19.
static constexpr unsigned kWr = 54;
static constexpr unsigned kWg = 183;
static constexpr unsigned kWb = 19;

float averageLumaScalar(const uint8_t* rgba, int pixelCount) {
    if (!rgba || pixelCount <= 0) return 0.f;
    uint64_t sum = 0;
    for (int i = 0; i < pixelCount; ++i) {
        const uint8_t* p = rgba + static_cast<size_t>(i) * 4;
        sum += (kWr * p[0] + kWg * p[1] + kWb * p[2]) >> 8;
    }
    return static_cast<float>(sum) / (static_cast<float>(pixelCount) * 255.f);
}

float averageLuma(const uint8_t* rgba, int pixelCount) {
#ifdef ARMIA_HAS_NEON
    if (!rgba || pixelCount <= 0) return 0.f;

    const uint8x8_t wr = vdup_n_u8(kWr);
    const uint8x8_t wg = vdup_n_u8(kWg);
    const uint8x8_t wb = vdup_n_u8(kWb);
    uint32x4_t acc = vdupq_n_u32(0);

    int i = 0;
    for (; i + 16 <= pixelCount; i += 16) {
        // vld4q separa os canais: val[0]=R, val[1]=G, val[2]=B, val[3]=A (16 pixels).
        const uint8x16x4_t px = vld4q_u8(rgba + static_cast<size_t>(i) * 4);

        // luma*256 cabe em 16 bits (máx. 255*256 = 65280).
        uint16x8_t lo = vmull_u8(vget_low_u8(px.val[0]), wr);
        lo = vmlal_u8(lo, vget_low_u8(px.val[1]), wg);
        lo = vmlal_u8(lo, vget_low_u8(px.val[2]), wb);

        uint16x8_t hi = vmull_u8(vget_high_u8(px.val[0]), wr);
        hi = vmlal_u8(hi, vget_high_u8(px.val[1]), wg);
        hi = vmlal_u8(hi, vget_high_u8(px.val[2]), wb);

        // >> 8 e soma em 32 bits (soma de pares com acumulação).
        acc = vpadalq_u16(acc, vshrq_n_u16(lo, 8));
        acc = vpadalq_u16(acc, vshrq_n_u16(hi, 8));
    }

    uint64_t sum = vaddvq_u32(acc);
    for (; i < pixelCount; ++i) {  // sobra (menos de 16 pixels)
        const uint8_t* p = rgba + static_cast<size_t>(i) * 4;
        sum += (kWr * p[0] + kWg * p[1] + kWb * p[2]) >> 8;
    }
    return static_cast<float>(sum) / (static_cast<float>(pixelCount) * 255.f);
#else
    return averageLumaScalar(rgba, pixelCount);
#endif
}

}  // namespace armia
