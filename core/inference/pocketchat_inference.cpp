#include "pocketchat_inference.h"

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

struct pc_model {
    llama_model * model = nullptr;
};

struct pc_context {
    llama_model   * model = nullptr; // non-owning, borrowed from the pc_model it was created from
    llama_context * ctx   = nullptr;

    // Running chat-template state (see pc_generate_chat below).
    std::vector<char> tmpl_buf;
    int                prev_len = 0;
};

namespace {

thread_local std::string g_last_error;

void set_error(std::string msg) {
    g_last_error = std::move(msg);
}

std::atomic<bool> g_initialized{false};

// Runs the decode/sample loop for `prompt` against `pc_ctx`, streaming generated
// text through `callback`. Shared by pc_generate_chat and pc_generate_raw.
int run_generation(
    pc_context         * pc_ctx,
    const std::string  & prompt,
    bool                 parse_special,
    pc_sampling_params   sampling,
    pc_token_callback    callback,
    void                * user_data
) {
    llama_context     * ctx    = pc_ctx->ctx;
    const llama_vocab  * vocab = llama_model_get_vocab(pc_ctx->model);
    llama_memory_t       mem   = llama_get_memory(ctx);

    const bool is_first = llama_memory_seq_pos_max(mem, 0) == -1;

    const int n_prompt_tokens =
        -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, is_first, parse_special);

    std::vector<llama_token> prompt_tokens(n_prompt_tokens > 0 ? n_prompt_tokens : 0);
    if (n_prompt_tokens > 0) {
        if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                            prompt_tokens.data(), (int32_t) prompt_tokens.size(),
                            is_first, parse_special) < 0) {
            set_error("failed to tokenize prompt");
            return -1;
        }
    } else if (n_prompt_tokens < 0) {
        set_error("failed to count prompt tokens");
        return -1;
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampling.temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (sampling.top_k > 0)          llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampling.top_k));
        if (sampling.top_p < 1.0f)       llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampling.top_p, 1));
        if (sampling.min_p > 0.0f)       llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sampling.min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sampling.temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sampling.seed));
    }

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t) prompt_tokens.size());

    int rc          = 0;
    int n_generated = 0;

    while (true) {
        const uint32_t n_ctx      = llama_n_ctx(ctx);
        const int      n_ctx_used = llama_memory_seq_pos_max(mem, 0) + 1;
        if (n_ctx_used + batch.n_tokens > (int) n_ctx) {
            set_error("context size exceeded");
            rc = -2;
            break;
        }

        if (llama_decode(ctx, batch) != 0) {
            set_error("llama_decode failed");
            rc = -3;
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char buf[256];
        const int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n < 0) {
            set_error("failed to convert token to piece");
            rc = -4;
            break;
        }

        if (callback && !callback(std::string(buf, n).c_str(), user_data)) {
            break;
        }

        n_generated++;
        if (sampling.n_predict > 0 && n_generated >= sampling.n_predict) {
            break;
        }

        batch = llama_batch_get_one(&new_token, 1);
    }

    llama_sampler_free(smpl);
    return rc;
}

// Wraps a caller's callback so we can accumulate the generated text alongside
// forwarding each piece, without requiring a capturing (non-C-ABI) callback.
struct chat_gen_state {
    std::string        response;
    pc_token_callback   user_cb;
    void              * user_data;
};

int chat_gen_trampoline(const char * piece, void * ud) {
    auto * state = static_cast<chat_gen_state *>(ud);
    state->response += piece;
    return state->user_cb ? state->user_cb(piece, state->user_data) : 1;
}

} // namespace

pc_sampling_params pc_sampling_default_params(void) {
    pc_sampling_params p;
    p.temp      = 0.8f;
    p.top_p     = 0.95f;
    p.min_p     = 0.05f;
    p.top_k     = 40;
    p.n_predict = -1;
    p.seed      = LLAMA_DEFAULT_SEED;
    return p;
}

void pc_init(void) {
    bool expected = false;
    if (g_initialized.compare_exchange_strong(expected, true)) {
        llama_log_set([](enum ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) {
                fprintf(stderr, "%s", text);
            }
        }, nullptr);
        ggml_backend_load_all();
        llama_backend_init();
    }
}

void pc_shutdown(void) {
    bool expected = true;
    if (g_initialized.compare_exchange_strong(expected, false)) {
        llama_backend_free();
    }
}

pc_model * pc_model_load(const char * path, int32_t n_gpu_layers) {
    if (!path) {
        set_error("pc_model_load: path is null");
        return nullptr;
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = n_gpu_layers;

    llama_model * m = llama_model_load_from_file(path, params);
    if (!m) {
        set_error(std::string("failed to load model from ") + path);
        return nullptr;
    }

    auto * out = new pc_model();
    out->model = m;
    return out;
}

void pc_model_free(pc_model * model) {
    if (!model) return;
    if (model->model) llama_model_free(model->model);
    delete model;
}

pc_context * pc_context_create(pc_model * model, uint32_t n_ctx, int32_t n_threads) {
    if (!model || !model->model) {
        set_error("pc_context_create: invalid model");
        return nullptr;
    }

    llama_context_params params = llama_context_default_params();
    // llama's own default (512) is too small for a chat session; fall back to a
    // conservative 2048 rather than the model's trained context, which can be
    // far larger than the floor-spec devices this app targets can afford.
    params.n_ctx   = n_ctx > 0 ? n_ctx : 2048;
    params.n_batch = params.n_ctx;

    const int32_t nt = n_threads > 0
        ? n_threads
        : (int32_t) std::max(1u, std::thread::hardware_concurrency());
    params.n_threads       = nt;
    params.n_threads_batch = nt;

    llama_context * lc = llama_init_from_model(model->model, params);
    if (!lc) {
        set_error("failed to create llama_context");
        return nullptr;
    }

    auto * out = new pc_context();
    out->model = model->model;
    out->ctx   = lc;
    return out;
}

void pc_context_free(pc_context * ctx) {
    if (!ctx) return;
    if (ctx->ctx) llama_free(ctx->ctx);
    delete ctx;
}

void pc_context_reset(pc_context * ctx) {
    if (!ctx || !ctx->ctx) return;
    llama_memory_clear(llama_get_memory(ctx->ctx), true);
    ctx->prev_len = 0;
}

uint32_t pc_context_n_ctx(const pc_context * ctx) {
    if (!ctx || !ctx->ctx) return 0;
    return llama_n_ctx(ctx->ctx);
}

uint32_t pc_context_n_used(const pc_context * ctx) {
    if (!ctx || !ctx->ctx) return 0;
    const llama_pos pos = llama_memory_seq_pos_max(llama_get_memory(ctx->ctx), 0);
    return pos < 0 ? 0 : (uint32_t) (pos + 1);
}

int pc_generate_chat(
    pc_context             * pc_ctx,
    const pc_chat_message  * messages,
    size_t                    n_messages,
    pc_sampling_params        sampling,
    pc_token_callback         callback,
    void                    * user_data
) {
    if (!pc_ctx || !pc_ctx->ctx) {
        set_error("pc_generate_chat: invalid context");
        return -1;
    }
    if (!messages || n_messages == 0) {
        set_error("pc_generate_chat: no messages");
        return -1;
    }

    const char * tmpl = llama_model_chat_template(pc_ctx->model, /* name */ nullptr);

    std::vector<llama_chat_message> chat_msgs;
    chat_msgs.reserve(n_messages);
    for (size_t i = 0; i < n_messages; i++) {
        chat_msgs.push_back({ messages[i].role, messages[i].content });
    }

    if (pc_ctx->tmpl_buf.empty()) {
        pc_ctx->tmpl_buf.resize(1024);
    }

    int new_len = llama_chat_apply_template(tmpl, chat_msgs.data(), chat_msgs.size(), true,
                                             pc_ctx->tmpl_buf.data(), (int32_t) pc_ctx->tmpl_buf.size());
    if (new_len > (int) pc_ctx->tmpl_buf.size()) {
        pc_ctx->tmpl_buf.resize(new_len);
        new_len = llama_chat_apply_template(tmpl, chat_msgs.data(), chat_msgs.size(), true,
                                             pc_ctx->tmpl_buf.data(), (int32_t) pc_ctx->tmpl_buf.size());
    }
    if (new_len < 0) {
        set_error("failed to apply chat template");
        return -1;
    }

    if (new_len < pc_ctx->prev_len) {
        set_error("pc_generate_chat: messages is shorter than the context's running conversation "
                   "(call pc_context_reset() before starting a new/different conversation)");
        return -5;
    }

    const std::string prompt(pc_ctx->tmpl_buf.begin() + pc_ctx->prev_len, pc_ctx->tmpl_buf.begin() + new_len);

    chat_gen_state state;
    state.user_cb   = callback;
    state.user_data = user_data;

    const int rc = run_generation(pc_ctx, prompt, /*parse_special=*/true, sampling, chat_gen_trampoline, &state);

    // Recompute how much of the templated conversation is now "consumed" — this
    // must include the reply we just generated (its tokens are already sitting
    // in the KV cache), so the next call only feeds genuinely new text through
    // decode. The caller is expected to append this exact response text as an
    // {"assistant", ...} message before its next pc_generate_chat() call.
    chat_msgs.push_back({ "assistant", state.response.c_str() });
    int consumed_len = llama_chat_apply_template(tmpl, chat_msgs.data(), chat_msgs.size(), false,
                                                  pc_ctx->tmpl_buf.data(), (int32_t) pc_ctx->tmpl_buf.size());
    if (consumed_len > (int) pc_ctx->tmpl_buf.size()) {
        pc_ctx->tmpl_buf.resize(consumed_len);
        consumed_len = llama_chat_apply_template(tmpl, chat_msgs.data(), chat_msgs.size(), false,
                                                  pc_ctx->tmpl_buf.data(), (int32_t) pc_ctx->tmpl_buf.size());
    }
    if (consumed_len >= 0) {
        pc_ctx->prev_len = consumed_len;
    }

    return rc;
}

int pc_generate_raw(
    pc_context         * pc_ctx,
    const char          * prompt,
    pc_sampling_params    sampling,
    pc_token_callback     callback,
    void                * user_data
) {
    if (!pc_ctx || !pc_ctx->ctx) {
        set_error("pc_generate_raw: invalid context");
        return -1;
    }
    if (!prompt) {
        set_error("pc_generate_raw: prompt is null");
        return -1;
    }

    return run_generation(pc_ctx, std::string(prompt), /*parse_special=*/false, sampling, callback, user_data);
}

const char * pc_last_error(void) {
    return g_last_error.c_str();
}
