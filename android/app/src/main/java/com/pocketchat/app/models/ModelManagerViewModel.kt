package com.pocketchat.app.models

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
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
import kotlinx.coroutines.suspendCancellableCoroutine

private const val PROGRESS_UPDATE_STEP_BYTES = 256L * 1024 // throttle UI updates to ~every 256 KiB
private const val MAX_BACKOFF_MILLIS = 30_000L

sealed interface ModelRowStatus {
    data object NotDownloaded : ModelRowStatus
    /** NFR-019: waiting for the currently-active download (elsewhere in the list) to finish. */
    data class Queued(val position: Int) : ModelRowStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelRowStatus
    /** Actively retrying on its own (network hiccup or waiting for connectivity) — [pause] still works, no [resume] needed. */
    data class Reconnecting(val downloadedBytes: Long, val totalBytes: Long, val reason: String) : ModelRowStatus
    /** Stopped and waiting for the user — either they tapped pause, or a partial file was found at app start. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val reason: String) : ModelRowStatus
    data object Downloaded : ModelRowStatus
    /** Not resumable — e.g. a real 404, or FR-037's storage check failing. The partial file (if any) has been discarded. */
    data class Failed(val message: String) : ModelRowStatus
}

/** [stats] is null until at least one generation has completed against this model (FR-043). */
data class ModelRow(val entry: ModelCatalogEntry, val status: ModelRowStatus, val stats: ResponseStatsStorage.Stats? = null)

data class ModelManagerUiState(
    val totalRamBytes: Long = 0L,
    val ramTier: RamTier = RamTier.FLOOR,
    val activeModelFilename: String? = null,
    val rows: List<ModelRow> = emptyList(),
    /** FR-012: set when a local-file import fails before a row for it even exists to show a Failed status on. */
    val importError: String? = null,
)

/** Retrying won't fix this (e.g. 404) — as opposed to network hiccups or transient 5xx/429s. */
private class PermanentDownloadFailure(message: String) : Exception(message)

class ModelManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    /** NFR-019: only one entry downloads at a time; everything else waits here. */
    private val downloadQueue = ArrayDeque<ModelCatalogEntry>()
    private var activeDownloadEntryId: String? = null

    init {
        refresh()
    }

    /** Re-scans disk for downloaded/partial models and re-detects RAM; safe to call any time. */
    fun refresh() {
        val app = getApplication<Application>()
        val downloadedFilenames = ModelStorage.downloadedModels(app).map { it.name }.toSet()
        val totalRam = detectTotalRamBytes(app)
        // FR-012: user-imported entries live alongside the static catalog, not
        // in place of it — same row treatment (download/activate/delete) either way.
        val allEntries = ModelCatalog.entries + CustomModelStorage.list(app)

        _uiState.update { state ->
            val rows = allEntries.map { entry ->
                val existingStatus = state.rows.find { it.entry.id == entry.id }?.status
                val partial = partialFile(app, entry)
                val status = when {
                    // Don't clobber an in-flight/pausable/queued state with a plain re-scan.
                    existingStatus is ModelRowStatus.Downloading -> existingStatus
                    existingStatus is ModelRowStatus.Reconnecting -> existingStatus
                    existingStatus is ModelRowStatus.Paused -> existingStatus
                    existingStatus is ModelRowStatus.Queued -> existingStatus
                    entry.filename in downloadedFilenames -> ModelRowStatus.Downloaded
                    // A .part file with no in-memory state means the app was killed
                    // mid-download — surface it as resumable rather than losing it.
                    partial.exists() -> ModelRowStatus.Paused(partial.length(), entry.approxSizeBytes, "interrupted")
                    else -> ModelRowStatus.NotDownloaded
                }
                ModelRow(entry, status, ResponseStatsStorage.stats(app, entry.filename))
            }
            state.copy(
                totalRamBytes = totalRam,
                ramTier = RamTier.recommendedFor(totalRam),
                activeModelFilename = ModelStorage.activeModelFilename(app),
                rows = rows,
            )
        }
    }

    /**
     * Starts a fresh download, or resumes one from a partial file left by a
     * pause/interruption. NFR-019: if a different entry is already
     * downloading, this one queues instead of starting immediately — only
     * one download runs at a time, to keep FR-005's resumable-download state
     * machine simple to reason about.
     */
    fun download(entry: ModelCatalogEntry) {
        if (downloadJobs[entry.id]?.isActive == true) return
        if (activeDownloadEntryId != null && activeDownloadEntryId != entry.id) {
            if (downloadQueue.none { it.id == entry.id }) downloadQueue.addLast(entry)
            refreshQueuePositions()
            return
        }
        startDownloadNow(entry)
    }

    /** NFR-019: pulls a not-yet-started entry back out of the queue. */
    fun cancelQueuedDownload(entry: ModelCatalogEntry) {
        downloadQueue.removeAll { it.id == entry.id }
        updateRowStatus(entry.id, ModelRowStatus.NotDownloaded)
        refreshQueuePositions()
    }

    private fun startDownloadNow(entry: ModelCatalogEntry) {
        val app = getApplication<Application>()
        val existingBytes = partialFile(app, entry).length()

        // FR-037: check remaining bytes (not the full size) against free space,
        // so a resumed download isn't blocked by space it doesn't actually need
        // anymore. Skipped for a size we don't know ahead of time (a freshly
        // added custom URL entry — see addCustomUrlModel) rather than guessing.
        if (entry.approxSizeBytes > 0) {
            val remaining = (entry.approxSizeBytes - existingBytes).coerceAtLeast(0)
            val available = StatFs(ModelStorage.modelsDir(app).path).availableBytes
            if (available < remaining) {
                updateRowStatus(
                    entry.id,
                    ModelRowStatus.Failed("not enough free storage — need ~${formatBytes(remaining)} more, only ${formatBytes(available)} free"),
                )
                return
            }
        }

        activeDownloadEntryId = entry.id
        // Immediate feedback — downloadOnce() posts its first real update only once
        // bytes actually start arriving, which can lag behind the tap on a slow link.
        updateRowStatus(entry.id, ModelRowStatus.Downloading(existingBytes, entry.approxSizeBytes))
        downloadJobs[entry.id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                runDownload(entry)
            } finally {
                // Runs on every exit path (success, permanent failure, or the
                // user cancelling via pause/discard) — frees the "one at a
                // time" slot for whatever's next in line either way.
                if (activeDownloadEntryId == entry.id) activeDownloadEntryId = null
                startNextQueued()
            }
        }
    }

    private fun startNextQueued() {
        val next = downloadQueue.removeFirstOrNull() ?: return
        refreshQueuePositions()
        startDownloadNow(next)
    }

    private fun refreshQueuePositions() {
        downloadQueue.forEachIndexed { index, entry -> updateRowStatus(entry.id, ModelRowStatus.Queued(index + 1)) }
    }

    /** Stops an active/reconnecting download without discarding progress — resumable via [download]. */
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
        // A harmless no-op for a catalog entry that was never custom — only
        // actually removes anything for a user-imported one (FR-012), so it
        // doesn't reappear as a phantom "not downloaded" row forever.
        CustomModelStorage.remove(app, entry.id)
        refresh()
    }

    /**
     * FR-012: registers a direct .gguf URL as a new downloadable entry and
     * starts it immediately. [approxSizeBytes] is left at 0 (unknown ahead of
     * time for an arbitrary URL) — the real total is resolved from the
     * response's actual Content-Length once the download starts; FR-037's
     * storage check is skipped for this entry until then rather than guessing.
     */
    fun addCustomUrlModel(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return
        val app = getApplication<Application>()
        val baseName = trimmedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "custom-model" }
        val filename = if (baseName.endsWith(".gguf", ignoreCase = true)) baseName else "$baseName.gguf"
        val entry = ModelCatalogEntry(
            id = "custom-$filename",
            displayName = filename.removeSuffix(".gguf"),
            quant = "custom",
            url = trimmedUrl,
            filename = filename,
            approxSizeBytes = 0L,
            tier = RamTier.FLOOR, // unknown fit for this hardware -- FLOOR is a label here, not an enforced gate (NFR-014)
        )
        CustomModelStorage.add(app, entry)
        refresh()
        download(entry)
    }

    /**
     * FR-012: copies a Storage-Access-Framework-picked file into the app's
     * own models directory (native `nativeLoadModel` needs a real filesystem
     * path, not a content:// URI) and validates its GGUF header before
     * registering it — an invalid file is deleted immediately rather than
     * left around to fail unhelpfully later inside core/inference.
     */
    fun importLocalFile(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val baseName = queryDisplayName(app, uri) ?: "imported-${System.currentTimeMillis()}"
            val filename = if (baseName.endsWith(".gguf", ignoreCase = true)) baseName else "$baseName.gguf"
            val dest = File(ModelStorage.modelsDir(app), filename)
            try {
                val opened = app.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
                if (!opened) {
                    _uiState.update { it.copy(importError = "couldn't open the picked file") }
                    return@launch
                }
                if (!isValidGgufFile(dest)) {
                    dest.delete()
                    _uiState.update { it.copy(importError = "not a valid GGUF file (missing GGUF header) — nothing was imported") }
                    return@launch
                }
                CustomModelStorage.add(
                    app,
                    ModelCatalogEntry(
                        id = "custom-$filename",
                        displayName = filename.removeSuffix(".gguf"),
                        quant = "custom",
                        url = "", // imported directly, not downloaded -- no remote source to record
                        filename = filename,
                        approxSizeBytes = dest.length(),
                        tier = RamTier.FLOOR,
                    ),
                )
                refresh()
            } catch (e: Exception) {
                dest.delete()
                _uiState.update { it.copy(importError = e.message ?: "import failed") }
            }
        }
    }

    fun dismissImportError() {
        _uiState.update { it.copy(importError = null) }
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

    /**
     * Drives [downloadOnce], retrying transient/connectivity failures
     * indefinitely — this only stops on success, a [PermanentDownloadFailure],
     * or the user explicitly calling [pauseDownload]/[discardDownload]. A
     * spotty connection can drop for well longer than a few quick retries
     * would cover, so there's no retry-count ceiling here; when there's no
     * network at all it waits on a ConnectivityManager callback (near-zero
     * cost) instead of hammering DNS on a timer.
     */
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
                if (!isNetworkAvailable(app)) {
                    updateRowStatus(entry.id, ModelRowStatus.Reconnecting(bytes, entry.approxSizeBytes, "waiting for network connection…"))
                    awaitNetworkAvailable(app)
                } else {
                    val backoff = backoffMillis(attempt)
                    updateRowStatus(
                        entry.id,
                        ModelRowStatus.Reconnecting(bytes, entry.approxSizeBytes, "${e.message ?: "connection error"} — retrying in ${backoff / 1000}s…"),
                    )
                    delay(backoff)
                }
                updateRowStatus(entry.id, ModelRowStatus.Downloading(bytes, entry.approxSizeBytes))
            }
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        (1_000L * (1L shl (attempt - 1).coerceAtMost(20))).coerceAtMost(MAX_BACKOFF_MILLIS)

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
                if (code in 400..499 && code != 429) throw PermanentDownloadFailure(httpErrorMessage(code))
                throw IOException(httpErrorMessage(code))
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
                throw IOException("failed to move downloaded file into place — check available storage space")
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

/** FR-012: SAF only gives a content:// URI — this is how you ask it what the user would recognize as the filename. */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

/** FR-037: same shape as ModelManagerScreen's formatSize, kept separate since that one's UI-layer-private. */
private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.1fgb".format(gb) else "%.0fmb".format(bytes / 1024.0 / 1024.0)
}

/** A bare status code isn't self-explanatory to someone who isn't reading HTTP specs. */
private fun httpErrorMessage(code: Int): String {
    val detail = when (code) {
        401, 403 -> "access denied — the model link may require authentication"
        404 -> "file not found on server — the download link may be broken or removed"
        410 -> "file no longer available on server"
        408 -> "server timed out waiting for the request"
        429 -> "rate-limited by server — too many requests"
        in 500..599 -> "server error — try again later"
        else -> "unexpected server response"
    }
    return "HTTP $code — $detail"
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Suspends until ConnectivityManager reports a validated internet-capable network — cancellable. */
private suspend fun awaitNetworkAvailable(context: Context) {
    if (isNetworkAvailable(context)) return

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    suspendCancellableCoroutine<Unit> { cont ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Otherwise this callback stays registered with the system for
                // the rest of the app's process lifetime — on a connection that
                // drops and reconnects repeatedly, each cycle through
                // runDownload()'s retry loop would leak another one.
                runCatching { cm.unregisterNetworkCallback(this) }
                if (cont.isActive) cont.resume(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(request, callback)
        cont.invokeOnCancellation {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
}
