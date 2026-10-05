#include "gl_util.h"

#include <algorithm>
#include <vector>

#include "log.h"

namespace armia::gl {
namespace {

GLuint compile(GLenum type, const char* src, const char* name) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = GL_FALSE;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint len = 0;
        glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(static_cast<size_t>(std::max(len, 1)));
        glGetShaderInfoLog(sh, len, nullptr, log.data());
        LOGE("Shader '%s' (%s) falhou: %s", name, type == GL_VERTEX_SHADER ? "vertex" : "fragment",
             log.data());
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

}  // namespace

GLuint buildProgram(const char* vertexSrc, const char* fragmentSrc, const char* debugName) {
    GLuint vs = compile(GL_VERTEX_SHADER, vertexSrc, debugName);
    if (!vs) return 0;
    GLuint fs = compile(GL_FRAGMENT_SHADER, fragmentSrc, debugName);
    if (!fs) {
        glDeleteShader(vs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = GL_FALSE;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint len = 0;
        glGetProgramiv(prog, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(static_cast<size_t>(std::max(len, 1)));
        glGetProgramInfoLog(prog, len, nullptr, log.data());
        LOGE("Link do programa '%s' falhou: %s", debugName, log.data());
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

bool createRenderTarget(RenderTarget& rt, int width, int height, bool mipmaps) {
    destroyRenderTarget(rt);
    if (width <= 0 || height <= 0) return false;

    GLint levels = 1;
    if (mipmaps) {
        int m = std::max(width, height);
        while (m > 1) {
            m >>= 1;
            ++levels;
        }
    }

    glGenTextures(1, &rt.tex);
    glBindTexture(GL_TEXTURE_2D, rt.tex);
    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, width, height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                    mipmaps ? GL_LINEAR_MIPMAP_NEAREST : GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &rt.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, rt.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, rt.tex, 0);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer %dx%d incompleto (0x%x)", width, height, status);
        destroyRenderTarget(rt);
        return false;
    }
    rt.width = width;
    rt.height = height;
    return true;
}

void destroyRenderTarget(RenderTarget& rt) {
    if (rt.fbo) glDeleteFramebuffers(1, &rt.fbo);
    if (rt.tex) glDeleteTextures(1, &rt.tex);
    rt = RenderTarget{};
}

}  // namespace armia::gl
