package com.pocketchat.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.DebugLog
import com.pocketchat.app.inference.MemoryVoice
import com.pocketchat.app.ui.ConfirmableMenuItem
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TermUser
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

/**
 * FR-032: consolidates every configurable option in one place instead of
 * scattering toggles per-screen — FR-020 (memory on/off), FR-014 (memory
 * voice), and FR-031 (persona/system-prompt override) all live here.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAbout: () -> Unit = {}, viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> settings", TermForeground)
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { MemoryToggleSection(uiState.memoryEnabled, onSetEnabled = viewModel::setMemoryEnabled) }
            item { MemoryVoiceSection(uiState.memoryVoice, onSetVoice = viewModel::setMemoryVoice) }
            item { PersonaSection(uiState.personaOverride, onSave = viewModel::setPersonaOverride) }
            item { SlashCommandsSection(uiState.slashCommandsEnabled, onSetEnabled = viewModel::setSlashCommandsEnabled) }
            item { DebugLogSection() }
            item { TerminalMenuItem("[about]", onClick = onOpenAbout) }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[back]", onClick = onBack)
    }
}

/** FR-041: opt-in — off leaves chat input as plain text, unchanged from before this ticket. */
@Composable
private fun SlashCommandsSection(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    Column {
        TerminalText("slash commands", TermDim)
        TerminalText(
            if (enabled) "on — try /help in chat" else "off — chat input is always sent as a plain message",
            TermForeground,
        )
        TerminalMenuItem(if (enabled) "[turn off]" else "[turn on]", onClick = { onSetEnabled(!enabled) })
    }
}

/**
 * FR-021: only shown as actionable once there's actually something to send —
 * before any crash/error, [DebugLog.hasContent] is false and this is just a
 * status line, not a dead button.
 */
@Composable
private fun DebugLogSection() {
    val context = LocalContext.current
    Column {
        TerminalText("debug log", TermDim)
        if (DebugLog.hasContent(context)) {
            TerminalText("local error/crash log available to share", TermForeground)
            TerminalMenuItem("[export debug log]", onClick = { DebugLog.share(context) })
        } else {
            TerminalText("nothing logged yet", TermForeground)
        }
    }
}

/**
 * FR-020 + NFR-018: turning memory off is destructive-adjacent (it silently
 * stops fact extraction, summaries, and context injection going forward),
 * so it goes through the same two-tap [ConfirmableMenuItem] pattern as
 * FR-029/FR-033. Turning it back on is not destructive — plain one-tap.
 */
@Composable
fun MemoryToggleSection(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    Column {
        TerminalText("memory", TermDim)
        TerminalText(if (enabled) "on — extracting facts and summarizing sessions" else "off — nothing new is being remembered", TermForeground)
        if (enabled) {
            ConfirmableMenuItem("[turn off]", "[confirm turn off]", onConfirmed = { onSetEnabled(false) })
        } else {
            TerminalMenuItem("[turn on]", onClick = { onSetEnabled(true) })
        }
    }
}

/** FR-014: a basic two-option picker, per the ticket's explicit "keep it simple to start" scope. */
@Composable
fun MemoryVoiceSection(voice: MemoryVoice, onSetVoice: (MemoryVoice) -> Unit) {
    Column {
        TerminalText("memory voice", TermDim)
        TerminalText(
            if (voice == MemoryVoice.NEUTRAL) "neutral — dry, factual extraction and summaries"
            else "reflective — warmer, validating framing",
            TermForeground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TerminalMenuItem(
                if (voice == MemoryVoice.NEUTRAL) "[neutral] (active)" else "[neutral]",
                onClick = { onSetVoice(MemoryVoice.NEUTRAL) },
            )
            TerminalMenuItem(
                if (voice == MemoryVoice.REFLECTIVE) "[reflective] (active)" else "[reflective]",
                onClick = { onSetVoice(MemoryVoice.REFLECTIVE) },
            )
        }
    }
}

/**
 * FR-031: a free-text override, layered on top of (not replacing) FR-009's
 * automatic memory-context injection — see ChatViewModel.buildSystemPrompt().
 * Blank save clears the override.
 */
@Composable
private fun PersonaSection(value: String, onSave: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }

    Column {
        TerminalText("persona / system prompt override", TermDim)
        TerminalText("(blank = use the default assistant prompt; memory context is still added on top)", TermDim)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalText("> ", TermDim)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = TermUser,
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                cursorBrush = SolidColor(TermUser),
            )
        }
        if (draft != value) {
            TerminalMenuItem("[save]", onClick = { onSave(draft) })
        }
    }
}
