# Arquitetura do Arm-IA

Este documento explica o que foi pesquisado, o que foi construído e o que **não** dá para fazer sem root.

## 1. Como filtrar a imagem de OUTRO app no Android

| Método | Precisa de root? | Funciona hoje? | Observação |
|---|---|---|---|
| **A. Janela translúcida por cima do jogo** (`TYPE_APPLICATION_OVERLAY`) | Não | Sim | Não lê os pixels do jogo. Só dá para somar camadas: grão, vinheta, cor, barras. Não consegue aumentar contraste ou saturação. **É o modo "Sobreposição".** |
| **B. MediaProjection + janela de saída** | Não | Em parte | Lê os pixels (denoise, nitidez, cor de verdade). Custa 1 a 3 quadros de atraso. Jogos com `FLAG_SECURE` saem pretos. **É o "Laboratório de captura".** |
| **C. `AccessibilityService.takeScreenshot`** | Não | Não serve | Limitado a poucos quadros por segundo. |
| **D. Camada Vulkan** (`VK_LAYER_*`) | Em prática, sim | Não | O Android só carrega camadas em app com `debuggable=true` (ou com root). Jogos comuns não são debuggable. |
| **E. Gancho no `eglSwapBuffers` / `vkQueuePresentKHR`** (Zygisk/LD_PRELOAD, como o ReShade) | Sim | Só com root | É o caminho "de verdade" do DLSS/ReShade: roda dentro do jogo, sem atraso extra. |
| **F. Shizuku/ADB** (matriz de cor do SurfaceFlinger, captura excluindo camadas) | Não, mas exige ADB sem fio | Futuro | Dá cor de verdade em qualquer jogo com custo quase zero. Depende do aparelho. |

**Efeito espelho (método B).** Se a janela de saída cobre a tela e a captura inclui essa janela, o app só
enxerga a própria saída. Solução sem root: no Android 14 (QPR2) em diante, a captura pode ser de **um app
só** ("Compartilhar um app"); a documentação do Android diz que só o conteúdo do app escolhido é
compartilhado, sem barras nem notificações. Como a janela do Arm-IA não faz parte do app capturado,
ela fica fora da captura. Isso precisa ser **confirmado no seu aparelho**. Em "tela inteira" o espelho acontece.

**Toques passando pelo filtro.** No Android 12+, uma janela de outro app com `FLAG_NOT_TOUCHABLE` e opacidade
acima de 0,8 tem os toques bloqueados. Por isso as janelas de filtro usam `alpha = 0,8`. Efeito colateral: no
Laboratório em tela cheia, 20% da imagem original aparece por baixo (leve "fantasma" em movimento rápido).

## 2. Pipeline

### Modo Sobreposição (`kFragOverlay`)
Um passe só, em resolução reduzida (35% a 75% da tela, o sistema estica):
1. **Cor (tint)**: camada colorida com pouca opacidade (aquece/esfria).
2. **Vinheta**: preto cuja opacidade cresce para os cantos.
3. **Grão**: ruído por célula (hash PCG), ponto claro ou escuro. Muda a cada quadro (24 a 30 por segundo).
4. **Barras de cinema**.

Se não há grão, o motor desenha uma vez e dorme: **custo de GPU zero** até algo mudar.

### Modo Captura (`render_engine.cpp::drawCapture`)
```
Tela do jogo -> VirtualDisplay -> SurfaceTexture (OES)
   1. copia OES -> textura A (RGBA8)               kFragCopyOes
   2. denoise bilateral 3x3, A -> B                 kFragDenoise   (preserva bordas)
   3. [gancho neural]                               NeuralStage    (desligado)
   4. a cada 12 quadros: mede brilho (32x18, NEON) kFragStats + averageLuma
   5. passe final -> janela                         kFragFinal
        nitidez adaptativa (inspirada no AMD CAS)
        -> exposição + auto-exposição + temperatura
        -> abre sombras / comprime realces
        -> curva em S (contraste)
        -> saturação + vibrance
        -> vinheta -> grão
```
Mapeamento com o que você pediu:
- **Correção de cor**: temperatura, saturação, vibrance, contraste.
- **Realce de bordas**: nitidez adaptativa (menos halo onde já há contraste).
- **Ajuste de iluminação**: exposição, sombras, realces, auto-exposição.
- **Denoise**: bilateral 3x3. **Upscale**: captura em 40% a 75% da tela, saída maior, interpolação bilinear + nitidez
  (é "upscale simples", não é DLSS).

Os números de cada estilo estão em `cpp/presets.cpp` (um lugar só para ajustar o "look").

## 3. Rede neural: o que é realista

O gancho existe (`neural_stage.h`) mas **não há modelo**. Motivos honestos:
- Um aparelho de entrada (por exemplo Helio G85 / Mali-G52) não tem NPU útil. Rede neural rodaria na GPU,
  a mesma que está desenhando o jogo. Isso baixa o FPS do jogo.
- DLSS e PSSR usam vetores de movimento e profundidade **dentro do jogo**. Por fora, só se tem a imagem final.
- Referência de tamanho que ainda cabe: super-resolução tipo FSRCNN/ESPCN, uns 20 mil parâmetros, int8,
  só no canal de brilho (Y), em 540x240 ou menos, via TFLite com delegate de GPU/NNAPI.
  Esperar de 10 a 20 quadros por segundo da rede, não 60.

Plano: primeiro validar o modo Captura com shaders; só depois testar um modelo mínimo atrás do `NeuralStage`.

## 4. Otimizações ARM64 (o que realmente está aplicado)

- **Só `arm64-v8a`**: NEON já vem ligado. A única parte de CPU pesada (média de brilho) usa NEON
  (`neon_stats.cpp`, 16 pixels por vez, com versão escalar de conferência).
- **Trabalho na GPU, não na CPU**: nenhum pixel passa pela CPU, exceto 32x18 para medir brilho (a cada 12 quadros).
- **Resolução reduzida**: filtro desenhado em 35% a 75% da tela.
- **Dormir quando parado**: efeito estático = zero quadros.
- **Ritmo adaptativo ao calor**: `PowerManager` avisa o `ThermalStatus`; o motor reduz o FPS (85%, 60%, 40%) e
  para de animar o grão em estado severo.
- **Um passe final** reúne nitidez + cor + vinheta + grão (menos leitura/escrita de memória).
- **Sem alocação por quadro**: texturas e programas são criados uma vez.
- **Página de 16 KB**: `-Wl,-z,max-page-size=16384` (Android 15+).

O que NÃO fazer: render em resolução cheia, vários passes de blur grandes, `glReadPixels` por quadro,
ou ligar o Laboratório junto com a Sobreposição (o app já desliga a Sobreposição enquanto o Laboratório roda).

## 5. Limitações conhecidas (v0.1)

- Latência de 1 a 3 quadros no Laboratório: ruim para jogos de ritmo e competitivos.
- Jogos que bloqueiam captura aparecem pretos.
- Sem som/entrada tratados: o Arm-IA só mexe na imagem.
- `Fotorrealista` e `Vibrante/HDR` só fazem sentido no Laboratório. Na Sobreposição são aproximações leves.
- Código ainda **sem teste em aparelho real** (veja o README para reportar erros de compilação ou travamentos).

## 6. Próximos passos sugeridos

1. Compilar e abrir o app; testar a Sobreposição em 1 jogo.
2. Testar o Laboratório na prévia, depois em tela cheia com "Compartilhar um app".
3. Medir FPS do jogo com e sem Arm-IA.
4. Decidir entre Shizuku (matriz de cor) ou root (gancho EGL) para filtros de cor sem custo.
