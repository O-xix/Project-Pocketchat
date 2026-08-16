package com.pocketchat.app.models

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermError
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

@Composable
fun ModelManagerScreen(onBack: () -> Unit, viewModel: ModelManagerViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> model manager", TermForeground)
        Spacer(Modifier.height(4.dp))
        TerminalText("detected: ${formatSize(uiState.totalRamBytes)} ram, tier: ${uiState.ramTier.label}", TermDim)
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(uiState.rows, key = { it.entry.id }) { row ->
                ModelRowView(
                    row = row,
                    isActive = row.entry.filename == uiState.activeModelFilename,
                    onDownload = { viewModel.download(row.entry) },
                    onCancel = { viewModel.cancelDownload(row.entry) },
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
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onActivate: () -> Unit,
) {
    Column {
        TerminalText(
            "[${row.entry.tier.label}] ${row.entry.displayName} (${row.entry.quant}, ${formatSize(row.entry.approxSizeBytes)})",
            TermForeground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when (val status = row.status) {
                is ModelRowStatus.NotDownloaded -> TerminalMenuItem("[download]", onDownload)

                is ModelRowStatus.Downloading -> {
                    TerminalText("${(status.progress * 100).toInt()}%", TermDim)
                    TerminalMenuItem("[cancel]", onCancel)
                }

                is ModelRowStatus.Downloaded -> {
                    if (isActive) {
                        TerminalText("active", TermDim)
                    } else {
                        TerminalMenuItem("[activate]", onActivate)
                    }
                    TerminalMenuItem("[delete]", onDelete)
                }

                is ModelRowStatus.Failed -> {
                    TerminalText("failed: ${status.message}", TermError)
                    TerminalMenuItem("[retry]", onDownload)
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.1fgb".format(gb) else "%.0fmb".format(bytes / 1024.0 / 1024.0)
}
