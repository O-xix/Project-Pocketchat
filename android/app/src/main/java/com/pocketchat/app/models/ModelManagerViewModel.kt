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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ModelRowStatus {
    data object NotDownloaded : ModelRowStatus
    data class Downloading(val progress: Float) : ModelRowStatus
    data object Downloaded : ModelRowStatus
    data class Failed(val message: String) : ModelRowStatus
}

data class ModelRow(val entry: ModelCatalogEntry, val status: ModelRowStatus)

data class ModelManagerUiState(
    val totalRamBytes: Long = 0L,
    val ramTier: RamTier = RamTier.FLOOR,
    val activeModelFilename: String? = null,
    val rows: List<ModelRow> = emptyList(),
)

class ModelManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    /** Re-scans disk for downloaded models and re-detects RAM; safe to call any time. */
    fun refresh() {
        val app = getApplication<Application>()
        val downloadedFilenames = ModelStorage.downloadedModels(app).map { it.name }.toSet()
        val totalRam = detectTotalRamBytes(app)

        _uiState.update { state ->
            val rows = ModelCatalog.entries.map { entry ->
                val inFlight = state.rows.find { it.entry.id == entry.id }?.status as? ModelRowStatus.Downloading
                val status = inFlight
                    ?: if (entry.filename in downloadedFilenames) ModelRowStatus.Downloaded else ModelRowStatus.NotDownloaded
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

    fun download(entry: ModelCatalogEntry) {
        if (downloadJobs[entry.id]?.isActive == true) return
        updateRowStatus(entry.id, ModelRowStatus.Downloading(0f))
        downloadJobs[entry.id] = viewModelScope.launch(Dispatchers.IO) { runDownload(entry) }
    }

    fun cancelDownload(entry: ModelCatalogEntry) {
        downloadJobs[entry.id]?.cancel()
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

    private suspend fun runDownload(entry: ModelCatalogEntry) {
        val app = getApplication<Application>()
        val dir = ModelStorage.modelsDir(app)
        val tempFile = File(dir, entry.filename + ".part")
        val finalFile = File(dir, entry.filename)

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(entry.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: entry.approxSizeBytes

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            updateRowStatus(entry.id, ModelRowStatus.Downloading(percent / 100f))
                        }
                    }
                }
            }

            if (!tempFile.renameTo(finalFile)) {
                throw IOException("failed to finalize download")
            }
            updateRowStatus(entry.id, ModelRowStatus.Downloaded)
        } catch (e: CancellationException) {
            tempFile.delete()
            updateRowStatus(entry.id, ModelRowStatus.NotDownloaded)
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            updateRowStatus(entry.id, ModelRowStatus.Failed(e.message ?: "download failed"))
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
