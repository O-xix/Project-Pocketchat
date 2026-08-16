package com.pocketchat.app.models

import android.content.Context
import java.io.File

/** Where downloaded/adb-pushed .gguf files live, plus which one is "active". */
object ModelStorage {
    private const val PREFS_NAME = "pocketchat"
    private const val KEY_ACTIVE_MODEL = "active_model_filename"

    fun modelsDir(context: Context): File =
        context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models").apply { mkdirs() }

    fun downloadedModels(context: Context): List<File> =
        modelsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * The model to load: whatever's explicitly marked active, falling back to
     * any .gguf already on disk (e.g. adb-pushed before this screen existed).
     */
    fun activeModelFile(context: Context): File? {
        val activeName = activeModelFilename(context)
        if (activeName != null) {
            val f = File(modelsDir(context), activeName)
            if (f.exists()) return f
        }
        return downloadedModels(context).firstOrNull()
    }

    fun activeModelFilename(context: Context): String? = prefs(context).getString(KEY_ACTIVE_MODEL, null)

    fun setActiveModel(context: Context, file: File) {
        prefs(context).edit().putString(KEY_ACTIVE_MODEL, file.name).apply()
    }

    fun deleteModel(context: Context, file: File) {
        file.delete()
        if (prefs(context).getString(KEY_ACTIVE_MODEL, null) == file.name) {
            prefs(context).edit().remove(KEY_ACTIVE_MODEL).apply()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
