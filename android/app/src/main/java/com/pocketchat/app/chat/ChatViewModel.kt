package com.pocketchat.app.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketchat.app.inference.ChatMessage
import com.pocketchat.app.inference.PocketChatContext
import com.pocketchat.app.inference.PocketChatException
import com.pocketchat.app.inference.PocketChatModel
import com.pocketchat.app.models.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ModelStatus {
    data object Loading : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

data class ChatUiState(
    val modelStatus: ModelStatus = ModelStatus.Loading,
    val messages: List<ChatMessage> = emptyList(),
    /** The in-progress assistant reply; empty when not generating. */
    val streamingResponse: String = "",
    val isGenerating: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var model: PocketChatModel? = null
    private var context: PocketChatContext? = null

    /** Path of the model currently loaded into [model]/[context], if any. */
    private var loadedModelPath: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { loadModel() }
    }

    private fun loadModel() {
        val app = getApplication<Application>()
        val modelFile = ModelStorage.activeModelFile(app)
        try {
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
     * Call after returning from the model manager screen in case the active
     * model changed there — a no-op if it didn't. Ignored mid-generation,
     * since there's no sensible way to switch a model out from under a
     * running generateChat() call.
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
                val history = _uiState.value.messages
                val response = ctx.generateChat(history) { piece ->
                    _uiState.update { it.copy(streamingResponse = it.streamingResponse + piece) }
                    true
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage("assistant", response),
                        streamingResponse = "",
                        isGenerating = false,
                    )
                }
            } catch (e: Exception) {
                // The native context's KV cache now holds whatever partial reply was
                // streamed before the error, but `messages` never got that turn
                // appended — reset() clears the cache so the two stay consistent;
                // the next send() re-plays the full (still-intact) `messages` history
                // into a fresh context rather than getting permanently stuck.
                ctx.reset()
                _uiState.update {
                    it.copy(isGenerating = false, streamingResponse = "", error = e.message ?: "generation failed")
                }
            }
        }
    }

    override fun onCleared() {
        context?.close()
        model?.close()
    }
}
