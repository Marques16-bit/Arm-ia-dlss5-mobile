#pragma once
#include <android/native_window.h>
#include <android/surface_texture.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "effect_params.h"
#include "egl_core.h"
#include "gl_util.h"
#include "neural_stage.h"

namespace armia {

struct EngineStats {
    float fps = 0.f;    // quadros desenhados por segundo (medido)
    float cpuMs = 0.f;  // tempo médio de CPU por quadro (desenho + troca de buffers)
};

// Dono da thread de renderização. Todas as chamadas públicas podem vir de qualquer thread;
// todo o OpenGL acontece dentro da thread interna.
//
//  - Modo SOBREPOSIÇÃO (padrão): desenha efeitos translúcidos (grão, vinheta, cor, barras).
//  - Modo CAPTURA: quando recebe uma ASurfaceTexture, lê os quadros capturados e roda a cadeia
//    cópia -> denoise -> (medição de brilho) -> nitidez/cor/vinheta/grão.
class RenderEngine {
public:
    RenderEngine();
    ~RenderEngine();
    RenderEngine(const RenderEngine&) = delete;
    RenderEngine& operator=(const RenderEngine&) = delete;

    // Assume a posse da referência de `window` (já adquirida). nullptr = solta a superfície.
    // Só retorna depois que a thread de render parou de usar a janela anterior.
    void setSurface(ANativeWindow* window);

    void setStyle(Style style, float intensity, Quality quality);
    void setTargetFps(int fps);
    // Valores de PowerManager.THERMAL_STATUS_* (0 = nenhum ... 6 = desligamento).
    void setThermalStatus(int status);

    // Assume a posse de `st`. nullptr desliga o modo captura (bloqueia até a thread soltar).
    void setCaptureSource(ASurfaceTexture* st, int width, int height);
    void notifyFrameAvailable();

    EngineStats stats() const;

private:
    using Clock = std::chrono::steady_clock;

    struct OverlayProg {
        GLuint id = 0;
        GLint res = -1, frame = -1, grain = -1, grainSize = -1, vig = -1, vigStart = -1,
              letterbox = -1, tint = -1;
    };
    struct CopyProg { GLuint id = 0; GLint texMatrix = -1, src = -1; };
    struct DenoiseProg { GLuint id = 0; GLint src = -1, texel = -1, strength = -1; };
    struct StatsProg { GLuint id = 0; GLint src = -1, lod = -1; };
    struct FinalProg {
        GLuint id = 0;
        GLint src = -1, texel = -1, res = -1, frame = -1, sharpen = -1, exposure = -1,
              contrast = -1, saturation = -1, vibrance = -1, temperature = -1, shadow = -1,
              highlight = -1, grain = -1, vignette = -1, autoGain = -1;
    };

    void threadMain();
    bool waitForWork(std::unique_lock<std::mutex>& lk, Clock::time_point nextFrame);

    bool initGlResources();
    void destroyGlResources();
    void applyWindowChange(ANativeWindow* window);
    void applyCaptureChange(ASurfaceTexture* st, int width, int height);

    bool drawOverlay(const OverlayParams& o, uint32_t frame);
    bool drawCapture(const GradeParams& g, uint32_t frame);
    void updateAutoGain(const gl::RenderTarget& src);
    int effectiveFps() const;  // chamar com mu_ travado

    // ---- estado compartilhado (protegido por mu_) ----
    mutable std::mutex mu_;
    std::condition_variable cv_;     // acorda a thread de render
    std::condition_variable ackCv_;  // acorda quem espera a thread aplicar uma troca
    bool quit_ = false;
    bool initFailed_ = false;
    ANativeWindow* pendingWindow_ = nullptr;
    bool windowDirty_ = false;
    uint64_t windowGen_ = 0;
    uint64_t windowAppliedGen_ = 0;
    ASurfaceTexture* pendingSt_ = nullptr;
    int pendingCapW_ = 0, pendingCapH_ = 0;
    bool captureDirty_ = false;
    uint64_t captureGen_ = 0;
    uint64_t captureAppliedGen_ = 0;
    EffectParams params_;
    bool paramsDirty_ = true;
    bool forceRedraw_ = false;
    bool animated_ = false;
    bool frameAvailable_ = false;
    int targetFps_ = 24;
    int thermal_ = 0;

    // ---- estado só da thread de render ----
    EglCore egl_;
    ANativeWindow* currentWindow_ = nullptr;
    ASurfaceTexture* currentSt_ = nullptr;
    GLuint vao_ = 0;
    GLuint oesTex_ = 0;
    OverlayProg overlay_;
    CopyProg copy_;
    DenoiseProg denoise_;
    StatsProg statsProg_;
    FinalProg final_;
    gl::RenderTarget a_, b_, statsRt_;
    std::vector<uint8_t> statsBuf_;
    float autoGain_ = 1.f;
    float autoGainTarget_ = 1.f;
    uint32_t captureFrames_ = 0;
    std::unique_ptr<NeuralStage> neural_;

    // ---- estatísticas ----
    std::atomic<float> statFps_{0.f};
    std::atomic<float> statCpuMs_{0.f};

    std::thread thread_;
};

}  // namespace armia
