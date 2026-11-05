# The on-device playlist assistant's inference engine: a pruned llama.cpp (native/third_party/llama.cpp, see its
# VENDOR.md) built for the CPU only, and slm_engine.cpp, which runs one grammar-constrained completion and frees
# everything it allocated before it returns.
#
# Everything here is static: the engine, libllama and ggml end up inside the one shared library the JNI bridge is
# (libaura_llm.so, see app/src/main/cpp/CMakeLists.txt), which the app loads only when the assistant is opened. The
# Automix analyser (libaura_analysis.so) does not depend on any of it.
#
# Also builds on a workstation (Linux/macOS, x86_64 or arm64) for the host harness in host/, so the grammar and the
# prompts can be checked against a real model without a phone:
#   cmake -S native/slm -B build/slm-host -DAURA_SLM_HOST_TOOLS=ON && cmake --build build/slm-host -j
#   build/slm-host/slm_cli model.gguf app/src/main/assets/slm/playlist_intent.gbnf "Night drive"

cmake_minimum_required(VERSION 3.22.1)
project(aura_slm LANGUAGES C CXX)

set(AURA_LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/llama.cpp")
option(AURA_SLM_HOST_TOOLS "Build the host command-line harness (not for Android)" OFF)

# --- llama.cpp / ggml configuration ---------------------------------------------------------------------------------
# Static, CPU only, no OpenMP (ggml's own thread pool is what the engine sizes to two threads), no dynamic backend
# loading, nothing but the libraries.
set(BUILD_SHARED_LIBS OFF)
set(GGML_STATIC           OFF CACHE BOOL "" FORCE)
set(GGML_CPU              ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)
set(GGML_FATAL_WARNINGS   OFF CACHE BOOL "" FORCE)

if (ANDROID)
    # Cross-compiled: the build machine's CPU says nothing about the phone's. ARMv8.2-A is the floor of every core the
    # app targets (Cortex-A55/A76 in the MT6789, and anything newer): NEON is part of AArch64, +fp16 gives the half
    # precision arithmetic ggml's F16 paths use, +dotprod the SDOT/UDOT instructions its quantised dot products use.
    set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
else()
    # On a workstation the harness runs where it was built.
    set(GGML_NATIVE       ON  CACHE BOOL "" FORCE)
endif()

# What llama.cpp's own top-level CMakeLists would define before reaching src/: its version, and the per-target flag
# helper (left empty: warnings in a third-party tree are not ours to fix).
set(LLAMA_VERSION_MAJOR 0)
set(LLAMA_VERSION_MINOR 8)
set(LLAMA_VERSION_PATCH 0)
set(LLAMA_VERSION_BASE "${LLAMA_VERSION_MAJOR}.${LLAMA_VERSION_MINOR}.${LLAMA_VERSION_PATCH}")
set(LLAMA_VERSION "${LLAMA_VERSION_BASE}")
set(LLAMA_BUILD_NUMBER 12100)
set(LLAMA_BUILD_COMMIT "4b29d02")
set(GGML_BUILD_NUMBER ${LLAMA_BUILD_NUMBER})
set(GGML_BUILD_COMMIT ${LLAMA_BUILD_COMMIT})
function(llama_add_compile_flags)
endfunction()

add_subdirectory(${AURA_LLAMA_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml EXCLUDE_FROM_ALL)
add_subdirectory(${AURA_LLAMA_DIR}/src ${CMAKE_CURRENT_BINARY_DIR}/llama EXCLUDE_FROM_ALL)

# Position-independent: these static archives end up inside a shared library.
foreach(t ggml ggml-base ggml-cpu llama)
    if (TARGET ${t})
        set_target_properties(${t} PROPERTIES POSITION_INDEPENDENT_CODE ON)
        # Optimised in every variant: a debug build of the app must not make the assistant ten times slower.
        target_compile_options(${t} PRIVATE -O3)
    endif()
endforeach()

# --- the engine ------------------------------------------------------------------------------------------------------
add_library(aura_slm_engine STATIC slm_engine.cpp)
set_target_properties(aura_slm_engine PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_features(aura_slm_engine PUBLIC cxx_std_17)
target_include_directories(aura_slm_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(aura_slm_engine PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(aura_slm_engine PUBLIC llama)
if (ANDROID)
    find_library(aura-slm-log-lib log)
    target_link_libraries(aura_slm_engine PRIVATE ${aura-slm-log-lib})
endif()

if (AURA_SLM_HOST_TOOLS AND NOT ANDROID)
    add_executable(slm_cli host/slm_cli.cpp)
    target_link_libraries(slm_cli PRIVATE aura_slm_engine)
endif()
