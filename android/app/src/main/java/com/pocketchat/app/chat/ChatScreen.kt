package com.pocketchat.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.inference.ChatMessage
import com.pocketchat.app.inference.MemoryPhase
import com.pocketchat.app.models.SettingsStorage
import com.pocketchat.app.power.ThrottleReason
import com.pocketchat.app.ui.ConfirmableMenuItem
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermError
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TermUser
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText
import com.pocketchat.app.ui.TerminalTextField

@Composable
fun ChatScreen(
    onOpenModelManager: () -> Unit,
    onOpenMemoryViewer: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // FR-040: local, ephemeral UI state — null means not searching. Distinct
    // from FR-026's archived-memory search: this filters the live, not-yet-
    // summarized scrollback, so it has no reason to touch ChatViewModel or
    // survive process death.
    var searchQuery by remember { mutableStateOf<String?>(null) }
    // FR-041: a one-off banner for /help — not part of ChatUiState since it's
    // purely local, ephemeral UI text, same reasoning as searchQuery above.
    var helpBanner by remember { mutableStateOf<String?>(null) }

    // FR-041: slash commands are opt-in and only checked when the user
    // actually submits something starting with "/" — see handleSubmit below.
    fun handleSubmit(text: String) {
        if (SettingsStorage.isSlashCommandsEnabled(context)) {
            when (val command = parseSlashCommand(text)) {
                SlashCommand.Clear -> { viewModel.clearChat(); return }
                SlashCommand.Settings -> { onOpenSettings(); return }
                SlashCommand.Models -> { onOpenModelManager(); return }
                SlashCommand.About -> { onOpenAbout(); return }
                SlashCommand.Memory -> { onOpenMemoryViewer(null); return }
                is SlashCommand.MemorySearch -> { onOpenMemoryViewer(command.query); return }
                is SlashCommand.Search -> { searchQuery = command.query; return }
                SlashCommand.Help -> { helpBanner = helpText(); return }
                null -> Unit
            }
        }
        viewModel.sendMessage(text)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TerminalMenuItem("[models]", onClick = onOpenModelManager)
            TerminalMenuItem("[memory]", onClick = { onOpenMemoryViewer(null) })
            TerminalMenuItem("[settings]", onClick = onOpenSettings)
            // FR-029: folds whatever's unsummarized into memory before wiping
            // the transcript — see ChatViewModel.clearChat()'s doc comment.
            ConfirmableMenuItem("[clear]", "[confirm clear]", onConfirmed = viewModel::clearChat)
            TerminalMenuItem(
                if (searchQuery != null) "[search: x]" else "[search]",
                onClick = { searchQuery = if (searchQuery != null) null else "" },
            )
        }
        if (searchQuery != null) {
            TerminalTextField("search> ", searchQuery ?: "", onValueChange = { searchQuery = it })
        }
        Spacer(Modifier.height(4.dp))
        MessageScrollback(modifier = Modifier.weight(1f), uiState = uiState, searchQuery = searchQuery)
        uiState.pendingSummaryReview?.let { summary ->
            SummaryReviewBanner(summary = summary, onDismiss = viewModel::dismissSummaryReview)
        }
        helpBanner?.let { text ->
            HelpBanner(text = text, onDismiss = { helpBanner = null })
        }
        // FR-028: only while an actual chat reply is streaming, not during a
        // memory update or clear — those don't check ChatViewModel.stopRequested,
        // so [stop] would otherwise appear and silently do nothing during them.
        if (uiState.isGenerating && uiState.memoryUpdateProgress == null) {
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TerminalMenuItem("[stop]", onClick = viewModel::stopGeneration)
            }
        }
        InputPrompt(
            enabled = uiState.modelStatus is ModelStatus.Ready && !uiState.isGenerating,
            onSubmit = ::handleSubmit,
            restoreText = uiState.restoredInput,
            onRestoreConsumed = viewModel::consumeRestoredInput,
        )
    }
}

/**
 * FR-022: an in-app prompt shown right after a session summary finishes
 * generating, so the user can review it while the conversation is still
 * fresh. Read-only for now — annotating a summary is FR-023, a separate,
 * not-yet-built ticket; this only acknowledges/dismisses.
 */
@Composable
private fun SummaryReviewBanner(summary: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        TerminalText("pocketchat> session summary — review:", TermDim)
        TerminalText(summary, TermForeground)
        TerminalMenuItem("[ok]", onClick = onDismiss)
    }
}

/** FR-041: shown for /help — dismissed the same way as [SummaryReviewBanner]. */
@Composable
private fun HelpBanner(text: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        TerminalText("pocketchat> $text", TermDim)
        TerminalMenuItem("[ok]", onClick = onDismiss)
    }
}

/**
 * [searchQuery] non-null puts this in FR-040 search mode: the list shows
 * only matching messages (a plain case-insensitive substring match — this is
 * the live, not-yet-summarized scrollback, small enough in practice that an
 * FTS5 index the way FR-013/FR-026 use for the *archived* memory would be
 * pure overhead) instead of the normal status/throttle/streaming lines,
 * which are about what's happening *right now* and don't belong in a view
 * of past messages.
 */
@Composable
private fun MessageScrollback(modifier: Modifier, uiState: ChatUiState, searchQuery: String?) {
    val listState = rememberLazyListState()

    if (searchQuery != null) {
        val matches = if (searchQuery.isBlank()) emptyList()
            else uiState.messages.filter { it.content.contains(searchQuery, ignoreCase = true) }
        LazyColumn(modifier = modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { TerminalText("search results (${matches.size})", TermDim) }
            if (searchQuery.isNotBlank() && matches.isEmpty()) {
                item { TerminalText("(no matches)", TermDim) }
            }
            items(matches) { message -> MessageLine(message) }
        }
        return
    }

    val statusLine = statusLineFor(uiState)
    // While a memory update runs, the streaming response is already cleared —
    // its own status line takes over instead of an empty "pocketchat> _" line.
    val showStreamingLine = uiState.isGenerating && uiState.memoryUpdateProgress == null
    // NFR-011: shown alongside the streaming line, not instead of it — the
    // point is naming the detected trigger, not hiding that generation is happening.
    val throttleLine = if (uiState.isGenerating) throttleLineFor(uiState) else null
    val totalRows = uiState.messages.size +
        (if (statusLine != null) 1 else 0) +
        (if (throttleLine != null) 1 else 0) +
        (if (showStreamingLine) 1 else 0)

    LaunchedEffect(totalRows, uiState.streamingResponse) {
        if (totalRows > 0) listState.animateScrollToItem(totalRows - 1)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(uiState.messages) { message -> MessageLine(message) }

        if (statusLine != null) {
            item { TerminalText(statusLine.first, statusLine.second) }
        }
        if (throttleLine != null) {
            item { TerminalText(throttleLine, TermDim) }
        }
        if (showStreamingLine) {
            item { TerminalText("pocketchat> ${uiState.streamingResponse}_", TermForeground) }
        }
    }
}

private fun throttleLineFor(uiState: ChatUiState): String? {
    val throttle = uiState.throttleStatus ?: return null
    val reason = when (throttle.reason) {
        ThrottleReason.BATTERY_LOW -> "battery low (${throttle.detail})"
        ThrottleReason.THERMAL_HIGH -> "device hot (${throttle.detail})"
    }
    return "pocketchat> throttled — $reason, shortening this response"
}

private fun statusLineFor(uiState: ChatUiState): Pair<String, Color>? {
    val status = uiState.modelStatus
    val memProgress = uiState.memoryUpdateProgress
    return when {
        status is ModelStatus.Loading -> "pocketchat> loading model_" to TermDim
        status is ModelStatus.Failed -> "pocketchat> error: ${status.message}" to TermError
        memProgress != null -> {
            val phaseLabel = when (memProgress.phase) {
                MemoryPhase.EXTRACTING_FACTS -> "updating memory (extracting facts)"
                MemoryPhase.SUMMARIZING -> "updating memory (summarizing)"
            }
            "pocketchat> $phaseLabel: ${memProgress.text}_" to TermDim
        }
        uiState.error != null -> "pocketchat> error: ${uiState.error}" to TermError
        else -> null
    }
}

/**
 * FR-038: long-press reveals [copy]/[share] for this message only — a flat
 * text row rather than a Material dropdown/context menu, consistent with
 * every other action surface in this app (no popups anywhere else either).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageLine(message: ChatMessage) {
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    val (prefix, color) = if (message.role == "user") "you> " to TermUser else "pocketchat> " to TermForeground

    Column(modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showActions = !showActions })) {
        TerminalText(prefix + message.content, color)
        if (showActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TerminalMenuItem("[copy]", onClick = {
                    copyToClipboard(context, message.content)
                    showActions = false
                })
                TerminalMenuItem("[share]", onClick = {
                    shareText(context, message.content)
                    showActions = false
                })
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PocketChat message", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
private fun InputPrompt(
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    restoreText: String?,
    onRestoreConsumed: () -> Unit,
) {
    var input by remember { mutableStateOf("") }

    // FR-028: restoreText is a one-shot signal from ChatViewModel.stopGeneration
    // discarding an in-flight reply — replay its text back into this field
    // (which otherwise only tracks its own local state) and immediately tell
    // the ViewModel it's been consumed, so it doesn't fire again on recomposition.
    LaunchedEffect(restoreText) {
        if (restoreText != null) {
            input = restoreText
            onRestoreConsumed()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "> ",
            color = TermForeground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
        )
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            enabled = enabled,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                color = if (enabled) TermForeground else TermDim,
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            ),
            cursorBrush = SolidColor(TermForeground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onSubmit(input)
                input = ""
            }),
        )
    }
}
