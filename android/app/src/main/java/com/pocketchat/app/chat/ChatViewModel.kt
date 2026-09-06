package com.pocketchat.app.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketchat.app.inference.ChatMessage
import com.pocketchat.app.inference.MemoryPhase
import com.pocketchat.app.inference.PocketChatContext
import com.pocketchat.app.inference.PocketChatException
import com.pocketchat.app.inference.PocketChatMemory
import com.pocketchat.app.inference.PocketChatModel
import com.pocketchat.app.inference.PocketChatSafety
import com.pocketchat.app.inference.SamplingParams
import com.pocketchat.app.models.BundledModel
import com.pocketchat.app.models.ChatStorage
import com.pocketchat.app.models.MemoryStorage
import com.pocketchat.app.models.ModelStorage
import com.pocketchat.app.power.DeviceStressMonitor
import com.pocketchat.app.power.ThrottleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MEMORY_UPDATE_EVERY_N_MESSAGES = 6
private const val BASE_SYSTEM_PROMPT = "You are PocketChat, a helpful assistant."
/** Response-length ceiling applied for one turn when NFR-011 detects battery/thermal stress. */
private const val THROTTLED_N_PREDICT = 256

sealed interface ModelStatus {
    data object Loading : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/** Live progress for an in-flight memory update — see ChatViewModel.maybeUpdateMemory(). */
data class MemoryUpdateProgress(val phase: MemoryPhase, val text: String)

data class ChatUiState(
    val modelStatus: ModelStatus = ModelStatus.Loading,
    val messages: List<ChatMessage> = emptyList(),
    /** The in-progress assistant reply; empty when not generating. */
    val streamingResponse: String = "",
    val isGenerating: Boolean = false,
    val memoryUpdateProgress: MemoryUpdateProgress? = null,
    /** Set for the duration of a generation NFR-011 detected as battery/thermal-stressed. */
    val throttleStatus: ThrottleStatus? = null,
    /** FR-022: the just-generated session summary, awaiting the user's review; null once dismissed. */
    val pendingSummaryReview: String? = null,
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var model: PocketChatModel? = null
    private var context: PocketChatContext? = null

    /** Path of the model currently loaded into [model]/[context], if any. */
    private var loadedModelPath: String? = null

    /** How many of [ChatUiState.messages] have already been folded into memory. */
    private var lastMemoryUpdateIndex: Int = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // NFR-012 (BOOM recovery): a fresh ChatViewModel instance means the
            // OS killed the previous process while backgrounded — the transcript
            // saved to disk (see persistTranscript()) is the only way to recover
            // it, since ViewModels themselves don't survive process death.
            val restored = ChatStorage.load(getApplication())
            if (restored.isNotEmpty()) {
                _uiState.update { it.copy(messages = restored) }
                // We don't persist which restored messages were already folded
                // into memory before the process died, so treat all of them as
                // already accounted for rather than risk re-summarizing (and
                // duplicating) turns memory may have already processed —
                // consistent with maybeUpdateMemory() already being best-effort.
                lastMemoryUpdateIndex = restored.size
            }
            loadModel()
        }
    }

    private fun loadModel() {
        val app = getApplication<Application>()
        try {
            // No-op unless this build bundles a model — extracts it out of assets
            // on first run and marks it active if nothing else is chosen yet, so
            // a fresh install has something to chat with immediately. Inside this
            // try block deliberately: it must never crash app startup.
            BundledModel.ensureExtracted(app)
            val modelFile = ModelStorage.activeModelFile(app)
            modelFile ?: throw PocketChatException("no model available — open [models] and download one")
            val loadedModel = PocketChatModel.load(modelFile.absolutePath)
            val loadedContext = PocketChatContext.create(loadedModel)
            model = loadedModel
            context = loadedContext
            loadedModelPath = modelFile.absolutePath
            _uiState.update { it.copy(modelStatus = ModelStatus.Ready) }
        } catch (e: Exception) {
            loadedModelPath = null
            _uiState.update { it.copy(modelStatus = ModelStatus.Failed(e.message ?: "failed to load model")) }
        }
    }

    /**
     * FR-013: rebuilt fresh for every turn — [query] (the user's just-typed
     * message) lets FTS5/BM25 pick summaries relevant to what's actually
     * being discussed right now, rather than a static set of the most recent
     * ones fixed at model-load time. Falls back to plain recency automatically
     * (see PocketChatMemory.buildContext's doc comment) when there's no
     * strong match, including the very first turn of a fresh conversation.
     */
    private fun buildSystemPrompt(query: String): String {
        val remembered = PocketChatMemory.buildContext(MemoryStorage.memoryDir(getApplication()), query = query)
        return if (remembered.isBlank()) BASE_SYSTEM_PROMPT else "$BASE_SYSTEM_PROMPT\n\n$remembered"
    }

    /**
     * Call after returning from the model manager screen in case the active
     * model changed there — a no-op if it didn't. Ignored mid-generation
     * (guarded by the same isGenerating flag a memory update also holds —
     * see sendMessage — so this can never close a model out from under a
     * still-running pc_memory_update_session on it).
     */
    fun reloadModelIfChanged() {
        if (_uiState.value.isGenerating) return
        val app = getApplication<Application>()
        val activePath = ModelStorage.activeModelFile(app)?.absolutePath
        if (activePath == loadedModelPath) return

        viewModelScope.launch(Dispatchers.IO) {
            context?.close()
            model?.close()
            context = null
            model = null
            // The visible transcript (and lastMemoryUpdateIndex, how much of it
            // is already folded into memory) survives the switch — only the
            // native context resets. The new context's KV cache starts empty,
            // so the next sendMessage() call — which always sends the full
            // history, not just the newest message — replays every preserved
            // message into the newly loaded model as a side effect of the
            // existing prev_len-tracking logic. That's what makes the switch
            // actually carry the conversation forward, not just display it.
            _uiState.update {
                it.copy(
                    modelStatus = ModelStatus.Loading,
                    streamingResponse = "",
                    memoryUpdateProgress = null,
                    error = null,
                )
            }
            loadModel()
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val ctx = context
        if (trimmed.isEmpty() || _uiState.value.isGenerating || ctx == null) return

        // Tier 1 of the defense-in-depth safety stack (FR-015/NFR-013): a
        // deterministic lexical check, cheap enough to run synchronously here
        // before anything is appended to the transcript or sent to the model.
        // Deeper UX (distinct visual treatment, softer false-positive wording)
        // is deliberately deferred to PRO-30, which is blocked on this check
        // existing at all — don't expand this beyond a plain error message.
        val safety = PocketChatSafety.check(trimmed)
        if (safety.blocked) {
            _uiState.update {
                it.copy(error = "prompt blocked — matched a restricted content pattern (${safety.category})")
            }
            return
        }

        // NFR-011: a point-in-time check is enough here since it's re-run on
        // every sendMessage() call — a device that's stressed for one long
        // conversation gets re-detected turn by turn rather than needing a
        // live listener. Only the response-length ceiling is throttled, not
        // per-token generation speed (which isn't controllable from here);
        // see REQUIREMENTS.md NFR-011 for that scope note.
        val throttle = DeviceStressMonitor.current(getApplication())

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage("user", trimmed),
                isGenerating = true,
                streamingResponse = "",
                error = null,
                throttleStatus = throttle,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            persistTranscript() // NFR-012: save the user's turn before generating, in case the process dies mid-response
            try {
                // The system turn carries remembered context but never appears in
                // the displayed transcript (ChatUiState.messages) or gets counted
                // towards a memory update — it's reconstructed fresh each call,
                // now keyed off this turn's message (FR-013 relevance search).
                val history = listOf(ChatMessage("system", buildSystemPrompt(trimmed))) + _uiState.value.messages
                val sampling = if (throttle != null) SamplingParams(nPredict = THROTTLED_N_PREDICT) else SamplingParams()
                val response = ctx.generateChat(history, sampling) { piece ->
                    _uiState.update { it.copy(streamingResponse = it.streamingResponse + piece) }
                    true
                }
                _uiState.update {
                    it.copy(messages = it.messages + ChatMessage("assistant", response), streamingResponse = "")
                }
                persistTranscript()

                // Runs inline (still under isGenerating) rather than as a detached
                // background job: pc_memory_update_session() runs against the same
                // PocketChatModel that reloadModelIfChanged() can close once
                // isGenerating drops — keeping it inside this window is what makes
                // that guard actually cover memory updates too, not just chat turns.
                maybeUpdateMemory()
                _uiState.update { it.copy(isGenerating = false, throttleStatus = null) }
            } catch (e: Exception) {
                // The native context's KV cache now holds whatever partial reply was
                // streamed before the error, but `messages` never got that turn
                // appended — reset() clears the cache so the two stay consistent;
                // the next send() re-plays the full (still-intact) `messages` history
                // into a fresh context rather than getting permanently stuck.
                ctx.reset()
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        memoryUpdateProgress = null,
                        streamingResponse = "",
                        throttleStatus = null,
                        error = e.message ?: "generation failed",
                    )
                }
            }
        }
    }

    /** NFR-012: overwrite the on-disk transcript with the current visible messages. */
    private fun persistTranscript() {
        ChatStorage.save(getApplication(), _uiState.value.messages)
    }

    /**
     * Runs a memory update (PLAN.md Phase 3: "every N turns") once enough new
     * messages have accumulated since the last one. See [forceMemoryUpdate]
     * for what the update itself does.
     */
    private fun maybeUpdateMemory() {
        val messages = _uiState.value.messages
        if (messages.size - lastMemoryUpdateIndex < MEMORY_UPDATE_EVERY_N_MESSAGES) return
        forceMemoryUpdate()
    }

    /**
     * The actual memory-update pipeline, unconditionally — used by
     * [maybeUpdateMemory]'s periodic in-session check and by FR-029's
     * [clearChat], which must always fold in whatever's left before wiping
     * the transcript rather than waiting for the next multiple of N. Runs on
     * its own scratch native context (see core/memory/) so it doesn't touch
     * the main chat context. Streams live progress into
     * [ChatUiState.memoryUpdateProgress] so the UI can show something more
     * useful than an opaque "updating memory" spinner. Never throws —
     * failures are swallowed (a stale memory isn't worth surfacing an error
     * over) and the next update will just cover a longer span; progress is
     * always cleared before returning either way.
     */
    private fun forceMemoryUpdate() {
        val currentModel = model ?: return
        val messages = _uiState.value.messages
        if (messages.size <= lastMemoryUpdateIndex) return // nothing new since the last update

        val unsummarized = messages.subList(lastMemoryUpdateIndex, messages.size).toList()
        lastMemoryUpdateIndex = messages.size
        try {
            var currentPhase: MemoryPhase? = null
            val buffer = StringBuilder()
            // Tracked separately from `buffer` (which resets on every phase
            // change, for the live-progress display) so this always ends up
            // holding exactly the SUMMARIZING phase's text even in the edge
            // case where that phase produces zero pieces — reusing `buffer`
            // for both purposes would leave stale EXTRACTING_FACTS text behind
            // in that case, which FR-022's review prompt must never show.
            val summaryBuffer = StringBuilder()
            PocketChatMemory.updateSession(
                currentModel, MemoryStorage.memoryDir(getApplication()), unsummarized,
            ) { phase, piece ->
                if (phase != currentPhase) {
                    currentPhase = phase
                    buffer.setLength(0)
                }
                buffer.append(piece)
                if (phase == MemoryPhase.SUMMARIZING) summaryBuffer.append(piece)
                _uiState.update { it.copy(memoryUpdateProgress = MemoryUpdateProgress(phase, buffer.toString())) }
                true
            }
            // FR-022: surface the just-written summary (core/memory writes the
            // same trimmed text to summaries/<timestamp>.txt) for in-app review
            // while the session is still fresh. Fires every time for now.
            val summaryText = summaryBuffer.toString().trim()
            if (summaryText.isNotEmpty()) {
                _uiState.update { it.copy(pendingSummaryReview = summaryText) }
            }
        } catch (_: Exception) {
            // Best-effort; see doc comment above.
        } finally {
            _uiState.update { it.copy(memoryUpdateProgress = null) }
        }
    }

    /** FR-022: acknowledge and clear the pending summary review prompt. */
    fun dismissSummaryReview() {
        _uiState.update { it.copy(pendingSummaryReview = null) }
    }

    /**
     * FR-029: resets the visible scrollback to start a new topic. Folds
     * whatever hasn't been summarized yet into memory first (the same
     * pipeline — and FR-022 review prompt — as the periodic in-session
     * update), so nothing is lost from memory just because the screen was
     * reset; this fires regardless of [MEMORY_UPDATE_EVERY_N_MESSAGES], since
     * ending the session deliberately shouldn't wait for the next multiple
     * of N. Ignored mid-generation, same guard as [reloadModelIfChanged].
     * No confirmation step: the pre-clear summary already means nothing
     * discussed is actually lost, just no longer on-screen — a stronger
     * NFR-018-style confirmation dialog is that ticket's job, not this one's.
     */
    fun clearChat() {
        if (_uiState.value.isGenerating) return
        context ?: return
        _uiState.update { it.copy(isGenerating = true) }
        viewModelScope.launch(Dispatchers.IO) {
            forceMemoryUpdate()
            context?.reset()
            lastMemoryUpdateIndex = 0
            _uiState.update {
                it.copy(messages = emptyList(), streamingResponse = "", error = null, isGenerating = false)
            }
            persistTranscript()
        }
    }

    override fun onCleared() {
        context?.close()
        model?.close()
    }
}
