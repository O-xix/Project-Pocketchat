package com.pocketchat.app.inference

/**
 * Raw JNI surface over core/inference/pocketchat_inference.h,
 * core/memory/pocketchat_memory.h, and core/safety/pocketchat_safety.h.
 * Model/context handles are opaque native pointers boxed as [Long] — use
 * [PocketChatModel], [PocketChatContext], [PocketChatMemory], and
 * [PocketChatSafety] instead of calling this directly.
 */
internal object PocketChatEngine {

    init {
        System.loadLibrary("pocketchat_jni")
    }

    fun interface TokenCallback {
        /** Return false to stop generation early. */
        fun onToken(piece: String): Boolean
    }

    fun interface MemoryProgressCallback {
        /** phase: 0 = extracting facts, 1 = summarizing. Return false to stop that phase's generation early. */
        fun onProgress(phase: Int, piece: String): Boolean
    }

    @JvmStatic external fun nativeInit()

    @JvmStatic external fun nativeLoadModel(path: String, nGpuLayers: Int): Long
    @JvmStatic external fun nativeFreeModel(handle: Long)

    @JvmStatic external fun nativeCreateContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun nativeFreeContext(handle: Long)
    @JvmStatic external fun nativeResetContext(handle: Long)
    @JvmStatic external fun nativeContextNCtx(handle: Long): Int
    @JvmStatic external fun nativeContextNUsed(handle: Long): Int

    @JvmStatic external fun nativeGenerateChat(
        ctxHandle: Long,
        roles: Array<String>,
        contents: Array<String>,
        temp: Float,
        topP: Float,
        minP: Float,
        topK: Int,
        nPredict: Int,
        seed: Long,
        callback: TokenCallback,
    ): Int

    @JvmStatic external fun nativeLastError(): String

    @JvmStatic external fun nativeMemoryBuildContext(memoryDir: String, query: String?, maxSummaries: Int, maxChars: Int): String

    @JvmStatic external fun nativeMemoryUpdateSession(
        modelHandle: Long,
        memoryDir: String,
        roles: Array<String>,
        contents: Array<String>,
        voice: Int,
        nCtx: Int,
        nThreads: Int,
        progressCallback: MemoryProgressCallback?,
    ): Int

    @JvmStatic external fun nativeMemoryLastError(): String

    /** FR-026: flattened 3-strings-per-result (timestamp, content, annotation) — see PocketChatMemory.search(). */
    @JvmStatic external fun nativeMemorySearch(memoryDir: String, query: String, maxResults: Int): Array<String>

    /** FR-033 */
    @JvmStatic external fun nativeMemoryDeleteSummary(memoryDir: String, timestamp: String)
    @JvmStatic external fun nativeMemoryDeleteProfileFact(memoryDir: String, factText: String)

    @JvmStatic external fun nativeSafetyCheck(text: String): Int
    @JvmStatic external fun nativeSafetyLastCategory(): String
}
