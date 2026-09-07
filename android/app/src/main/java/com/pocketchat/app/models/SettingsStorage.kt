package com.pocketchat.app.models

import android.content.Context
import com.pocketchat.app.inference.MemoryVoice

/**
 * FR-032's underlying storage: memory on/off (FR-020), memory voice
 * (FR-014), and the persona/system-prompt override (FR-031). Plain
 * SharedPreferences, same "pocketchat" file [ModelStorage] already uses —
 * reads are cheap enough to call directly from the UI thread, consistent
 * with how that object's functions are already used.
 */
object SettingsStorage {
    private const val PREFS_NAME = "pocketchat"
    private const val KEY_MEMORY_ENABLED = "memory_enabled"
    private const val KEY_MEMORY_VOICE = "memory_voice"
    private const val KEY_PERSONA_OVERRIDE = "persona_override"

    /** FR-020: default on — memory is the existing behavior, opting out is the explicit choice. */
    fun isMemoryEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MEMORY_ENABLED, true)

    fun setMemoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
    }

    /** FR-014: default NEUTRAL. */
    fun memoryVoice(context: Context): MemoryVoice =
        if (prefs(context).getString(KEY_MEMORY_VOICE, null) == "reflective") MemoryVoice.REFLECTIVE else MemoryVoice.NEUTRAL

    fun setMemoryVoice(context: Context, voice: MemoryVoice) {
        prefs(context).edit().putString(KEY_MEMORY_VOICE, if (voice == MemoryVoice.REFLECTIVE) "reflective" else "neutral").apply()
    }

    /** FR-031: null (unset) means "use the default system prompt" — never an empty string. */
    fun personaOverride(context: Context): String? =
        prefs(context).getString(KEY_PERSONA_OVERRIDE, null)?.takeIf { it.isNotBlank() }

    fun setPersonaOverride(context: Context, text: String?) {
        prefs(context).edit().putString(KEY_PERSONA_OVERRIDE, text?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
