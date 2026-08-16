// Thin C API over llama.cpp — the only surface the Android (JNI) and iOS (Swift)
// apps talk to. Keeping this a plain C ABI means both platforms can bind it
// without any C++ interop machinery.
#ifndef POCKETCHAT_INFERENCE_H
#define POCKETCHAT_INFERENCE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct pc_model   pc_model;
typedef struct pc_context pc_context;

typedef struct {
    const char * role;    // "system" | "user" | "assistant"
    const char * content;
} pc_chat_message;

typedef struct {
    float    temp;      // sampling temperature; <= 0 means greedy (argmax)
    float    top_p;
    float    min_p;
    int32_t  top_k;
    int32_t  n_predict; // max tokens to generate this call; <= 0 means "until eog or context full"
    uint32_t seed;      // LLAMA_DEFAULT_SEED-equivalent sentinel: 0xFFFFFFFF for a random seed
} pc_sampling_params;

pc_sampling_params pc_sampling_default_params(void);

// Called once per generated piece of text. Return 0 to stop generation early,
// non-zero to keep going.
typedef int (*pc_token_callback)(const char * piece, void * user_data);

// Global one-time setup/teardown for the llama.cpp backend. Safe to call
// pc_init() more than once — it's a no-op after the first call.
void pc_init(void);
void pc_shutdown(void);

// Loads a GGUF model from `path`. `n_gpu_layers` = 0 keeps everything on CPU,
// which is the safe default on the floor-spec devices this app targets.
// Returns NULL on failure — see pc_last_error().
pc_model * pc_model_load(const char * path, int32_t n_gpu_layers);
void pc_model_free(pc_model * model);

// Creates an inference context bound to `model`. `n_ctx` = 0 uses the model's
// trained context length. `n_threads` <= 0 auto-detects from the hardware.
// Returns NULL on failure — see pc_last_error().
pc_context * pc_context_create(pc_model * model, uint32_t n_ctx, int32_t n_threads);
void pc_context_free(pc_context * ctx);

// Clears the KV cache and running chat-template state so the next
// pc_generate_chat() call starts a fresh conversation.
void pc_context_reset(pc_context * ctx);

// For UI "context N% full" indicators.
uint32_t pc_context_n_ctx(const pc_context * ctx);
uint32_t pc_context_n_used(const pc_context * ctx);

// Applies the model's chat template to `messages` (the full running
// conversation — pass the whole list each call) and streams a generated
// reply via `callback`. Only the newly-templated suffix since the last call
// is actually fed through decode, so this is cheap for multi-turn use.
// Returns 0 on success, negative on error (see pc_last_error()).
int pc_generate_chat(
    pc_context             * ctx,
    const pc_chat_message  * messages,
    size_t                    n_messages,
    pc_sampling_params        sampling,
    pc_token_callback         callback,
    void                    * user_data
);

// Low-level: generate directly from a raw, already-formatted prompt string,
// bypassing the chat template entirely. core/memory/ ended up using
// pc_generate_chat() on a scratch context instead (better instruction-
// following on instruct-tuned models than a bare unformatted prompt) — this
// is here for callers that genuinely want a raw completion.
int pc_generate_raw(
    pc_context             * ctx,
    const char              * prompt,
    pc_sampling_params        sampling,
    pc_token_callback         callback,
    void                    * user_data
);

// Human-readable message for the last error on this thread; empty string if none.
const char * pc_last_error(void);

#ifdef __cplusplus
}
#endif

#endif // POCKETCHAT_INFERENCE_H
