cmake_minimum_required(VERSION 3.22.1)
project(armia LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(armia SHARED
    jni_bridge.cpp
    render_engine.cpp
    egl_core.cpp
    gl_util.cpp
    presets.cpp
    neon_stats.cpp)

target_compile_options(armia PRIVATE
    -O3
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden)

# Alvo é só ARM64 (arm64-v8a): NEON já vem ligado por padrão nesse ABI.
# max-page-size=16384 deixa a biblioteca compatível com aparelhos de página de 16 KB (Android 15+).
target_link_options(armia PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(armia
    android      # ANativeWindow, ASurfaceTexture
    log
    EGL
    GLESv3)
