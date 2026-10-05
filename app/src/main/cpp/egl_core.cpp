#include "egl_core.h"

#include "log.h"

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

namespace armia {

bool EglCore::init() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay falhou");
        return false;
    }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(display_, &major, &minor)) {
        LOGE("eglInitialize falhou (0x%x)", eglGetError());
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE};
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig falhou (0x%x)", eglGetError());
        return false;
    }
    eglGetConfigAttrib(display_, config_, EGL_NATIVE_VISUAL_ID, &visualId_);

    const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext (ES3) falhou (0x%x)", eglGetError());
        return false;
    }

    const EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttribs);
    if (pbuffer_ == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface falhou (0x%x)", eglGetError());
        return false;
    }
    if (!eglMakeCurrent(display_, pbuffer_, pbuffer_, context_)) {
        LOGE("eglMakeCurrent (pbuffer) falhou (0x%x)", eglGetError());
        return false;
    }
    LOGI("EGL %d.%d pronto", major, minor);
    return true;
}

bool EglCore::createWindowSurface(ANativeWindow* window) {
    destroyWindowSurface();
    if (!window || display_ == EGL_NO_DISPLAY) return false;

    // O formato do buffer da janela precisa combinar com a config escolhida (RGBA8888).
    ANativeWindow_setBuffersGeometry(window, 0, 0, visualId_);
    window_ = eglCreateWindowSurface(display_, config_, window, nullptr);
    if (window_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface falhou (0x%x)", eglGetError());
        return false;
    }
    if (!eglMakeCurrent(display_, window_, window_, context_)) {
        LOGE("eglMakeCurrent (janela) falhou (0x%x)", eglGetError());
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        eglDestroySurface(display_, window_);
        window_ = EGL_NO_SURFACE;
        return false;
    }
    EGLint w = 0, h = 0;
    eglQuerySurface(display_, window_, EGL_WIDTH, &w);
    eglQuerySurface(display_, window_, EGL_HEIGHT, &h);
    width_ = w;
    height_ = h;
    eglSwapInterval(display_, 1);
    LOGI("Superfície criada: %dx%d", width_, height_);
    return true;
}

void EglCore::destroyWindowSurface() {
    if (window_ == EGL_NO_SURFACE) return;
    eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
    eglDestroySurface(display_, window_);
    window_ = EGL_NO_SURFACE;
    width_ = height_ = 0;
}

bool EglCore::swap() {
    if (window_ == EGL_NO_SURFACE) return false;
    if (!eglSwapBuffers(display_, window_)) {
        LOGW("eglSwapBuffers falhou (0x%x)", eglGetError());
        return false;
    }
    return true;
}

void EglCore::release() {
    if (display_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (window_ != EGL_NO_SURFACE) eglDestroySurface(display_, window_);
    if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    window_ = EGL_NO_SURFACE;
    pbuffer_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
    display_ = EGL_NO_DISPLAY;
    width_ = height_ = 0;
}

}  // namespace armia
