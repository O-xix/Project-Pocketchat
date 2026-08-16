package com.pocketchat.app.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketchat.app.inference.ChatMessage
import com.pocketchat.app.inference.PocketChatContext
import com.pocketchat.app.inference.PocketChatException
import com.pocketchat.app.inference.PocketChatModel
import java.io.File
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

/**
 * There's no model manager/downloader screen yet (that's a separate,
 * later piece of Phase 2) — until then, the chat screen just looks for any
 * .gguf file already sitting in the app's external files dir.
 */
private fun findModelFile(app: Application): File? =
    app.getExternalFilesDir("models")
        ?.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
        ?.minByOrNull { it.name }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var model: PocketChatModel? = null
    private var context: PocketChatContext? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { loadModel() }
    }

    private fun loadModel() {
        val app = getApplication<Application>()
        try {
            val modelFile = findModelFile(app)
                ?: throw PocketChatException(
                    "no .gguf model found in ${app.getExternalFilesDir("models")}\n" +
                        "(adb push one there for now — the model manager/downloader screen comes later)"
                )
            val loadedModel = PocketChatModel.load(modelFile.absolutePath)
            val loadedContext = PocketChatContext.create(loadedModel)
            model = loadedModel
            context = loadedContext
            _uiState.update { it.copy(modelStatus = ModelStatus.Ready) }
        } catch (e: Exception) {
            _uiState.update { it.copy(modelStatus = ModelStatus.Failed(e.message ?: "failed to load model")) }
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
