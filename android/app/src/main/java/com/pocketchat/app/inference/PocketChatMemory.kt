package com.pocketchat.app.inference

import java.io.File

object PocketChatMemory {

    /**
     * Reads memoryDir/profile.txt and the last [maxSummaries] session
     * summaries into a string ready to splice into a system prompt. Empty
     * string if there's no memory yet. Blocking (file I/O) — call from a
     * background thread.
     */
    fun buildContext(memoryDir: File, maxSummaries: Int = 5, maxChars: Int = 2000): String =
        PocketChatEngine.nativeMemoryBuildContext(memoryDir.absolutePath, maxSummaries, maxChars)

    /**
     * Prompts [model] — via its own fresh scratch context internally, never
     * touching any [PocketChatContext] the caller already has open — to merge
     * durable facts from `messages` into memoryDir/profile.txt and append a
     * summary to memoryDir/summaries/. Blocking — call from a background
     * thread.
     */
    fun updateSession(
        model: PocketChatModel,
        memoryDir: File,
        messages: List<ChatMessage>,
        nCtx: Int = 0,
        nThreads: Int = -1,
    ) {
        if (messages.isEmpty()) return
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }
        val rc = PocketChatEngine.nativeMemoryUpdateSession(
            model.handle, memoryDir.absolutePath, roles, contents, nCtx, nThreads,
        )
        if (rc != 0) {
            throw PocketChatException(PocketChatEngine.nativeMemoryLastError())
        }
    }
}
