package com.pocketchat.app.models

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The floor-tier model shipped inside the APK itself, if any — a .gguf file
 * under assets/models/, populated by the CI build
 * (.github/workflows/android-build.yml), not committed to git (a few
 * hundred MB is well past what a normal git push accepts anyway). Lets the
 * app be usable immediately after install with no first-run download, which
 * matters a lot on a flaky connection.
 */
object BundledModel {
    private const val ASSET_DIR = "models"

    /**
     * Copies the bundled model out of assets into [ModelStorage.modelsDir]
     * if it isn't there already, and — only on a fresh install with no
     * active model chosen yet — marks it active. A no-op if this build
     * doesn't bundle one. Blocking (file I/O) — call from a background
     * thread, and from inside a try/catch: this runs on every app launch, so
     * it must never be allowed to crash the app if extraction fails for any
     * reason (disk full, corrupted asset, etc.) — the caller's existing
     * "model failed to load" handling should absorb it instead.
     */
    fun ensureExtracted(context: Context) {
        val assetName = bundledAssetName(context) ?: return
        val dest = File(ModelStorage.modelsDir(context), assetName)

        if (!dest.exists()) {
            val tmp = File(dest.parentFile, "$assetName.extracting")
            context.assets.open("$ASSET_DIR/$assetName").use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return
            }
        }

        if (ModelStorage.activeModelFilename(context) == null) {
            ModelStorage.setActiveModel(context, dest)
        }
    }

    private fun bundledAssetName(context: Context): String? =
        try {
            // Whether a path with nothing under it returns an empty array or
            // throws isn't consistent enough across AssetManager implementations
            // to rely on either — this must degrade to "nothing bundled" either way.
            context.assets.list(ASSET_DIR)?.firstOrNull { it.endsWith(".gguf") }
        } catch (_: IOException) {
            null
        }
}
