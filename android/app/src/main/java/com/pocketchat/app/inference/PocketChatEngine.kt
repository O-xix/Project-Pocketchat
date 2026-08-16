package com.pocketchat.app.inference

/**
 * Raw JNI surface over core/inference/pocketchat_inference.h. Model/context
 * handles are opaque native pointers boxed as [Long] — use [PocketChatModel]
 * and [PocketChatContext] instead of calling this directly.
 */
internal object PocketChatEngine {

    init {
        System.loadLibrary("pocketchat_jni")
    }

    fun interface TokenCallback {
        /** Return false to stop generation early. */
        fun onToken(piece: String): Boolean
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
}
