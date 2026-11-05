# Native analysis front end for Automix.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2 adds the log-mel front end the
# Beat This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3 adds the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree rather than here, so a
# later phase adding a Rubber Band-based transition renderer can add sources
# to that same tree without duplicating this module.

cmake_minimum_required(VERSION 3.22.1)
project(aura_analysis LANGUAGES C CXX)

get_filename_component(AURA_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(aura_analysis SHARED
    ${AURA_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${AURA_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${AURA_NATIVE_DIR}/analyzer/resampler.cpp
    ${AURA_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${AURA_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(aura_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${AURA_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
target_compile_options(aura_analysis PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(aura_analysis ${log-lib})

# The playlist assistant: a small instruction model run on the CPU through a pruned llama.cpp (native/slm, see its
# CMakeLists.txt). Its own library, loaded only when the assistant is opened, so the app's start and the Automix
# analyser carry none of it.
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
add_subdirectory(${AURA_NATIVE_DIR}/slm ${CMAKE_CURRENT_BINARY_DIR}/slm)

add_library(aura_llm SHARED jni/llama_bridge.cpp)
target_compile_options(aura_llm PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(aura_llm PRIVATE aura_slm_engine ${log-lib})
# Only the two JNI entry points are exported; ggml and llama stay internal to the library. Segments are aligned on 16 KB
# so the library loads on devices with 16 KB pages (Android 15 and later).
target_link_options(aura_llm PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384)
