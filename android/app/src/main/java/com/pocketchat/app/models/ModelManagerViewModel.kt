package com.pocketchat.app.models

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_AUTO_RETRIES = 3
private const val PROGRESS_UPDATE_STEP_BYTES = 256L * 1024 // throttle UI updates to ~every 256 KiB

sealed interface ModelRowStatus {
    data object NotDownloaded : ModelRowStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelRowStatus
    /** Stopped (network hiccup, sustained outage, or user-paused) with the partial file kept — resumable. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val reason: String) : ModelRowStatus
    data object Downloaded : ModelRowStatus
    /** Not resumable — e.g. a 404. The partial file (if any) has been discarded. */
    data class Failed(val message: String) : ModelRowStatus
}

data class ModelRow(val entry: ModelCatalogEntry, val status: ModelRowStatus)

data class ModelManagerUiState(
    val totalRamBytes: Long = 0L,
    val ramTier: RamTier = RamTier.FLOOR,
    val activeModelFilename: String? = null,
    val rows: List<ModelRow> = emptyList(),
)

/** Retrying won't fix this (e.g. 404) — as opposed to network hiccups or transient 5xx/429s. */
private class PermanentDownloadFailure(message: String) : Exception(message)

class ModelManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    /** Re-scans disk for downloaded/partial models and re-detects RAM; safe to call any time. */
    fun refresh() {
        val app = getApplication<Application>()
        val downloadedFilenames = ModelStorage.downloadedModels(app).map { it.name }.toSet()
        val totalRam = detectTotalRamBytes(app)

        _uiState.update { state ->
            val rows = ModelCatalog.entries.map { entry ->
                val existingStatus = state.rows.find { it.entry.id == entry.id }?.status
                val partial = partialFile(app, entry)
                val status = when {
                    // Don't clobber an in-flight/pausable state with a plain re-scan.
                    existingStatus is ModelRowStatus.Downloading -> existingStatus
                    existingStatus is ModelRowStatus.Paused -> existingStatus
                    entry.filename in downloadedFilenames -> ModelRowStatus.Downloaded
                    // A .part file with no in-memory state means the app was killed
                    // mid-download — surface it as resumable rather than losing it.
                    partial.exists() -> ModelRowStatus.Paused(partial.length(), entry.approxSizeBytes, "interrupted")
                    else -> ModelRowStatus.NotDownloaded
                }
                ModelRow(entry, status)
            }
            state.copy(
                totalRamBytes = totalRam,
                ramTier = RamTier.recommendedFor(totalRam),
                activeModelFilename = ModelStorage.activeModelFilename(app),
                rows = rows,
            )
        }
    }

    /** Starts a fresh download, or resumes one from a partial file left by a pause/interruption. */
    fun download(entry: ModelCatalogEntry) {
        if (downloadJobs[entry.id]?.isActive == true) return
        // Immediate feedback — downloadOnce() posts its first real update only once
        // bytes actually start arriving, which can lag behind the tap on a slow link.
        val existingBytes = partialFile(getApplication<Application>(), entry).length()
        updateRowStatus(entry.id, ModelRowStatus.Downloading(existingBytes, entry.approxSizeBytes))
        downloadJobs[entry.id] = viewModelScope.launch(Dispatchers.IO) { runDownload(entry) }
    }

    /** Stops an active download without discarding progress — resumable via [download]. */
    fun pauseDownload(entry: ModelCatalogEntry) {
        downloadJobs[entry.id]?.cancel()
    }

    /** Abandons a paused/failed download entirely, deleting any partial file. */
    fun discardDownload(entry: ModelCatalogEntry) {
        downloadJobs[entry.id]?.cancel()
        partialFile(getApplication<Application>(), entry).delete()
        updateRowStatus(entry.id, ModelRowStatus.NotDownloaded)
    }

    fun delete(entry: ModelCatalogEntry) {
        val app = getApplication<Application>()
        ModelStorage.deleteModel(app, File(ModelStorage.modelsDir(app), entry.filename))
        refresh()
    }

    fun setActive(entry: ModelCatalogEntry) {
        val app = getApplication<Application>()
        val file = File(ModelStorage.modelsDir(app), entry.filename)
        if (!file.exists()) return
        ModelStorage.setActiveModel(app, file)
        _uiState.update { it.copy(activeModelFilename = entry.filename) }
    }

    private fun partialFile(app: Application, entry: ModelCatalogEntry): File =
        File(ModelStorage.modelsDir(app), entry.filename + ".part")

    /** Drives [downloadOnce], auto-retrying transient failures with backoff before settling into [ModelRowStatus.Paused]. */
    private suspend fun runDownload(entry: ModelCatalogEntry) {
        val app = getApplication<Application>()
        val tempFile = partialFile(app, entry)
        val finalFile = File(ModelStorage.modelsDir(app), entry.filename)

        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadOnce(entry, tempFile, finalFile)
                updateRowStatus(entry.id, ModelRowStatus.Downloaded)
                return
            } catch (e: CancellationException) {
                val bytes = tempFile.length()
                updateRowStatus(
                    entry.id,
                    if (bytes > 0) ModelRowStatus.Paused(bytes, entry.approxSizeBytes, "paused") else ModelRowStatus.NotDownloaded,
                )
                throw e
            } catch (e: PermanentDownloadFailure) {
                tempFile.delete()
                updateRowStatus(entry.id, ModelRowStatus.Failed(e.message ?: "download failed"))
                return
            } catch (e: Exception) {
                val bytes = tempFile.length()
                if (attempt >= MAX_AUTO_RETRIES) {
                    updateRowStatus(
                        entry.id,
                        ModelRowStatus.Paused(bytes, entry.approxSizeBytes, (e.message ?: "network error") + " — tap resume to retry"),
                    )
                    return
                }
                updateRowStatus(
                    entry.id,
                    ModelRowStatus.Paused(bytes, entry.approxSizeBytes, "connection lost, retrying ($attempt/$MAX_AUTO_RETRIES)…"),
                )
                delay(1_000L * (1L shl (attempt - 1))) // 1s, 2s, 4s
            }
        }
    }

    /** One connect-and-stream attempt. Resumes via an HTTP Range request when `tempFile` already has bytes. */
    private suspend fun downloadOnce(entry: ModelCatalogEntry, tempFile: File, finalFile: File) {
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }
            connection.connect()

            val resuming = existingBytes > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (existingBytes > 0 && !resuming && connection.responseCode == HttpURLConnection.HTTP_OK) {
                // Server ignored our Range header and is sending the whole file again.
                tempFile.delete()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                if (code in 400..499 && code != 429) throw PermanentDownloadFailure("HTTP $code")
                throw IOException("HTTP $code")
            }

            val startBytes = if (resuming) existingBytes else 0L
            val totalBytes = if (resuming) {
                startBytes + (connection.contentLengthLong.takeIf { it > 0 } ?: (entry.approxSizeBytes - startBytes).coerceAtLeast(0))
            } else {
                connection.contentLengthLong.takeIf { it > 0 } ?: entry.approxSizeBytes
            }

            var downloaded = startBytes
            connection.inputStream.use { input ->
                FileOutputStream(tempFile, resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReported = downloaded
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= PROGRESS_UPDATE_STEP_BYTES) {
                            lastReported = downloaded
                            updateRowStatus(entry.id, ModelRowStatus.Downloading(downloaded, totalBytes))
                        }
                    }
                }
            }
            updateRowStatus(entry.id, ModelRowStatus.Downloading(downloaded, totalBytes))

            if (!tempFile.renameTo(finalFile)) {
                throw IOException("failed to finalize download")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun updateRowStatus(entryId: String, status: ModelRowStatus) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { row -> if (row.entry.id == entryId) row.copy(status = status) else row })
        }
    }
}

private fun detectTotalRamBytes(context: Context): Long {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)
    return info.totalMem
}
