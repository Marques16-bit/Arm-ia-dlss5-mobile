#include "render_engine.h"

#include <algorithm>
#include <cmath>

#include "log.h"
#include "neon_stats.h"
#include "shaders.h"

namespace armia {
namespace {

constexpr int kStatsW = 32;            // tamanho da imagem usada para medir o brilho
constexpr int kStatsH = 18;
constexpr uint32_t kStatsEvery = 12;   // mede o brilho a cada N quadros (glReadPixels custa)
constexpr int kThermalLight = 1;       // PowerManager.THERMAL_STATUS_LIGHT
constexpr int kThermalModerate = 2;
constexpr int kThermalSevere = 3;

inline GLint U(GLuint prog, const char* name) { return glGetUniformLocation(prog, name); }

}  // namespace

RenderEngine::RenderEngine() : neural_(new NeuralStage()) {
    thread_ = std::thread(&RenderEngine::threadMain, this);
}

RenderEngine::~RenderEngine() {
    {
        std::lock_guard<std::mutex> lk(mu_);
        quit_ = true;
    }
    cv_.notify_all();
    ackCv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

// ------------------------------------------------------------------------------------------
// API pública (qualquer thread)
// ------------------------------------------------------------------------------------------

void RenderEngine::setSurface(ANativeWindow* window) {
    std::unique_lock<std::mutex> lk(mu_);
    if (pendingWindow_) ANativeWindow_release(pendingWindow_);  // trocada antes de ser aplicada
    pendingWindow_ = window;
    windowDirty_ = true;
    const uint64_t gen = ++windowGen_;
    cv_.notify_all();
    ackCv_.wait(lk, [&] { return windowAppliedGen_ >= gen || quit_ || initFailed_; });
}

void RenderEngine::setStyle(Style style, float intensity, Quality quality) {
    std::lock_guard<std::mutex> lk(mu_);
    params_ = makePreset(style, intensity, quality);
    paramsDirty_ = true;
    animated_ = params_.overlay.grain > 0.001f && thermal_ < kThermalSevere;
    cv_.notify_all();
}

void RenderEngine::setTargetFps(int fps) {
    std::lock_guard<std::mutex> lk(mu_);
    targetFps_ = std::min(60, std::max(5, fps));
    cv_.notify_all();
}

void RenderEngine::setThermalStatus(int status) {
    std::lock_guard<std::mutex> lk(mu_);
    thermal_ = status;
    animated_ = params_.overlay.grain > 0.001f && thermal_ < kThermalSevere;
    forceRedraw_ = true;
    cv_.notify_all();
}

void RenderEngine::setCaptureSource(ASurfaceTexture* st, int width, int height) {
    std::unique_lock<std::mutex> lk(mu_);
    if (pendingSt_) ASurfaceTexture_release(pendingSt_);
    pendingSt_ = st;
    pendingCapW_ = width;
    pendingCapH_ = height;
    captureDirty_ = true;
    const uint64_t gen = ++captureGen_;
    cv_.notify_all();
    ackCv_.wait(lk, [&] { return captureAppliedGen_ >= gen || quit_ || initFailed_; });
}

void RenderEngine::notifyFrameAvailable() {
    {
        std::lock_guard<std::mutex> lk(mu_);
        frameAvailable_ = true;
    }
    cv_.notify_all();
}

EngineStats RenderEngine::stats() const {
    EngineStats s;
    s.fps = statFps_.load();
    s.cpuMs = statCpuMs_.load();
    return s;
}

// FPS efetivo: o calor do aparelho reduz o ritmo (a cadeia inteira respeita isso).
int RenderEngine::effectiveFps() const {
    float scale = 1.f;
    if (thermal_ >= kThermalSevere) scale = 0.4f;
    else if (thermal_ >= kThermalModerate) scale = 0.6f;
    else if (thermal_ >= kThermalLight) scale = 0.85f;
    return std::max(5, static_cast<int>(std::lround(static_cast<float>(targetFps_) * scale)));
}

// ------------------------------------------------------------------------------------------
// Thread de render
// ------------------------------------------------------------------------------------------

bool RenderEngine::waitForWork(std::unique_lock<std::mutex>& lk, Clock::time_point nextFrame) {
    for (;;) {
        if (quit_) return false;
        if (windowDirty_ || captureDirty_) return true;
        if (!egl_.hasWindowSurface()) {
            cv_.wait(lk);
            continue;
        }
        if (currentSt_ != nullptr) {  // modo captura: só desenha quando chega quadro novo
            if (!frameAvailable_) {
                cv_.wait(lk);
                continue;
            }
            if (Clock::now() >= nextFrame) return true;
            cv_.wait_until(lk, nextFrame);
            continue;
        }
        // modo sobreposição
        if (paramsDirty_ || forceRedraw_) return true;
        if (animated_) {
            if (Clock::now() >= nextFrame) return true;
            cv_.wait_until(lk, nextFrame);
            continue;
        }
        cv_.wait(lk);  // efeito estático: dorme até algo mudar (custo zero de GPU)
    }
}

void RenderEngine::threadMain() {
    if (!egl_.init() || !initGlResources()) {
        LOGE("Motor de render não iniciou");
        std::unique_lock<std::mutex> lk(mu_);
        initFailed_ = true;
        ackCv_.notify_all();
        cv_.wait(lk, [this] { return quit_; });
        if (pendingWindow_) ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
        if (pendingSt_) ASurfaceTexture_release(pendingSt_);
        pendingSt_ = nullptr;
        return;
    }

    Clock::time_point nextFrame = Clock::now();
    uint32_t frame = 0;
    int statFrames = 0;
    double statCpu = 0.0;
    Clock::time_point statStart = Clock::now();

    for (;;) {
        ANativeWindow* newWindow = nullptr;
        bool applyWin = false;
        uint64_t winGen = 0;
        ASurfaceTexture* newSt = nullptr;
        bool applyCap = false;
        uint64_t capGen = 0;
        int capW = 0, capH = 0;
        EffectParams params;
        bool gotFrame = false;
        int fps = 24;

        {
            std::unique_lock<std::mutex> lk(mu_);
            if (!waitForWork(lk, nextFrame)) break;

            if (windowDirty_) {
                applyWin = true;
                newWindow = pendingWindow_;
                pendingWindow_ = nullptr;
                windowDirty_ = false;
                winGen = windowGen_;
            }
            if (captureDirty_) {
                applyCap = true;
                newSt = pendingSt_;
                pendingSt_ = nullptr;
                capW = pendingCapW_;
                capH = pendingCapH_;
                captureDirty_ = false;
                capGen = captureGen_;
            }
            params = params_;
            paramsDirty_ = false;
            forceRedraw_ = false;
            if (currentSt_ != nullptr && frameAvailable_) {
                gotFrame = true;
                frameAvailable_ = false;
            }
            fps = effectiveFps();
        }

        bool needDraw = false;
        if (applyWin) {
            applyWindowChange(newWindow);
            needDraw = true;
            std::lock_guard<std::mutex> lk(mu_);
            windowAppliedGen_ = winGen;
            ackCv_.notify_all();
        }
        if (applyCap) {
            applyCaptureChange(newSt, capW, capH);
            needDraw = true;
            std::lock_guard<std::mutex> lk(mu_);
            captureAppliedGen_ = capGen;
            ackCv_.notify_all();
        }

        if (!egl_.hasWindowSurface()) continue;

        const Clock::time_point t0 = Clock::now();
        bool drew = false;
        if (currentSt_ != nullptr) {
            if (gotFrame) drew = drawCapture(params.grade, frame);
            else if (needDraw) continue;  // capturou agora: espera o primeiro quadro chegar
        } else {
            drew = drawOverlay(params.overlay, frame);
        }

        if (drew) {
            egl_.swap();
            ++frame;
            ++statFrames;
            statCpu += std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
        }
        nextFrame = Clock::now() + std::chrono::microseconds(1000000 / std::max(1, fps));

        const double secs = std::chrono::duration<double>(Clock::now() - statStart).count();
        if (secs >= 1.0) {
            statFps_.store(static_cast<float>(statFrames / secs));
            statCpuMs_.store(statFrames > 0 ? static_cast<float>(statCpu / statFrames) : 0.f);
            statFrames = 0;
            statCpu = 0.0;
            statStart = Clock::now();
        }
    }

    // ---- encerramento: apaga tudo com o contexto ainda ativo ----
    applyCaptureChange(nullptr, 0, 0);
    egl_.destroyWindowSurface();
    if (currentWindow_) {
        ANativeWindow_release(currentWindow_);
        currentWindow_ = nullptr;
    }
    destroyGlResources();
    egl_.release();
    std::lock_guard<std::mutex> lk(mu_);
    if (pendingWindow_) ANativeWindow_release(pendingWindow_);
    pendingWindow_ = nullptr;
    if (pendingSt_) ASurfaceTexture_release(pendingSt_);
    pendingSt_ = nullptr;
}

bool RenderEngine::initGlResources() {
    using namespace shaders;

    overlay_.id = gl::buildProgram(kVertFullscreen, kFragOverlay, "overlay");
    copy_.id = gl::buildProgram(kVertFullscreenMat, kFragCopyOes, "copyOES");
    denoise_.id = gl::buildProgram(kVertFullscreen, kFragDenoise, "denoise");
    statsProg_.id = gl::buildProgram(kVertFullscreen, kFragStats, "stats");
    final_.id = gl::buildProgram(kVertFullscreen, kFragFinal, "final");

    // O modo sobreposição é o essencial. Se só os shaders de captura falharem (ex.: GPU sem
    // suporte a textura externa em ES3) o app continua funcionando sem o modo captura.
    if (!overlay_.id) return false;

    overlay_.res = U(overlay_.id, "uRes");
    overlay_.frame = U(overlay_.id, "uFrame");
    overlay_.grain = U(overlay_.id, "uGrain");
    overlay_.grainSize = U(overlay_.id, "uGrainSize");
    overlay_.vig = U(overlay_.id, "uVignette");
    overlay_.vigStart = U(overlay_.id, "uVignetteStart");
    overlay_.letterbox = U(overlay_.id, "uLetterbox");
    overlay_.tint = U(overlay_.id, "uTint");

    if (copy_.id) {
        copy_.texMatrix = U(copy_.id, "uTexMatrix");
        copy_.src = U(copy_.id, "uSrc");
    }
    if (denoise_.id) {
        denoise_.src = U(denoise_.id, "uSrc");
        denoise_.texel = U(denoise_.id, "uTexel");
        denoise_.strength = U(denoise_.id, "uStrength");
    }
    if (statsProg_.id) {
        statsProg_.src = U(statsProg_.id, "uSrc");
        statsProg_.lod = U(statsProg_.id, "uLod");
    }
    if (final_.id) {
        const GLuint p = final_.id;
        final_.src = U(p, "uSrc");
        final_.texel = U(p, "uTexel");
        final_.res = U(p, "uRes");
        final_.frame = U(p, "uFrame");
        final_.sharpen = U(p, "uSharpen");
        final_.exposure = U(p, "uExposure");
        final_.contrast = U(p, "uContrast");
        final_.saturation = U(p, "uSaturation");
        final_.vibrance = U(p, "uVibrance");
        final_.temperature = U(p, "uTemperature");
        final_.shadow = U(p, "uShadow");
        final_.highlight = U(p, "uHighlight");
        final_.grain = U(p, "uGrain");
        final_.vignette = U(p, "uVignette");
        final_.autoGain = U(p, "uAutoGain");
    }

    glGenVertexArrays(1, &vao_);
    statsBuf_.assign(static_cast<size_t>(kStatsW) * kStatsH * 4, 0);
    return true;
}

void RenderEngine::destroyGlResources() {
    if (overlay_.id) glDeleteProgram(overlay_.id);
    if (copy_.id) glDeleteProgram(copy_.id);
    if (denoise_.id) glDeleteProgram(denoise_.id);
    if (statsProg_.id) glDeleteProgram(statsProg_.id);
    if (final_.id) glDeleteProgram(final_.id);
    overlay_ = OverlayProg{};
    copy_ = CopyProg{};
    denoise_ = DenoiseProg{};
    statsProg_ = StatsProg{};
    final_ = FinalProg{};
    if (vao_) glDeleteVertexArrays(1, &vao_);
    vao_ = 0;
}

void RenderEngine::applyWindowChange(ANativeWindow* window) {
    egl_.destroyWindowSurface();
    if (currentWindow_) {
        ANativeWindow_release(currentWindow_);
        currentWindow_ = nullptr;
    }
    if (!window) return;
    if (egl_.createWindowSurface(window)) {
        currentWindow_ = window;
    } else {
        ANativeWindow_release(window);
    }
}

void RenderEngine::applyCaptureChange(ASurfaceTexture* st, int width, int height) {
    if (currentSt_) {
        ASurfaceTexture_detachFromGLContext(currentSt_);
        ASurfaceTexture_release(currentSt_);
        currentSt_ = nullptr;
    }
    gl::destroyRenderTarget(a_);
    gl::destroyRenderTarget(b_);
    gl::destroyRenderTarget(statsRt_);
    if (!st) return;

    if (!copy_.id || !final_.id || !denoise_.id || !statsProg_.id) {
        LOGE("Shaders de captura indisponíveis neste aparelho");
        ASurfaceTexture_release(st);
        return;
    }
    if (!oesTex_) {
        glGenTextures(1, &oesTex_);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTex_);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    if (ASurfaceTexture_attachToGLContext(st, oesTex_) != 0) {
        LOGE("ASurfaceTexture_attachToGLContext falhou");
        ASurfaceTexture_release(st);
        return;
    }
    if (!gl::createRenderTarget(a_, width, height, true) ||
        !gl::createRenderTarget(b_, width, height, true) ||
        !gl::createRenderTarget(statsRt_, kStatsW, kStatsH, false)) {
        LOGE("Não foi possível criar os alvos de render %dx%d", width, height);
        gl::destroyRenderTarget(a_);
        gl::destroyRenderTarget(b_);
        gl::destroyRenderTarget(statsRt_);
        ASurfaceTexture_detachFromGLContext(st);
        ASurfaceTexture_release(st);
        return;
    }
    currentSt_ = st;
    autoGain_ = autoGainTarget_ = 1.f;
    captureFrames_ = 0;
    LOGI("Captura ligada: %dx%d", width, height);
}

// ------------------------------------------------------------------------------------------
// Desenho
// ------------------------------------------------------------------------------------------

bool RenderEngine::drawOverlay(const OverlayParams& o, uint32_t frame) {
    const int w = egl_.width(), h = egl_.height();
    if (w <= 0 || h <= 0) return false;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, w, h);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glBindVertexArray(vao_);

    glUseProgram(overlay_.id);
    glUniform2f(overlay_.res, static_cast<float>(w), static_cast<float>(h));
    glUniform1ui(overlay_.frame, frame);
    glUniform1f(overlay_.grain, o.grain);
    glUniform1f(overlay_.grainSize, o.grainSize);
    glUniform1f(overlay_.vig, o.vignette);
    glUniform1f(overlay_.vigStart, o.vignetteStart);
    glUniform1f(overlay_.letterbox, o.letterbox);
    glUniform4f(overlay_.tint, o.tint[0], o.tint[1], o.tint[2], o.tint[3]);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    return true;
}

void RenderEngine::updateAutoGain(const gl::RenderTarget& src) {
    // 1) mipmaps da imagem atual  2) lê um nível pequeno para 32x18  3) NEON calcula a média
    glBindTexture(GL_TEXTURE_2D, src.tex);
    glGenerateMipmap(GL_TEXTURE_2D);

    glBindFramebuffer(GL_FRAMEBUFFER, statsRt_.fbo);
    glViewport(0, 0, kStatsW, kStatsH);
    glUseProgram(statsProg_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, src.tex);
    glUniform1i(statsProg_.src, 0);
    const float lod = std::floor(std::log2(std::max(1.f, static_cast<float>(src.width) / kStatsW)));
    glUniform1f(statsProg_.lod, lod);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glReadPixels(0, 0, kStatsW, kStatsH, GL_RGBA, GL_UNSIGNED_BYTE, statsBuf_.data());

    const float avg = averageLuma(statsBuf_.data(), kStatsW * kStatsH);
    // Alvo de brilho médio ~0.42 (em espaço gama). Limita o ganho para nunca "estourar" a cena.
    autoGainTarget_ = std::min(1.25f, std::max(0.8f, 0.42f / std::max(avg, 0.02f)));
}

bool RenderEngine::drawCapture(const GradeParams& g, uint32_t frame) {
    if (ASurfaceTexture_updateTexImage(currentSt_) != 0) return false;
    float tm[16];
    ASurfaceTexture_getTransformMatrix(currentSt_, tm);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glBindVertexArray(vao_);

    // Passo 1: textura externa -> A
    glBindFramebuffer(GL_FRAMEBUFFER, a_.fbo);
    glViewport(0, 0, a_.width, a_.height);
    glUseProgram(copy_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTex_);
    glUniform1i(copy_.src, 0);
    glUniformMatrix4fv(copy_.texMatrix, 1, GL_FALSE, tm);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    gl::RenderTarget* src = &a_;

    // Passo 2: denoise (A -> B)
    if (g.denoise > 0.01f) {
        glBindFramebuffer(GL_FRAMEBUFFER, b_.fbo);
        glViewport(0, 0, b_.width, b_.height);
        glUseProgram(denoise_.id);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, a_.tex);
        glUniform1i(denoise_.src, 0);
        glUniform2f(denoise_.texel, 1.f / a_.width, 1.f / a_.height);
        glUniform1f(denoise_.strength, g.denoise);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        src = &b_;
    }

    // Etapa neural (gancho): só entra se houver modelo disponível.
    if (neural_ && neural_->available()) {
        gl::RenderTarget* dst = (src == &a_) ? &b_ : &a_;
        if (neural_->process(src->tex, dst->fbo, dst->width, dst->height)) src = dst;
    }

    // Passo 3: medição de brilho (a cada N quadros)
    if (g.autoExposure > 0.01f && (captureFrames_ % kStatsEvery) == 0) updateAutoGain(*src);
    ++captureFrames_;
    autoGain_ += (autoGainTarget_ - autoGain_) * 0.08f;  // suaviza para não "pulsar"
    const float gain = 1.f + (autoGain_ - 1.f) * g.autoExposure;

    // Passo 4: nitidez + cor + iluminação + vinheta + grão -> janela
    const int w = egl_.width(), h = egl_.height();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, w, h);
    glUseProgram(final_.id);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, src->tex);
    glUniform1i(final_.src, 0);
    glUniform2f(final_.texel, 1.f / src->width, 1.f / src->height);
    glUniform2f(final_.res, static_cast<float>(w), static_cast<float>(h));
    glUniform1ui(final_.frame, frame);
    glUniform1f(final_.sharpen, g.sharpen);
    glUniform1f(final_.exposure, g.exposure);
    glUniform1f(final_.contrast, g.contrast);
    glUniform1f(final_.saturation, g.saturation);
    glUniform1f(final_.vibrance, g.vibrance);
    glUniform1f(final_.temperature, g.temperature);
    glUniform1f(final_.shadow, g.shadowLift);
    glUniform1f(final_.highlight, g.highlightRoll);
    glUniform1f(final_.grain, g.grain);
    glUniform1f(final_.vignette, g.vignette);
    glUniform1f(final_.autoGain, gain);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    return true;
}

}  // namespace armia
