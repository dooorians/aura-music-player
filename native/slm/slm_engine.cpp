#include "slm_engine.h"

#include "llama-cpp.h"
#include "llama.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <mutex>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace aura::slm {
namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

// llama.cpp is chatty at INFO (every tensor, every buffer): only what can be acted on is kept.
void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level < GGML_LOG_LEVEL_WARN || text == nullptr) return;
#ifdef __ANDROID__
    __android_log_write(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "AuraSlm", text);
#else
    std::fputs(text, stderr);
#endif
}

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

// The model's own chat template (Qwen 2.5 and SmolLM2 both carry ChatML); ChatML written out when the model has no
// template llama.cpp knows.
std::string render_chat(const llama_model * model, const std::vector<ChatMessage> & messages, size_t count, bool add_assistant) {
    std::vector<llama_chat_message> chat;
    chat.reserve(count);
    size_t total = 0;
    for (size_t i = 0; i < count; ++i) {
        chat.push_back({messages[i].role.c_str(), messages[i].content.c_str()});
        total += messages[i].role.size() + messages[i].content.size();
    }
    if (const char * tmpl = llama_model_chat_template(model, nullptr)) {
        std::vector<char> buf(total * 2 + 256);
        int32_t n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), add_assistant, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(n));
            n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), add_assistant, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n >= 0) return std::string(buf.data(), static_cast<size_t>(n));
    }
    std::string out;
    out.reserve(total + count * 24 + 32);
    for (size_t i = 0; i < count; ++i) {
        out += "<|im_start|>";
        out += messages[i].role;
        out += '\n';
        out += messages[i].content;
        out += "<|im_end|>\n";
    }
    if (add_assistant) out += "<|im_start|>assistant\n";
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    if (text.empty()) return {};
    const int32_t needed = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    if (needed <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), needed, add_special, true);
    if (n < 0) return {};
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

// Feeds tokens [0, n) at positions pos0.. in batches of at most n_batch; only the very last one asks for logits.
int32_t feed(llama_context * ctx, llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos0, int32_t n_batch, bool want_logits) {
    for (int32_t i = 0; i < n; i += n_batch) {
        const int32_t m = std::min(n_batch, n - i);
        llama_batch_ext_clear(batch);
        for (int32_t j = 0; j < m; ++j) {
            const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i + j]);
            if (idx < 0) return -100;
            const llama_pos pos = pos0 + i + j;
            llama_batch_ext_set_pos(batch, idx, &pos);
        }
        if (want_logits && i + m == n) llama_batch_ext_set_output_logits(batch, m - 1, true);
        const int32_t rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) return rc;
    }
    return 0;
}

bool abort_requested(void * data) {
    return data != nullptr && static_cast<const std::atomic<bool> *>(data)->load(std::memory_order_relaxed);
}

bool is_cancelled(const std::atomic<bool> * cancel) {
    return cancel != nullptr && cancel->load(std::memory_order_relaxed);
}

// The prefix's state, if the cache file holds exactly these tokens. Leaves the memory empty otherwise.
bool load_prefix(llama_context * ctx, const std::string & path, const std::vector<llama_token> & prefix) {
    if (path.empty() || prefix.empty()) return false;
    std::FILE * probe = std::fopen(path.c_str(), "rb");
    if (probe == nullptr) return false;
    std::fclose(probe);
    std::vector<llama_token> stored(prefix.size() + 16);
    size_t count = 0;
    const bool loaded = llama_state_load_file(ctx, path.c_str(), stored.data(), stored.size(), &count);
    if (loaded && count == prefix.size() && std::equal(prefix.begin(), prefix.end(), stored.begin())) return true;
    // A cache of another model, another prompt or another version of the app: start over (it is rewritten below).
    llama_memory_clear(llama_get_memory(ctx), true);
    return false;
}

// Written beside the target and renamed over it: a crash mid-write never leaves a half file to be loaded later.
void save_prefix(llama_context * ctx, const std::string & path, const std::vector<llama_token> & prefix) {
    if (path.empty() || prefix.empty()) return;
    const std::string tmp = path + ".tmp";
    if (llama_state_save_file(ctx, tmp.c_str(), prefix.data(), prefix.size())) {
        if (std::rename(tmp.c_str(), path.c_str()) != 0) std::remove(tmp.c_str());
    } else {
        std::remove(tmp.c_str());
    }
}

// Greedy choice within the grammar. The grammar is first asked about the model's own favourite only (almost always
// allowed: the examples taught the model the format), and filters the whole vocabulary only when it refuses it. This
// is what llama.cpp's common sampler does: on a 150k-token vocabulary it is the difference between a few microseconds
// and a few milliseconds per token.
llama_token pick_token(llama_sampler * grammar, const float * logits, int32_t n_vocab, std::vector<llama_token_data> & scratch) {
    int32_t best = 0;
    for (int32_t i = 1; i < n_vocab; ++i) {
        if (logits[i] > logits[best]) best = i;
    }
    llama_token_data one{best, logits[best], 0.0f};
    llama_token_data_array single{&one, 1, -1, false};
    llama_sampler_apply(grammar, &single);
    if (single.size == 1 && std::isfinite(single.data[0].logit)) return best;

    scratch.resize(static_cast<size_t>(n_vocab));
    for (int32_t i = 0; i < n_vocab; ++i) scratch[static_cast<size_t>(i)] = {i, logits[i], 0.0f};
    llama_token_data_array all{scratch.data(), scratch.size(), -1, false};
    llama_sampler_apply(grammar, &all);
    llama_token chosen = LLAMA_TOKEN_NULL;
    float top = -INFINITY;
    for (size_t i = 0; i < all.size; ++i) {
        if (all.data[i].logit > top) {
            top = all.data[i].logit;
            chosen = all.data[i].id;
        }
    }
    return std::isfinite(top) ? chosen : LLAMA_TOKEN_NULL;
}

}  // namespace

GenerateResult generate(const GenerateParams & params, const std::atomic<bool> * cancel) {
    GenerateResult result;
    if (params.model_path.empty() || params.messages.empty()) {
        result.error = "nothing to generate";
        return result;
    }
    ensure_backend();
    const auto t_load = Clock::now();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    // Mapped, not read: the weights are clean file pages the kernel can drop under pressure, and they are gone the
    // moment the model is freed.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model_ptr model(llama_model_load_from_file(params.model_path.c_str(), model_params));
    if (!model) {
        result.error = "the model could not be loaded";
        return result;
    }
    const llama_vocab * vocab = llama_model_get_vocab(model.get());

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(std::max(256, params.n_ctx));
    ctx_params.n_batch = std::min<uint32_t>(ctx_params.n_ctx, 512);
    ctx_params.n_ubatch = ctx_params.n_batch;
    ctx_params.n_seq_max = 1;
    ctx_params.n_threads = std::max(1, params.n_threads);
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.no_perf = true;
    if (cancel != nullptr) {
        ctx_params.abort_callback = abort_requested;
        ctx_params.abort_callback_data = const_cast<std::atomic<bool> *>(cancel);
    }
    llama_context_ptr ctx(llama_init_from_model(model.get(), ctx_params));
    if (!ctx) {
        result.error = "the context could not be created";
        return result;
    }
    llama_sampler_ptr grammar(llama_sampler_init_grammar(vocab, params.grammar.c_str(), params.grammar_root.c_str()));
    if (!grammar) {
        result.error = "the grammar could not be parsed";
        return result;
    }
    llama_batch_ext_ptr batch(llama_batch_ext_init(ctx.get()));
    if (!batch) {
        result.error = "the batch could not be allocated";
        return result;
    }
    result.load_ms = ms_since(t_load);

    // The fixed part (system prompt and examples) and the listener's request, tokenised apart so the first can be
    // cached. ChatML ends every message with a special token, so cutting there never splits a token.
    const std::string full = render_chat(model.get(), params.messages, params.messages.size(), true);
    const std::string prefix = params.messages.size() > 1 ? render_chat(model.get(), params.messages, params.messages.size() - 1, false) : std::string();
    const bool splittable = !prefix.empty() && full.compare(0, prefix.size(), prefix) == 0;
    std::vector<llama_token> prefix_tokens = splittable ? tokenize(vocab, prefix, true) : std::vector<llama_token>();
    std::vector<llama_token> rest_tokens = splittable ? tokenize(vocab, full.substr(prefix.size()), false) : tokenize(vocab, full, true);
    const int32_t n_prompt = static_cast<int32_t>(prefix_tokens.size() + rest_tokens.size());
    if (rest_tokens.empty() || n_prompt + params.max_tokens >= static_cast<int32_t>(llama_n_ctx(ctx.get()))) {
        result.error = "the prompt does not fit the context";
        return result;
    }

    const auto t_prompt = Clock::now();
    const int32_t n_batch = static_cast<int32_t>(ctx_params.n_batch);
    llama_pos n_past = 0;
    if (!prefix_tokens.empty()) {
        result.prefix_cache_hit = load_prefix(ctx.get(), params.prefix_cache_path, prefix_tokens);
        if (!result.prefix_cache_hit) {
            const int32_t rc = feed(ctx.get(), batch.get(), prefix_tokens.data(), static_cast<int32_t>(prefix_tokens.size()), 0, n_batch, false);
            if (rc != 0) {
                result.cancelled = is_cancelled(cancel);
                result.error = result.cancelled ? "cancelled" : "the prompt could not be processed";
                return result;
            }
            save_prefix(ctx.get(), params.prefix_cache_path, prefix_tokens);
            result.prompt_tokens += static_cast<int32_t>(prefix_tokens.size());
        }
        n_past = static_cast<llama_pos>(prefix_tokens.size());
    }
    if (feed(ctx.get(), batch.get(), rest_tokens.data(), static_cast<int32_t>(rest_tokens.size()), n_past, n_batch, true) != 0) {
        result.cancelled = is_cancelled(cancel);
        result.error = result.cancelled ? "cancelled" : "the prompt could not be processed";
        return result;
    }
    n_past += static_cast<llama_pos>(rest_tokens.size());
    result.prompt_tokens += static_cast<int32_t>(rest_tokens.size());
    result.prompt_ms = ms_since(t_prompt);

    const auto t_gen = Clock::now();
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> scratch;
    int depth = 0;
    char piece[256];
    for (int32_t step = 0; step < params.max_tokens; ++step) {
        if (is_cancelled(cancel)) {
            result.cancelled = true;
            result.error = "cancelled";
            return result;
        }
        const float * logits = llama_get_logits_ith(ctx.get(), -1);
        if (logits == nullptr) {
            result.error = "no logits";
            return result;
        }
        const llama_token token = pick_token(grammar.get(), logits, n_vocab, scratch);
        if (token == LLAMA_TOKEN_NULL) {
            result.error = "the grammar allows nothing here";
            return result;
        }
        llama_sampler_accept(grammar.get(), token);
        if (llama_vocab_is_eog(vocab, token)) break;

        const int32_t n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n < 0) {
            result.error = "a token could not be rendered";
            return result;
        }
        result.text.append(piece, static_cast<size_t>(n));
        ++result.generated_tokens;
        // The grammar's strings cannot hold a brace, so the object is complete the moment its braces balance: stop
        // there rather than spend one more decode on the end-of-turn token the grammar would force next.
        for (int32_t i = 0; i < n; ++i) {
            if (piece[i] == '{') ++depth;
            else if (piece[i] == '}') --depth;
        }
        if (depth == 0 && !result.text.empty() && result.text.find('{') != std::string::npos) break;

        llama_token next = token;
        if (feed(ctx.get(), batch.get(), &next, 1, n_past, n_batch, true) != 0) {
            result.cancelled = is_cancelled(cancel);
            result.error = result.cancelled ? "cancelled" : "decoding failed";
            return result;
        }
        ++n_past;
    }
    result.generate_ms = ms_since(t_gen);
    result.ok = !result.text.empty();
    if (!result.ok) result.error = "the model produced nothing";
    return result;
    // batch, grammar, ctx and model are freed here, in that order (llama_batch_ext_free, llama_sampler_free,
    // llama_free, llama_model_free): nothing of the model outlives the call.
}

}  // namespace aura::slm
