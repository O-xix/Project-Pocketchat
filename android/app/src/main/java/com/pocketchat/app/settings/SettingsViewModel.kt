package com.pocketchat.app.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.pocketchat.app.inference.MemoryVoice
import com.pocketchat.app.models.SettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val memoryEnabled: Boolean = true,
    val memoryVoice: MemoryVoice = MemoryVoice.NEUTRAL,
    val personaOverride: String = "",
    val slashCommandsEnabled: Boolean = false,
)

/** FR-032: backs the settings screen; each setter writes through to [SettingsStorage] immediately. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(loadFromStorage())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadFromStorage(): SettingsUiState {
        val app = getApplication<Application>()
        return SettingsUiState(
            memoryEnabled = SettingsStorage.isMemoryEnabled(app),
            memoryVoice = SettingsStorage.memoryVoice(app),
            personaOverride = SettingsStorage.personaOverride(app) ?: "",
            slashCommandsEnabled = SettingsStorage.isSlashCommandsEnabled(app),
        )
    }

    /** FR-020. Turning memory off is the only direction requiring NFR-018 confirmation — see SettingsScreen. */
    fun setMemoryEnabled(enabled: Boolean) {
        SettingsStorage.setMemoryEnabled(getApplication(), enabled)
        _uiState.update { it.copy(memoryEnabled = enabled) }
    }

    /** FR-014 */
    fun setMemoryVoice(voice: MemoryVoice) {
        SettingsStorage.setMemoryVoice(getApplication(), voice)
        _uiState.update { it.copy(memoryVoice = voice) }
    }

    /** FR-031. Blank clears the override (reverts to the default system prompt). */
    fun setPersonaOverride(text: String) {
        val trimmed = text.trim()
        SettingsStorage.setPersonaOverride(getApplication(), trimmed)
        _uiState.update { it.copy(personaOverride = trimmed) }
    }

    /** FR-041: opt-in slash-command input mode. */
    fun setSlashCommandsEnabled(enabled: Boolean) {
        SettingsStorage.setSlashCommandsEnabled(getApplication(), enabled)
        _uiState.update { it.copy(slashCommandsEnabled = enabled) }
    }
}
