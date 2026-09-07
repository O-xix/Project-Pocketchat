package com.pocketchat.app.models

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * FR-012: user-imported models (local file picker or direct URL) — kept
 * alongside [ModelCatalog.entries] rather than replacing it. A plain JSON
 * file living in the same models/ directory as the `.gguf` files it
 * describes, matching `ChatStorage`'s plain-file pattern for a structured
 * list rather than shoehorning JSON into a SharedPreferences string.
 */
object CustomModelStorage {
    private const val FILE_NAME = "custom_models.json"

    private fun file(context: Context): File = File(ModelStorage.modelsDir(context), FILE_NAME)

    fun list(context: Context): List<ModelCatalogEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                ModelCatalogEntry(
                    id = obj.getString("id"),
                    displayName = obj.getString("displayName"),
                    quant = obj.getString("quant"),
                    url = obj.getString("url"),
                    filename = obj.getString("filename"),
                    approxSizeBytes = obj.getLong("approxSizeBytes"),
                    tier = RamTier.valueOf(obj.getString("tier")),
                )
            }
        } catch (_: Exception) {
            emptyList() // a corrupt/unreadable file shouldn't block the model manager from loading at all
        }
    }

    /** Replaces any existing entry with the same id (a re-import updates the record, no duplicate rows). */
    fun add(context: Context, entry: ModelCatalogEntry) {
        save(context, list(context).filterNot { it.id == entry.id } + entry)
    }

    fun remove(context: Context, entryId: String) {
        save(context, list(context).filterNot { it.id == entryId })
    }

    private fun save(context: Context, entries: List<ModelCatalogEntry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("displayName", e.displayName)
                    .put("quant", e.quant)
                    .put("url", e.url)
                    .put("filename", e.filename)
                    .put("approxSizeBytes", e.approxSizeBytes)
                    .put("tier", e.tier.name)
            )
        }
        file(context).writeText(array.toString())
    }
}
