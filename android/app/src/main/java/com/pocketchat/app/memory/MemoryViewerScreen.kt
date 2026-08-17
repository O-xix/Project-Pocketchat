package com.pocketchat.app.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

/**
 * Purely a viewer — profile.txt and every summary are rendered with plain,
 * non-editable Text composables. There is no text field, no save action, no
 * path in this screen that can write back to those files; see the doc
 * comment on MemoryViewerViewModel for why that's deliberate.
 */
@Composable
fun MemoryViewerScreen(onBack: () -> Unit, viewModel: MemoryViewerViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> memory (read-only)", TermForeground)
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column {
                    TerminalText("profile.txt", TermDim)
                    TerminalText(uiState.profile.ifBlank { "(empty)" }, TermForeground)
                }
            }

            item { TerminalText("summaries (${uiState.summaries.size})", TermDim) }

            if (!uiState.isLoading && uiState.summaries.isEmpty()) {
                item { TerminalText("(none yet)", TermDim) }
            }

            items(uiState.summaries) { summary ->
                Column {
                    TerminalText(summary.timestampLabel, TermDim)
                    TerminalText(summary.content, TermForeground)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[back]", onClick = onBack)
    }
}
