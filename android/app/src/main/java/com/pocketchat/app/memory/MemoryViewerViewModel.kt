package com.pocketchat.app.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val profile: String = "",
    val summaries: List<MemorySummaryEntry> = emptyList(),
)

/**
 * profile.txt and summaries/*.txt are model-authored (see core/memory/) and
 * stay purely read-only here — no path in this file edits or overwrites
 * them. FR-023 amends that boundary narrowly: a user-authored annotation is
 * a separate, additive `<summary>.annotation.txt` sibling file, never merged
 * into the model's own text (NFR-006). See MemoryViewerScreen for why it's
 * rendered visually distinct.
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

        val profile = File(dir, "profile.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""

        val summariesDir = File(dir, "summaries")
        val summaries = summariesDir
            .listFiles { f -> f.isFile && f.name.endsWith(".txt") && !f.name.endsWith(ANNOTATION_SUFFIX) }
            ?.sortedByDescending { it.name } // filenames are zero-padded timestamps; newest first
            ?.map { file ->
                val base = file.name.removeSuffix(".txt")
                val annotation = File(summariesDir, base + ANNOTATION_SUFFIX).takeIf { it.exists() }?.readText()?.trim() ?: ""
                MemorySummaryEntry(timestampLabelFor(file.name), base, file.readText().trim(), annotation)
            }
            ?: emptyList()

        _uiState.update { it.copy(isLoading = false, profile = profile, summaries = summaries) }
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
