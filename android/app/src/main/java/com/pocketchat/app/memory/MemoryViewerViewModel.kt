package com.pocketchat.app.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketchat.app.inference.PocketChatMemory
import com.pocketchat.app.models.MemoryStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ANNOTATION_SUFFIX = ".annotation.txt"

/** [baseName] (e.g. "20260101-090000") locates this entry's sibling annotation file for save/delete. */
data class MemorySummaryEntry(
    val timestampLabel: String,
    val baseName: String,
    val content: String,
    val annotation: String = "",
)

data class MemoryViewerUiState(
    val isLoading: Boolean = true,
    /** FR-033: one entry per non-blank profile.txt line — that file is one durable fact per line. */
    val profileFacts: List<String> = emptyList(),
    val summaries: List<MemorySummaryEntry> = emptyList(),
    val searchQuery: String = "",
    /** FR-026: null while not searching; an empty (non-null) list means "searched, nothing matched." */
    val searchResults: List<MemorySummaryEntry>? = null,
)

/**
 * profile.txt and every summary under summaries/ are model-authored (see
 * core/memory/) and stay purely read-only here — no path in this file edits
 * or overwrites them. FR-023 amends that boundary narrowly: a user-authored
 * annotation is a separate, additive `<summary>.annotation.txt` sibling
 * file, never merged into the model's own text (NFR-006). FR-033 amends it
 * again to allow permanent deletion (never editing) of one summary or one
 * profile fact at a time. See MemoryViewerScreen for the visual distinction.
 */
class MemoryViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MemoryViewerUiState())
    val uiState: StateFlow<MemoryViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        val app = getApplication<Application>()
        val dir = MemoryStorage.memoryDir(app)

        val profileFacts = File(dir, "profile.txt").takeIf { it.exists() }
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val summariesDir = File(dir, "summaries")
        val summaries = summariesDir
            .listFiles { f -> f.isFile && f.name.endsWith(".txt") && !f.name.endsWith(ANNOTATION_SUFFIX) }
            ?.sortedByDescending { it.name } // filenames are zero-padded timestamps; newest first
            ?.map { file -> summaryEntryFor(summariesDir, file) }
            ?: emptyList()

        _uiState.update { it.copy(isLoading = false, profileFacts = profileFacts, summaries = summaries) }
    }

    private fun summaryEntryFor(summariesDir: File, file: File): MemorySummaryEntry {
        val base = file.name.removeSuffix(".txt")
        val annotation = File(summariesDir, base + ANNOTATION_SUFFIX).takeIf { it.exists() }?.readText()?.trim() ?: ""
        return MemorySummaryEntry(timestampLabelFor(file.name), base, file.readText().trim(), annotation)
    }

    /**
     * FR-023: writes the user's own note on the summary identified by
     * [baseName] — a blank [text] deletes it instead, since "no note" and
     * "empty note" mean the same thing here. Never touches the summary's own
     * .txt file. Reloads from disk afterward so the UI reflects the change.
     */
    fun saveAnnotation(baseName: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(File(MemoryStorage.memoryDir(getApplication()), "summaries"), baseName + ANNOTATION_SUFFIX)
            val trimmed = text.trim()
            if (trimmed.isEmpty()) file.delete() else file.writeText(trimmed)
            load()
        }
    }

    /** FR-033: permanently deletes summary [baseName] and its annotation, if any. */
    fun deleteSummary(baseName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            PocketChatMemory.deleteSummary(MemoryStorage.memoryDir(getApplication()), baseName)
            load()
        }
    }

    /** FR-033: permanently deletes the profile.txt line exactly matching [factText]. */
    fun deleteProfileFact(factText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            PocketChatMemory.deleteProfileFact(MemoryStorage.memoryDir(getApplication()), factText)
            load()
        }
    }

    /**
     * FR-026: [query] blank clears search and returns to the normal
     * profile/summaries view; otherwise runs an FTS5/BM25 search (see
     * PocketChatMemory.search — no recency fallback, unlike the automatic
     * memory-injection path) and shows exactly what matched, including zero
     * results, rather than falling back to browsing everything.
     */
    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val dir = MemoryStorage.memoryDir(getApplication())
            val results = PocketChatMemory.search(dir, query).map { r ->
                MemorySummaryEntry(timestampLabelFor(r.timestamp + ".txt"), r.timestamp, r.content, r.annotation)
            }
            _uiState.update { it.copy(searchResults = results) }
        }
    }
}

// Filenames are "YYYYMMDD-HHMMSS.txt" (see timestamp_filename() in
// core/memory/pocketchat_memory.cpp) — reformat for readability, or fall back
// to the raw name if it doesn't match (keeps this tolerant of future changes).
private fun timestampLabelFor(filename: String): String {
    val base = filename.removeSuffix(".txt")
    val pattern = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$""")
    val match = pattern.matchEntire(base) ?: return base
    val (year, month, day, hour, minute, second) = match.destructured
    return "$year-$month-$day $hour:$minute:$second UTC"
}
