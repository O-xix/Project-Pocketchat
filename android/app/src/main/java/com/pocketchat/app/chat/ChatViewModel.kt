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
import com.pocketchat.app.models.BundledModel
import com.pocketchat.app.models.MemoryStorage
import com.pocketchat.app.models.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MEMORY_UPDATE_EVERY_N_MESSAGES = 6
private const val BASE_SYSTEM_PROMPT = "You are PocketChat, a helpful assistant."

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
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var model: PocketChatModel? = null
    private var context: PocketChatContext? = null

    /** Path of the model currently loaded into [model]/[context], if any. */
    private var loadedModelPath: String? = null

    /** Remembered profile/summaries, prepended as a system turn — not shown in the transcript. */
    private var systemPrompt: String = BASE_SYSTEM_PROMPT

    /** How many of [ChatUiState.messages] have already been folded into memory. */
    private var lastMemoryUpdateIndex: Int = 0

    init {
        viewModelScope.launch(Dispatchers.IO) { loadModel() }
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
            systemPrompt = buildSystemPrompt(app)
            _uiState.update { it.copy(modelStatus = ModelStatus.Ready) }
        } catch (e: Exception) {
            loadedModelPath = null
            _uiState.update { it.copy(modelStatus = ModelStatus.Failed(e.message ?: "failed to load model")) }
        }
    }

    private fun buildSystemPrompt(app: Application): String {
        val remembered = PocketChatMemory.buildContext(MemoryStorage.memoryDir(app))
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
            lastMemoryUpdateIndex = 0
            _uiState.update { ChatUiState(modelStatus = ModelStatus.Loading) }
            loadModel()
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val ctx = context
        if (trimmed.isEmpty() || _uiState.value.isGenerating || ctx == null) return

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage("user", trimmed),
                isGenerating = true,
                streamingResponse = "",
                error = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // The system turn carries remembered context but never appears in
                // the displayed transcript (ChatUiState.messages) or gets counted
                // towards a memory update — it's reconstructed fresh each call.
                val history = listOf(ChatMessage("system", systemPrompt)) + _uiState.value.messages
                val response = ctx.generateChat(history) { piece ->
                    _uiState.update { it.copy(streamingResponse = it.streamingResponse + piece) }
                    true
                }
                _uiState.update {
                    it.copy(messages = it.messages + ChatMessage("assistant", response), streamingResponse = "")
                }

                // Runs inline (still under isGenerating) rather than as a detached
                // background job: pc_memory_update_session() runs against the same
                // PocketChatModel that reloadModelIfChanged() can close once
                // isGenerating drops — keeping it inside this window is what makes
                // that guard actually cover memory updates too, not just chat turns.
                maybeUpdateMemory()
                _uiState.update { it.copy(isGenerating = false) }
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
                        error = e.message ?: "generation failed",
                    )
                }
            }
        }
    }

    /**
     * Runs a memory update (PLAN.md Phase 3: "every N turns") once enough new
     * messages have accumulated since the last one, on its own scratch native
     * context (see core/memory/) so it doesn't touch the main chat context.
     * Streams live progress into [ChatUiState.memoryUpdateProgress] so the UI
     * can show something more useful than an opaque "updating memory" spinner.
     * Never throws — failures are swallowed (a stale memory isn't worth
     * surfacing an error over) and the next update will just cover a longer
     * span; progress is always cleared before returning either way.
     */
    private fun maybeUpdateMemory() {
        val currentModel = model ?: return
        val messages = _uiState.value.messages
        if (messages.size - lastMemoryUpdateIndex < MEMORY_UPDATE_EVERY_N_MESSAGES) return

        val unsummarized = messages.subList(lastMemoryUpdateIndex, messages.size).toList()
        lastMemoryUpdateIndex = messages.size
        try {
            var currentPhase: MemoryPhase? = null
            val buffer = StringBuilder()
            PocketChatMemory.updateSession(
                currentModel, MemoryStorage.memoryDir(getApplication()), unsummarized,
            ) { phase, piece ->
                if (phase != currentPhase) {
                    currentPhase = phase
                    buffer.setLength(0)
                }
                buffer.append(piece)
                _uiState.update { it.copy(memoryUpdateProgress = MemoryUpdateProgress(phase, buffer.toString())) }
                true
            }
        } catch (_: Exception) {
            // Best-effort; see doc comment above.
        } finally {
            _uiState.update { it.copy(memoryUpdateProgress = null) }
        }
    }

    override fun onCleared() {
        context?.close()
        model?.close()
    }
}
