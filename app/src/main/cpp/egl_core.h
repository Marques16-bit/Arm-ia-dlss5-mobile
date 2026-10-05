#pragma once
#include <EGL/egl.h>
#include <android/native_window.h>

namespace armia {

// Contexto OpenGL ES 3 + superfície de janela. Quando não há janela, um pbuffer 1x1 mantém o
// contexto "ativo" para que os recursos de GL possam ser criados e apagados sem erro.
class EglCore {
public:
    EglCore() = default;
    ~EglCore() { release(); }
    EglCore(const EglCore&) = delete;
    EglCore& operator=(const EglCore&) = delete;

    bool init();
    bool createWindowSurface(ANativeWindow* window);
    void destroyWindowSurface();
    bool hasWindowSurface() const { return window_ != EGL_NO_SURFACE; }
    bool swap();
    void release();

    int width() const { return width_; }
    int height() const { return height_; }

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface window_ = EGL_NO_SURFACE;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLint visualId_ = 0;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace armia
