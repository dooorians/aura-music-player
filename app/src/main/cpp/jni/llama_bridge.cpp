// JNI bridge to the playlist assistant's engine (native/slm/slm_engine.cpp).
//
// As thin as the analyser's bridges: strings in, one generate() call, strings out. No policy and no threading here:
// the Kotlin side (LlamaBridge.kt) picks the thread (Dispatchers.Default), serialises calls, and decides what an answer
// is worth. The engine frees the model and its context before generate() returns, so nothing native outlives a call.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "slm_engine.h"

namespace {

// One flag for the process: the Kotlin side never runs two generations at once.
std::atomic<bool> g_cancel{false};

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Modified UTF-8 (what NewStringUTF reads) differs from UTF-8 for supplementary characters and NUL; the grammar keeps
// the answer inside the BMP, but an error message is passed through a byte array decoded on the Kotlin side anyway.
jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

}  // namespace

extern "C" {

// Returns [text (UTF-8 bytes), error (UTF-8 bytes), stats] where stats is
// "ok,cancelled,cacheHit,promptTokens,generatedTokens,loadMs,promptMs,generateMs". Null only if the JVM is out of memory.
JNIEXPORT jobjectArray JNICALL
Java_com_ikko_aura_ai_slm_LlamaBridge_nativeGenerate(
    JNIEnv * env,
    jclass /* clazz */,
    jstring model_path,
    jobjectArray roles,
    jobjectArray contents,
    jstring grammar,
    jstring prefix_cache_path,
    jint max_tokens,
    jint threads,
    jint n_ctx) {
    g_cancel.store(false);

    aura::slm::GenerateParams params;
    params.model_path = to_string(env, model_path);
    params.grammar = to_string(env, grammar);
    params.prefix_cache_path = to_string(env, prefix_cache_path);
    params.max_tokens = max_tokens;
    params.n_threads = threads;
    params.n_ctx = n_ctx;
    const jsize count = roles != nullptr && contents != nullptr ? std::min(env->GetArrayLength(roles), env->GetArrayLength(contents)) : 0;
    params.messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        params.messages.push_back({to_string(env, role), to_string(env, content)});
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    const aura::slm::GenerateResult r = aura::slm::generate(params, &g_cancel);

    const std::string stats = std::to_string(r.ok ? 1 : 0) + "," + std::to_string(r.cancelled ? 1 : 0) + "," +
                              std::to_string(r.prefix_cache_hit ? 1 : 0) + "," + std::to_string(r.prompt_tokens) + "," +
                              std::to_string(r.generated_tokens) + "," + std::to_string(static_cast<long>(r.load_ms)) + "," +
                              std::to_string(static_cast<long>(r.prompt_ms)) + "," + std::to_string(static_cast<long>(r.generate_ms));
    jclass object_class = env->FindClass("java/lang/Object");
    if (object_class == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(3, object_class, nullptr);
    if (out == nullptr) return nullptr;
    env->SetObjectArrayElement(out, 0, to_bytes(env, r.text));
    env->SetObjectArrayElement(out, 1, to_bytes(env, r.error));
    env->SetObjectArrayElement(out, 2, env->NewStringUTF(stats.c_str()));
    return out;
}

// Stops the generation in progress at its next graph node (or next token); harmless when none is running.
JNIEXPORT void JNICALL
Java_com_ikko_aura_ai_slm_LlamaBridge_nativeCancel(JNIEnv * /* env */, jclass /* clazz */) {
    g_cancel.store(true);
}

}  // extern "C"
