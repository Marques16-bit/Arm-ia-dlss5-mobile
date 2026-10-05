#pragma once
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

namespace armia::gl {

// Compila e liga um programa. Devolve 0 se falhar (o motivo vai para o logcat).
GLuint buildProgram(const char* vertexSrc, const char* fragmentSrc, const char* debugName);

// Textura RGBA8 + framebuffer. Com mipmaps = true a textura tem a cadeia de mips alocada
// (usada só para medir o brilho médio com textureLod).
struct RenderTarget {
    GLuint tex = 0;
    GLuint fbo = 0;
    int width = 0;
    int height = 0;
};

bool createRenderTarget(RenderTarget& rt, int width, int height, bool mipmaps);
void destroyRenderTarget(RenderTarget& rt);

}  // namespace armia::gl
