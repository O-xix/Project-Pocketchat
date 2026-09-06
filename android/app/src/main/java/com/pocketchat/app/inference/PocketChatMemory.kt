package com.pocketchat.app.inference

import java.io.File

enum class MemoryPhase { EXTRACTING_FACTS, SUMMARIZING }

/** [timestamp] is the summary's base filename — pass it to [PocketChatMemory.deleteSummary]. */
data class MemorySearchResult(val timestamp: String, val content: String, val annotation: String)

object PocketChatMemory {

    /**
     * Reads memoryDir/profile.txt (included in full) and up to [maxSummaries]
     * entries from memoryDir/summaries/, ready to splice into a system
     * prompt. [query] — typically the current conversation's latest message —
     * ranks which summaries are most relevant via FTS5/BM25 (FR-013); pass
     * null or blank to fall back to plain recency (e.g. a fresh conversation
     * with nothing to key off yet), which also happens automatically if
     * [query] matches nothing. Empty string if there's no memory yet.
     * Blocking (file I/O) — call from a background thread.
     */
    fun buildContext(memoryDir: File, query: String? = null, maxSummaries: Int = 5, maxChars: Int = 2000): String =
        PocketChatEngine.nativeMemoryBuildContext(memoryDir.absolutePath, query?.takeIf { it.isNotBlank() }, maxSummaries, maxChars)

    /**
     * Prompts [model] — via its own fresh scratch context internally, never
     * touching any [PocketChatContext] the caller already has open — to merge
     * durable facts from `messages` into memoryDir/profile.txt and append a
     * summary to memoryDir/summaries/. Blocking — call from a background
     * thread.
     *
     * [onProgress], if given, is invoked once per generated piece of text
     * across both generation passes (fact extraction, then summarizing),
     * tagged with which one — for showing live progress instead of an opaque
     * spinner. Return `false` from it to stop that phase's generation early
     * (the update as a whole still completes normally).
     */
    fun updateSession(
        model: PocketChatModel,
        memoryDir: File,
        messages: List<ChatMessage>,
        nCtx: Int = 0,
        nThreads: Int = -1,
        onProgress: ((phase: MemoryPhase, piece: String) -> Boolean)? = null,
    ) {
        if (messages.isEmpty()) return
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }
        val callback = onProgress?.let { cb ->
            PocketChatEngine.MemoryProgressCallback { phaseOrdinal, piece ->
                val phase = if (phaseOrdinal == 0) MemoryPhase.EXTRACTING_FACTS else MemoryPhase.SUMMARIZING
                cb(phase, piece)
            }
        }
        val rc = PocketChatEngine.nativeMemoryUpdateSession(
            model.handle, memoryDir.absolutePath, roles, contents, nCtx, nThreads, callback,
        )
        if (rc != 0) {
            throw PocketChatException(PocketChatEngine.nativeMemoryLastError())
        }
    }

    /**
     * FR-026: direct user-facing search across [memoryDir]'s summaries, ranked
     * by the same FTS5/BM25 relevance [buildContext] uses — but unlike that
     * function, this never falls back to recency; no match means an empty
     * list, not unrelated recent entries. Blank [query] short-circuits to an
     * empty list without touching the native layer. Blocking — call from a
     * background thread.
     */
    fun search(memoryDir: File, query: String, maxResults: Int = 10): List<MemorySearchResult> {
        if (query.isBlank()) return emptyList()
        val flat = PocketChatEngine.nativeMemorySearch(memoryDir.absolutePath, query, maxResults)
        return (flat.indices step 3).map { i -> MemorySearchResult(flat[i], flat[i + 1], flat[i + 2]) }
    }

    /** FR-033: permanently deletes summary [timestamp] and its annotation, if any. Blocking. */
    fun deleteSummary(memoryDir: File, timestamp: String) {
        PocketChatEngine.nativeMemoryDeleteSummary(memoryDir.absolutePath, timestamp)
    }

    /** FR-033: permanently deletes the first profile.txt line exactly matching [factText]. Blocking. */
    fun deleteProfileFact(memoryDir: File, factText: String) {
        PocketChatEngine.nativeMemoryDeleteProfileFact(memoryDir.absolutePath, factText)
    }
}
