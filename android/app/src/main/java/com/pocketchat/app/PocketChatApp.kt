package com.pocketchat.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.about.AboutScreen
import com.pocketchat.app.chat.ChatScreen
import com.pocketchat.app.chat.ChatViewModel
import com.pocketchat.app.memory.MemoryViewerScreen
import com.pocketchat.app.models.ModelManagerScreen
import com.pocketchat.app.models.SettingsStorage
import com.pocketchat.app.onboarding.OnboardingScreen
import com.pocketchat.app.settings.SettingsScreen
import com.pocketchat.app.settings.SettingsViewModel
import com.pocketchat.app.ui.LocalTerminalPalette

// FR-041's /memory search needs MemoryViewer to carry an initial query, so
// this can't stay a plain enum — a sealed interface lets one destination
// (MemoryViewer) hold data the others don't need.
private sealed interface Screen {
    data object Onboarding : Screen
    data object Chat : Screen
    data object ModelManager : Screen
    data class MemoryViewer(val initialSearchQuery: String? = null) : Screen
    data object Settings : Screen
    data object About : Screen
}

@Composable
fun PocketChatApp() {
    val context = LocalContext.current
    // FR-036: gates first launch only — never re-checked once onboarding is done,
    // so nothing here revisits Onboarding for the lifetime of the install.
    var screen by remember {
        mutableStateOf<Screen>(if (SettingsStorage.isOnboardingCompleted(context)) Screen.Chat else Screen.Onboarding)
    }
    val chatViewModel: ChatViewModel = viewModel()
    // FR-044: shared with SettingsScreen's own `viewModel()` default param —
    // both resolve to the same Activity-scoped instance, so a change made in
    // Settings is reflected here (and thus app-wide) immediately, with no
    // extra plumbing needed to propagate it back up.
    val settingsViewModel: SettingsViewModel = viewModel()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalTerminalPalette provides settingsUiState.terminalPalette) {
        // Every screen only ever reads MaterialTheme.typography.bodyLarge (see
        // TerminalText/TerminalTextField) — overriding just that one slot here
        // is enough to resize text everywhere without touching each screen.
        MaterialTheme(
            typography = Typography(
                bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = settingsUiState.fontSize.sp.sp),
            ),
        ) {
            when (val current = screen) {
                Screen.Onboarding -> OnboardingScreen(onDone = { screen = Screen.Chat })

                Screen.Chat -> ChatScreen(
                    viewModel = chatViewModel,
                    onOpenModelManager = { screen = Screen.ModelManager },
                    onOpenMemoryViewer = { query -> screen = Screen.MemoryViewer(query) },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenAbout = { screen = Screen.About },
                )

                Screen.ModelManager -> ModelManagerScreen(
                    onBack = {
                        // Picking a different model on that screen doesn't take effect
                        // until the chat context is reloaded against it.
                        chatViewModel.reloadModelIfChanged()
                        screen = Screen.Chat
                    },
                )

                is Screen.MemoryViewer -> MemoryViewerScreen(
                    onBack = { screen = Screen.Chat },
                    initialSearchQuery = current.initialSearchQuery,
                )

                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Chat },
                    onOpenAbout = { screen = Screen.About },
                    viewModel = settingsViewModel,
                )

                Screen.About -> AboutScreen(onBack = { screen = Screen.Settings })
            }
        }
    }
}
