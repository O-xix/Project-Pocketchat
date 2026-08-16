package com.pocketchat.app.inference

/** A single chat turn. `role` is "system", "user", or "assistant". */
data class ChatMessage(val role: String, val content: String)

data class SamplingParams(
    val temp: Float = 0.8f,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val topK: Int = 40,
    /** Max tokens to generate this call; <= 0 means "until end-of-turn or context full". */
    val nPredict: Int = -1,
    /** 0xFFFFFFFF (the default) picks a random seed each call. */
    val seed: Long = 0xFFFFFFFFL,
)

class PocketChatException(message: String) : Exception(message)

/**
 * A loaded GGUF model. Cheap to keep around; the expensive state (KV cache,
 * chat history) lives in [PocketChatContext] instead, so one model can back
 * multiple contexts if that's ever useful.
 *
 * Not thread-safe — load, use, and close from one thread (or externally
 * synchronize).
 */
class PocketChatModel private constructor(internal val handle: Long) : AutoCloseable {

    companion object {
        /**
         * Loads a GGUF model from `path`. Blocking and potentially slow (disk
         * I/O + mmap setup) — call from a background thread, not the UI thread.
         *
         * @param nGpuLayers 0 keeps everything on CPU, the safe default on the
         *   floor-spec devices this app targets.
         */
        fun load(path: String, nGpuLayers: Int = 0): PocketChatModel {
            PocketChatEngine.nativeInit()
            val handle = PocketChatEngine.nativeLoadModel(path, nGpuLayers)
            if (handle == 0L) {
                throw PocketChatException(PocketChatEngine.nativeLastError())
            }
            return PocketChatModel(handle)
        }
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        PocketChatEngine.nativeFreeModel(handle)
    }
}

/**
 * An inference session bound to a [PocketChatModel] — owns the KV cache and
 * the running chat-template state for one conversation.
 *
 * Not thread-safe, and generation is blocking (can take from milliseconds to
 * tens of seconds depending on device and model size) — always call
 * [generateChat] from a background thread/coroutine, never the UI thread.
 */
class PocketChatContext private constructor(internal val handle: Long) : AutoCloseable {

    companion object {
        /**
         * @param nCtx 0 uses a conservative default (2048 tokens).
         * @param nThreads <= 0 auto-detects from the hardware.
         */
        fun create(model: PocketChatModel, nCtx: Int = 0, nThreads: Int = -1): PocketChatContext {
            val handle = PocketChatEngine.nativeCreateContext(model.handle, nCtx, nThreads)
            if (handle == 0L) {
                throw PocketChatException(PocketChatEngine.nativeLastError())
            }
            return PocketChatContext(handle)
        }
    }

    private var closed = false

    /** Clears the KV cache so the next [generateChat] call starts a fresh conversation. */
    fun reset() = PocketChatEngine.nativeResetContext(handle)

    val nCtx: Int get() = PocketChatEngine.nativeContextNCtx(handle)
    val nUsed: Int get() = PocketChatEngine.nativeContextNUsed(handle)

    /**
     * Applies the model's chat template to `messages` (pass the full running
     * conversation each call) and streams the reply through [onToken]. Return
     * `false` from [onToken] to stop generation early.
     *
     * The returned string must be appended back into the message list you
     * pass to the *next* call, as an `"assistant"` [ChatMessage] with this
     * exact text — the context has already folded the generated tokens into
     * its KV cache, so re-supplying different text there would desync it.
     */
    fun generateChat(
        messages: List<ChatMessage>,
        sampling: SamplingParams = SamplingParams(),
        onToken: (String) -> Boolean = { true },
    ): String {
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }
        val response = StringBuilder()

        val rc = PocketChatEngine.nativeGenerateChat(
            handle, roles, contents,
            sampling.temp, sampling.topP, sampling.minP, sampling.topK, sampling.nPredict, sampling.seed,
        ) { piece ->
            response.append(piece)
            onToken(piece)
        }

        if (rc != 0) {
            throw PocketChatException(PocketChatEngine.nativeLastError())
        }
        return response.toString()
    }

    override fun close() {
        if (closed) return
        closed = true
        PocketChatEngine.nativeFreeContext(handle)
    }
}
