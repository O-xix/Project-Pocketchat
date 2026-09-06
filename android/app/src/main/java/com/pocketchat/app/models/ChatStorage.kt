package com.pocketchat.app.models

import android.content.Context
import com.pocketchat.app.inference.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the visible chat transcript to disk so it survives Android's
 * low-memory killer silently ending a backgrounded process (a "BOOM" — see
 * NFR-012). Deliberately separate from core/memory/'s durable
 * profile.txt/summaries: this is the raw, unsummarized scrollback the user
 * sees on screen, not the model's extracted long-term facts. Uses
 * org.json (built into the Android platform) rather than pulling in a
 * serialization library for one small file.
 */
object ChatStorage {
    private const val FILE_NAME = "transcript.json"

    private fun transcriptFile(context: Context): File =
        File(context.getExternalFilesDir("chat") ?: File(context.filesDir, "chat").apply { mkdirs() }, FILE_NAME)

    /** Overwrites the saved transcript. Blocking file I/O — call from a background thread. */
    fun save(context: Context, messages: List<ChatMessage>) {
        val array = JSONArray()
        for (m in messages) {
            array.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        transcriptFile(context).writeText(array.toString())
    }

    /**
     * Empty list if nothing was ever saved, or if the file is unreadable —
     * a corrupt transcript shouldn't block the app from starting a fresh chat.
     * Blocking file I/O — call from a background thread.
     */
    fun load(context: Context): List<ChatMessage> {
        val file = transcriptFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                ChatMessage(obj.getString("role"), obj.getString("content"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
