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
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketchat.app.ui.ConfirmableMenuItem
import com.pocketchat.app.ui.TermBackground
import com.pocketchat.app.ui.TermDim
import com.pocketchat.app.ui.TermForeground
import com.pocketchat.app.ui.TermUser
import com.pocketchat.app.ui.TerminalMenuItem
import com.pocketchat.app.ui.TerminalText

/**
 * profile.txt and every summary are rendered with plain, non-editable Text
 * composables — no path here writes back to their content. Two narrow,
 * additive exceptions: FR-023's per-summary annotation field (the user's own
 * note, always shown in [TermUser] to stay visually distinct from the
 * model's text) and FR-033's per-item delete action (removal only, never
 * edits). FR-026 adds a search box at the top, reusing the same FTS5 ranking
 * FR-013 uses for automatic memory injection — but showing exactly what
 * matched, with no recency fallback, since a search box silently substituting
 * unrelated recent entries would look broken rather than helpful.
 */
@Composable
fun MemoryViewerScreen(
    onBack: () -> Unit,
    initialSearchQuery: String? = null,
    viewModel: MemoryViewerViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // FR-041: lets `/memory search <query>` land directly on results instead
    // of the profile/summaries default view — only fires once per navigation,
    // not on every recomposition.
    LaunchedEffect(initialSearchQuery) {
        if (!initialSearchQuery.isNullOrBlank()) viewModel.search(initialSearchQuery)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBackground)
            .padding(12.dp)
    ) {
        TerminalText("pocketchat> memory", TermForeground)
        Spacer(Modifier.height(8.dp))
        SearchBox(query = uiState.searchQuery, onQueryChange = viewModel::search)
        Spacer(Modifier.height(8.dp))

        val searchResults = uiState.searchResults
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (searchResults != null) {
                item { TerminalText("search results (${searchResults.size})", TermDim) }
                if (searchResults.isEmpty()) {
                    item { TerminalText("(no matches)", TermDim) }
                }
                items(searchResults) { summary ->
                    SummaryEntryRow(
                        summary,
                        onSaveAnnotation = { text -> viewModel.saveAnnotation(summary.baseName, text) },
                        onDelete = { viewModel.deleteSummary(summary.baseName) },
                    )
                }
            } else {
                item { TerminalText("profile.txt", TermDim) }
                if (uiState.profileFacts.isEmpty()) {
                    item { TerminalText("(empty)", TermForeground) }
                }
                items(uiState.profileFacts) { fact ->
                    ProfileFactRow(fact, onDelete = { viewModel.deleteProfileFact(fact) })
                }

                item { TerminalText("summaries (${uiState.summaries.size})", TermDim) }
                if (!uiState.isLoading && uiState.summaries.isEmpty()) {
                    item { TerminalText("(none yet)", TermDim) }
                }
                items(uiState.summaries) { summary ->
                    SummaryEntryRow(
                        summary,
                        onSaveAnnotation = { text -> viewModel.saveAnnotation(summary.baseName, text) },
                        onDelete = { viewModel.deleteSummary(summary.baseName) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TerminalMenuItem("[back]", onClick = onBack)
    }
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TerminalText("search> ", TermDim)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                color = TermUser,
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            ),
            cursorBrush = SolidColor(TermUser),
        )
    }
}

@Composable
private fun ProfileFactRow(fact: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalText(fact, TermForeground, modifier = Modifier.weight(1f))
        ConfirmableMenuItem("[delete]", "[confirm delete]", onConfirmed = onDelete)
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
private fun SummaryEntryRow(entry: MemorySummaryEntry, onSaveAnnotation: (String) -> Unit, onDelete: () -> Unit) {
    var draft by remember(entry.baseName) { mutableStateOf(entry.annotation) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TerminalText(entry.timestampLabel, TermDim, modifier = Modifier.weight(1f))
            ConfirmableMenuItem("[delete]", "[confirm delete]", onConfirmed = onDelete)
        }
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
