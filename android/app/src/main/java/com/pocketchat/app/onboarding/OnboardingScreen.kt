package com.pocketchat.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.models.SettingsStorage
import com.pocketchat.app.settings.MemoryToggleSection
import com.pocketchat.app.settings.MemoryVoiceSection
import com.pocketchat.app.settings.SettingsViewModel
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

/**
 * FR-036: shown exactly once, on first launch (gated by
 * [SettingsStorage.isOnboardingCompleted] in PocketChatApp). Reuses the same
 * memory sections [com.pocketchat.app.settings.SettingsScreen] already has —
 * these are the two choices worth surfacing before the user starts chatting
 * (everything else in Settings has a sane default and isn't worth blocking
 * first use over).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> welcome", TermForeground)
        Spacer(Modifier.height(8.dp))
        TerminalText("PocketChat runs entirely on this device.", TermForeground)
        TerminalText("No accounts, no servers, no telemetry — nothing you type ever leaves this phone.", TermDim)
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { MemoryToggleSection(uiState.memoryEnabled, onSetEnabled = viewModel::setMemoryEnabled) }
            item { MemoryVoiceSection(uiState.memoryVoice, onSetVoice = viewModel::setMemoryVoice) }
            item { TerminalText("(all of this — and more — can be changed later in [settings])", TermDim) }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[continue]", onClick = {
            SettingsStorage.setOnboardingCompleted(context, true)
            onDone()
        })
    }
}
