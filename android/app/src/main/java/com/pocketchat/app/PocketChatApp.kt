package com.pocketchat.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.chat.ChatScreen
import com.pocketchat.app.chat.ChatViewModel
import com.pocketchat.app.memory.MemoryViewerScreen
import com.pocketchat.app.models.ModelManagerScreen

private enum class Screen { Chat, ModelManager, MemoryViewer }

@Composable
fun PocketChatApp() {
    var screen by remember { mutableStateOf(Screen.Chat) }
    val chatViewModel: ChatViewModel = viewModel()

    when (screen) {
        Screen.Chat -> ChatScreen(
            viewModel = chatViewModel,
            onOpenModelManager = { screen = Screen.ModelManager },
            onOpenMemoryViewer = { screen = Screen.MemoryViewer },
        )

        Screen.ModelManager -> ModelManagerScreen(
            onBack = {
                // Picking a different model on that screen doesn't take effect
                // until the chat context is reloaded against it.
                chatViewModel.reloadModelIfChanged()
                screen = Screen.Chat
            },
        )

        Screen.MemoryViewer -> MemoryViewerScreen(onBack = { screen = Screen.Chat })
    }
}
