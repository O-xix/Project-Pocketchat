package com.pocketchat.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LOG_BYTES = 512L * 1024 // recent context matters more than full history

/**
 * FR-021: a small rolling local log for bug reports — appended to on
 * uncaught crashes ([PocketChatApplication]) and a few key error paths,
 * never transmitted anywhere on its own (NFR-001 — zero telemetry) until
 * the user explicitly exports it via Settings' share action.
 */
object DebugLog {
    private const val FILE_NAME = "debug.log"
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Never throws — logging must not itself crash the app it's trying to help debug. */
    fun log(context: Context, tag: String, message: String) {
        try {
            val file = logFile(context)
            file.appendText("${timestampFormat.format(Date())} [$tag] $message\n")
            if (file.length() > MAX_LOG_BYTES) trim(file)
        } catch (_: Exception) {
            // See doc comment above.
        }
    }

    fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        log(context, "CRASH", "uncaught exception on ${thread.name}: ${throwable.stackTraceToString()}")
    }

    fun hasContent(context: Context): Boolean = logFile(context).let { it.exists() && it.length() > 0 }

    /**
     * FR-021: the actual user-initiated export — a `FileProvider` URI rather
     * than a raw `file://` one, since Android blocks exposing app-private
     * files directly to another app's share target on API 24+.
     */
    fun share(context: Context) {
        val file = logFile(context)
        if (!file.exists() || file.length() == 0L) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Keeps only the tail once the log grows past [MAX_LOG_BYTES]. */
    private fun trim(file: File) {
        val text = file.readText()
        if (text.length > MAX_LOG_BYTES) {
            file.writeText(text.substring(text.length - MAX_LOG_BYTES.toInt()))
        }
    }
}
