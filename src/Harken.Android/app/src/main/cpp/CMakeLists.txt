# Builds the vendored whisper.cpp (ggml + whisper core) into a shared library
# consumed by harken_whisper_jni.cpp, plus the JNI bridge itself.
#
# Vendored from https://github.com/ggml-org/whisper.cpp, tag v1.7.6
# (commit a8d002cfd879315632a579e73f0148d06959de36). See whisper/ for the
# vendored ggml/ and src/ trees copied from that release — examples/,
# bindings/, and other-language directories were intentionally not vendored.
# Only the CPU backend is built (no CUDA/Metal/Vulkan/etc — not applicable on
# Android), targeting arm64-v8a only per app/build.gradle.kts's abiFilters.

cmake_minimum_required(VERSION 3.22)
project(harken_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)
set(GGML_DIR ${WHISPER_DIR}/ggml)

add_library(ggml STATIC
    ${GGML_DIR}/src/ggml.c
    ${GGML_DIR}/src/ggml.cpp
    ${GGML_DIR}/src/ggml-alloc.c
    ${GGML_DIR}/src/ggml-backend.cpp
    ${GGML_DIR}/src/ggml-backend-reg.cpp
    ${GGML_DIR}/src/ggml-opt.cpp
    ${GGML_DIR}/src/ggml-quants.c
    ${GGML_DIR}/src/ggml-threading.cpp
    ${GGML_DIR}/src/gguf.cpp
    ${GGML_DIR}/src/ggml-cpu/ggml-cpu.c
    ${GGML_DIR}/src/ggml-cpu/ggml-cpu.cpp
    ${GGML_DIR}/src/ggml-cpu/repack.cpp
    ${GGML_DIR}/src/ggml-cpu/hbm.cpp
    ${GGML_DIR}/src/ggml-cpu/quants.c
    ${GGML_DIR}/src/ggml-cpu/traits.cpp
    ${GGML_DIR}/src/ggml-cpu/binary-ops.cpp
    ${GGML_DIR}/src/ggml-cpu/unary-ops.cpp
    ${GGML_DIR}/src/ggml-cpu/vec.cpp
    ${GGML_DIR}/src/ggml-cpu/ops.cpp
    ${GGML_DIR}/src/ggml-cpu/arch/arm/quants.c
    ${GGML_DIR}/src/ggml-cpu/arch/arm/repack.cpp
)

target_include_directories(ggml PUBLIC
    ${GGML_DIR}/include
)

target_include_directories(ggml PRIVATE
    ${GGML_DIR}/src
    ${GGML_DIR}/src/ggml-cpu
)

target_compile_definitions(ggml PRIVATE
    GGML_USE_CPU
)

target_link_libraries(ggml PUBLIC dl)

add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
)

target_include_directories(whisper PRIVATE
    ${WHISPER_DIR}/src
    ${GGML_DIR}/include
)

target_link_libraries(whisper PUBLIC ggml)

add_library(harken_whisper_jni SHARED
    harken_whisper_jni.cpp
)

target_include_directories(harken_whisper_jni PRIVATE
    ${WHISPER_DIR}/include
)

target_link_libraries(harken_whisper_jni PRIVATE
    whisper
    log
)
