package com.pocketchat.app.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.ui.ConfirmableMenuItem
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermError
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText
import com.pocketchat.app.ui.TerminalTextField

@Composable
fun ModelManagerScreen(onBack: () -> Unit, viewModel: ModelManagerViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // FR-043: this ViewModel instance survives navigating away and back (same
    // ViewModelStoreOwner throughout), so without this, stats recorded by a
    // chat session in between visits wouldn't show until process restart.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // FR-012: SAF file picker -- Compose's own wrapper around the Activity
    // Result API, so this doesn't need MainActivity to thread a callback all
    // the way down through the screen hierarchy.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importLocalFile(uri)
    }
    var showCustomImport by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> model manager", TermForeground)
        Spacer(Modifier.height(4.dp))
        TerminalText("detected: ${formatSize(uiState.totalRamBytes)} ram, tier: ${uiState.ramTier.label}", TermDim)
        Spacer(Modifier.height(4.dp))

        // FR-012
        TerminalMenuItem(
            if (showCustomImport) "[+ custom model: x]" else "[+ custom model]",
            onClick = { showCustomImport = !showCustomImport },
        )
        if (showCustomImport) {
            TerminalTextField("url> ", customUrl, onValueChange = { customUrl = it })
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TerminalMenuItem(
                    "[download url]",
                    onClick = {
                        if (customUrl.isNotBlank()) {
                            viewModel.addCustomUrlModel(customUrl)
                            customUrl = ""
                            showCustomImport = false
                        }
                    },
                )
                // GGUF has no registered MIME type, so this deliberately
                // accepts any file — isValidGgufFile() is the real gate,
                // checked after picking, not this filter.
                TerminalMenuItem("[pick file]", onClick = { filePicker.launch(arrayOf("*/*")) })
            }
        }
        uiState.importError?.let { message ->
            TerminalText("import failed: $message", TermError)
            TerminalMenuItem("[ok]", onClick = viewModel::dismissImportError)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(uiState.rows, key = { it.entry.id }) { row ->
                ModelRowView(
                    row = row,
                    isActive = row.entry.filename == uiState.activeModelFilename,
                    onDownload = { viewModel.download(row.entry) },
                    onPause = { viewModel.pauseDownload(row.entry) },
                    onResume = { viewModel.download(row.entry) },
                    onDiscard = { viewModel.discardDownload(row.entry) },
                    onCancelQueued = { viewModel.cancelQueuedDownload(row.entry) },
                    onDelete = { viewModel.delete(row.entry) },
                    onActivate = { viewModel.setActive(row.entry) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[back]", onClick = onBack)
    }
}

@Composable
private fun ModelRowView(
    row: ModelRow,
    isActive: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onCancelQueued: () -> Unit,
    onDelete: () -> Unit,
    onActivate: () -> Unit,
) {
    Column {
        TerminalText(
            "[${row.entry.tier.label}] ${row.entry.displayName} (${row.entry.quant}, ${formatSize(row.entry.approxSizeBytes)})",
            TermForeground,
        )
        // FR-043: only once at least one real generation has run against this
        // model — no stats line at all beats showing a misleading "0.0s / 0 tok/s".
        row.stats?.let { stats -> TerminalText(formatStats(stats), TermDim) }
        when (val status = row.status) {
            is ModelRowStatus.NotDownloaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalMenuItem("[download]", onDownload)
                }
            }

            is ModelRowStatus.Queued -> {
                // NFR-019: only one download runs at a time -- this one is waiting.
                TerminalText("queued (#${status.position})", TermDim)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalMenuItem("[cancel]", onCancelQueued)
                }
            }

            is ModelRowStatus.Downloading -> {
                TerminalText(progressLine(status.downloadedBytes, status.totalBytes), TermDim)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalMenuItem("[pause]", onPause)
                }
            }

            is ModelRowStatus.Reconnecting -> {
                TerminalText(status.reason, TermDim)
                TerminalText(progressLine(status.downloadedBytes, status.totalBytes), TermDim)
                // Still actively retrying on its own — only offer to stop it,
                // not [resume], since nothing needs resuming yet.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalMenuItem("[pause]", onPause)
                }
            }

            is ModelRowStatus.Paused -> {
                TerminalText("paused — ${status.reason}", TermError)
                TerminalText(progressLine(status.downloadedBytes, status.totalBytes), TermDim)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalMenuItem("[resume]", onResume)
                    TerminalMenuItem("[discard]", onDiscard)
                }
            }

            is ModelRowStatus.Downloaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (isActive) {
                        TerminalText("active", TermDim)
                    } else {
                        TerminalMenuItem("[activate]", onActivate)
                    }
                    // NFR-018: deleting a downloaded model is irreversible (re-download required).
                    ConfirmableMenuItem("[delete]", "[confirm delete]", onConfirmed = onDelete)
                }
            }

            is ModelRowStatus.Failed -> {
                TerminalText("failed: ${status.message}", TermError)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TerminalMenuItem("[retry]", onDownload)
                }
            }
        }
    }
}

private fun formatStats(stats: ResponseStatsStorage.Stats): String =
    "first token: %.2fs • %.1f tok/s".format(stats.ttftSeconds, stats.tokensPerSecond)

private fun progressLine(downloadedBytes: Long, totalBytes: Long): String =
    "${formatPercent(downloadedBytes, totalBytes)}  —  ${formatMb(downloadedBytes)} / ${formatMb(totalBytes)}"

private fun formatPercent(downloadedBytes: Long, totalBytes: Long): String {
    if (totalBytes <= 0) return "0.000%"
    return "%.3f%%".format(downloadedBytes * 100.0 / totalBytes)
}

private fun formatMb(bytes: Long): String = "%.2f MB".format(bytes / 1024.0 / 1024.0)

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.1fgb".format(gb) else "%.0fmb".format(bytes / 1024.0 / 1024.0)
}
