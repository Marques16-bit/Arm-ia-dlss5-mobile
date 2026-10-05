# Arm-IA

Filtros visuais em tempo real para jogos no Android (ARM64), feitos por cima do jogo, **sem root**.

**Estado atual (v0.1):** a base funciona de ponta a ponta (perfis de jogos, detecção do jogo aberto,
bolinha flutuante, painel de ajustes, motor OpenGL ES em C++). O que roda de verdade hoje:

| Modo | O que faz | Observação |
|---|---|---|
| **Sobreposição** (padrão) | Grão de filme, vinheta, cor (tint) e barras de cinema | Leve, estável, funciona em qualquer jogo |
| **Laboratório de captura** (experimental) | Denoise, nitidez, cor, iluminação, auto-exposição | Precisa de captura de tela; latência de 1 a 3 quadros |

Não existe rede neural treinada nesta versão. Veja `docs/ARQUITETURA.md` para os limites reais.

## Estrutura

```
ArmIA/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ .github/workflows/        <- os 2 arquivos .yml (você cria pelo site, passo 2)
├─ docs/ARQUITETURA.md
└─ app/
   ├─ build.gradle.kts       (Kotlin + Compose + CMake/NDK, só arm64-v8a)
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/armia/app/
      │  ├─ MainActivity.kt, ArmIAApp.kt, NativeBridge.kt
      │  ├─ data/      (modelos, perfis em JSON, lista de apps)
      │  ├─ system/    (permissões, app em primeiro plano, temperatura)
      │  ├─ overlay/   (serviço, bolinha, painel, superfície GL)
      │  ├─ capture/   (laboratório de captura)
      │  └─ ui/        (tela principal em Compose)
      └─ cpp/          (CMakeLists, motor EGL/GLES3, shaders, presets, NEON)
```

## Como colocar no GitHub e compilar (tudo pelo celular)

1. **Repositório pronto.** Abra o seu repositório (o fork) no navegador do celular.
   Se o navegador mostrar a versão para celular, ative "Site para computador" no menu do navegador.

2. **Crie os 2 workflows** (só colar texto):
   - Toque em **Add file → Create new file**.
   - No nome, digite `.github/workflows/descompactar-zip.yml` e cole o conteúdo do arquivo
     `descompactar-zip.yml`. Toque em **Commit changes**.
   - Repita com o nome `.github/workflows/android-build.yml` e o conteúdo de `android-build.yml`.

3. **Envie o projeto.** **Add file → Upload files**, escolha `arm-ia.zip` e faça o commit na `main`.
   A aba **Actions** mostra "Descompactar projeto" rodando. Em ~30 segundos as pastas
   (`app/`, `docs/`, etc.) aparecem no repositório e o zip some.

4. **Compile.** Abra **Actions → Compilar APK → Run workflow**. A primeira vez leva uns 8 a 15 minutos.
   (Se o commit do passo 3 não disparou a compilação sozinho, é normal: use "Run workflow".)

5. **Baixe o APK.** Na página principal do repositório, entre em **Releases**, abra o build mais novo
   e toque em `ArmIA-debug.apk`. Instale (o Android pede para permitir instalar de fontes desconhecidas).

6. **Se a compilação falhar:** abra a execução com ❌, toque no passo vermelho e copie as últimas
   linhas do erro. Com elas dá para corrigir o ponto exato.

## Como usar o app

1. Abra o Arm-IA e libere **Sobrepor a outros apps**, **Acesso ao uso** e **Notificações**.
2. Em **Adicionar jogo**, toque no jogo. Escolha estilo, intensidade e modo.
3. Toque em **Ativar serviço**.
4. Abra o jogo. A bolinha aparece; toque nela para abrir o painel.

### Laboratório de captura

Pela bolinha: **Laboratório: prévia** (janela pequena) ou **tela cheia**. O Android pede permissão de
captura. No Android 14 ou mais novo, escolha **Compartilhar um app** e selecione o jogo: assim a captura
não inclui o filtro e não há efeito espelho. Se o seu aparelho só oferecer "tela inteira", use a prévia
pequena (a tela cheia vira um espelho dentro do espelho).

## Medir se está pesado

No painel, a linha de baixo mostra quadros por segundo do filtro e ms de CPU por quadro.
Se o aparelho esquentar, o motor reduz o ritmo sozinho (ThermalStatus).
Para ver os logs: `adb logcat -s ArmIA` (ou um app de logcat no próprio celular).
