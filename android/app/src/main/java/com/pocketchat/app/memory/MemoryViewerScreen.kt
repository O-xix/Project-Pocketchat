package com.pocketchat.app.memory

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TermUser
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

/**
 * profile.txt and every summary are rendered with plain, non-editable Text
 * composables — no path here writes back to those files. FR-023's one
 * exception is the per-summary annotation field below each entry: the
 * user's own note, always shown in [TermUser] to stay visually distinct from
 * the model's own summary text (never merged with it). See the doc comment
 * on MemoryViewerViewModel for the storage-level detail.
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
        TerminalText("pocketchat> memory", TermForeground)
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
                SummaryEntryRow(summary, onSaveAnnotation = { text -> viewModel.saveAnnotation(summary.baseName, text) })
            }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[back]", onClick = onBack)
    }
}

/**
 * FR-023: the annotation field is a plain editable text field pre-filled
 * with whatever's already saved (empty if none) — add, edit, and delete are
 * all the same action here (save with new text, or save empty to delete).
 * The "[save note]" action only appears once the draft actually differs
 * from what's persisted, so an untouched entry doesn't clutter the screen
 * with an action that would do nothing.
 */
@Composable
private fun SummaryEntryRow(entry: MemorySummaryEntry, onSaveAnnotation: (String) -> Unit) {
    var draft by remember(entry.baseName) { mutableStateOf(entry.annotation) }

    Column {
        TerminalText(entry.timestampLabel, TermDim)
        TerminalText(entry.content, TermForeground)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalText("note> ", TermDim)
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
        if (draft != entry.annotation) {
            TerminalMenuItem("[save note]", onClick = { onSaveAnnotation(draft) })
        }
    }
}
