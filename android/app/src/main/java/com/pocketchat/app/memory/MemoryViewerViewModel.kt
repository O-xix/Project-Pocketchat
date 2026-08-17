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

data class MemorySummaryEntry(val timestampLabel: String, val content: String)

data class MemoryViewerUiState(
    val isLoading: Boolean = true,
    val profile: String = "",
    val summaries: List<MemorySummaryEntry> = emptyList(),
)

/**
 * Read-only. There is deliberately no write/delete/edit path anywhere in this
 * file — profile.txt and summaries/ are model-authored (see core/memory/) and
 * meant to be viewable but never hand-edited through the app; that's a
 * filesystem-level thing (adb, a file manager), not an in-app one.
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

        val summaries = File(dir, "summaries")
            .listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } // filenames are zero-padded timestamps; newest first
            ?.map { MemorySummaryEntry(timestampLabelFor(it.name), it.readText().trim()) }
            ?: emptyList()

        _uiState.update { it.copy(isLoading = false, profile = profile, summaries = summaries) }
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
