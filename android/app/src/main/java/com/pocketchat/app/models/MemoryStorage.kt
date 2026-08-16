package com.pocketchat.app.models

import android.content.Context
import java.io.File

/** Where profile.txt / summaries/ live — see core/memory/pocketchat_memory.h. */
object MemoryStorage {
    fun memoryDir(context: Context): File =
        context.getExternalFilesDir("memory")
            ?: File(context.filesDir, "memory").apply { mkdirs() }
}
