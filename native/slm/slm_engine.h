// One grammar-constrained completion of a small instruction model (GGUF), from nothing back to nothing: the model is
// loaded, the context made, the answer generated, and every native allocation freed before generate() returns. The
// assistant is used a few times a day; keeping a model resident between uses would hold hundreds of megabytes for
// nothing.
#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace aura::slm {

struct ChatMessage {
    std::string role;     // "system", "user" or "assistant"
    std::string content;
};

struct GenerateParams {
    std::string model_path;
    // The conversation up to (and including) the request: system prompt, worked examples as user/assistant pairs, and
    // the listener's request last. Everything before the last message is the same on every call, which is what
    // prefix_cache_path keeps.
    std::vector<ChatMessage> messages;
    // GBNF production rules; the sampler only ever picks tokens that keep the output inside the language they define.
    std::string grammar;
    std::string grammar_root = "root";
    // Where the processed prefix (system prompt and examples) is saved after a first run, so later runs only read the
    // listener's request. Empty: no cache. The file is checked against the prefix's tokens before it is used.
    std::string prefix_cache_path;
    int32_t max_tokens = 160;
    // Two threads at the caller's priority: the phone keeps its other cores for the UI, and Media3's audio thread
    // is never starved by the model.
    int32_t n_threads = 2;
    int32_t n_ctx = 1024;
};

struct GenerateResult {
    bool ok = false;
    bool cancelled = false;
    std::string text;
    std::string error;
    int32_t prompt_tokens = 0;      // tokens processed for this call (the request only, when the prefix was cached)
    int32_t generated_tokens = 0;
    bool prefix_cache_hit = false;
    double load_ms = 0, prompt_ms = 0, generate_ms = 0;
};

// Blocking. `cancel` may be null; when it turns true the computation stops at the next graph node and the result is
// {ok = false, cancelled = true}. Thread-safe as long as each call has its own params; llama.cpp's backend is
// initialised once per process.
GenerateResult generate(const GenerateParams & params, const std::atomic<bool> * cancel);

}  // namespace aura::slm
