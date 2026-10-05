#pragma once
#include <GLES3/gl3.h>

namespace armia {

// Ponto de extensão para uma rede neural (TFLite/NNAPI) no meio da cadeia de captura.
// Nesta versão não há modelo: a implementação padrão diz "indisponível" e a cadeia
// segue só com shaders. Veja docs/ARQUITETURA.md (seção "Etapa neural") para os limites reais.
class NeuralStage {
public:
    virtual ~NeuralStage() = default;

    // true quando há um modelo carregado e rápido o bastante para rodar neste aparelho.
    virtual bool available() const { return false; }

    // Lê `inTex` (RGBA8) e escreve em `outFbo`. Devolve false se não conseguiu.
    virtual bool process(GLuint inTex, GLuint outFbo, int width, int height) {
        (void)inTex;
        (void)outFbo;
        (void)width;
        (void)height;
        return false;
    }
};

}  // namespace armia
