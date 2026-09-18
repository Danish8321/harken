package com.harken.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.harken.android.HarkenApplication
import com.harken.android.R
import com.harken.android.container
import com.harken.android.data.AppSettings
import com.harken.android.device.DeviceCapability
import com.harken.android.export.ExportService
import com.harken.android.export.ExportState
import com.harken.android.export.ExportStatus
import com.harken.android.speech.ModelDownloadManager
import com.harken.android.telemetry.LogExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val download: ModelDownloadUi = ModelDownloadUi(),
    /**
     * The device this is running on, so the model card can say why a transcription may not
     * finish here. Read once at construction — it cannot change while the app is running.
     */
    val device: DeviceCapability = DeviceCapability(0),
)

private const val TAG = "SettingsViewModel"

/** Rebuilt on every export, shared by the share and save paths so they cannot diverge. */
private const val LOG_ZIP_NAME = "harken-logs.zip"

private val LOG_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

// Every recording is transcribed entirely on-device (ADR-0011): no backend URL to configure,
// so Settings is theme + model management only.
class SettingsViewModel(
    application: Application,
    private val settings: AppSettings,
    private val modelDownloadManager: ModelDownloadManager,
) : AndroidViewModel(application) {
    private val _uiState =
        MutableStateFlow(
            SettingsUiState(
                download = ModelDownloadUi.of(modelDownloadManager.isModelPresent()),
                device = DeviceCapability.of(application),
            ),
        )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.themeMode.collect { mode ->
                _uiState.value = _uiState.value.copy(themeMode = mode)
            }
        }
        viewModelScope.launch {
            settings.dynamicColor.collect { enabled ->
                _uiState.value = _uiState.value.copy(dynamicColor = enabled)
            }
        }
    }

    /**
     * How the export is going.
     *
     * Read straight off the process-wide holder rather than mirrored into [uiState]: the
     * export outlives this ViewModel by design, so a copy here would be a snapshot of
     * something that has since moved on.
     */
    val exportState: StateFlow<ExportState> = ExportStatus.state

    /**
     * Starts an export into the folder the user just picked.
     *
     * The picker grants this activity access to that one directory; the grant is made
     * persistable here because the work runs in a service that outlives the activity. The
     * service releases it when the copy ends.
     */
    fun startExport(treeUri: Uri) {
        val app = getApplication<Application>()
        val taken =
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "Could not persist the export folder grant", it) }
        // Not fatal on its own: the grant this process already holds may well outlast the
        // copy. Starting is worth more than refusing on a permission that is probably fine.
        if (taken.isFailure) Log.w(TAG, "Exporting on the transient grant")
        ExportService.start(app, treeUri)
    }

    fun cancelExport() = ExportService.cancel(getApplication())

    fun acknowledgeExport() = ExportStatus.acknowledge()

    /**
     * Writes the same zip to a file the user picked, which is the only way the logs leave
     * the phone without going through somebody else's service.
     *
     * [exportLogs] below was the only route out, and on a phone with no file manager
     * installed its chooser offers Drive, Gmail, OneDrive and a handful of messaging apps —
     * so reading a monitoring build's own diagnostics meant uploading them somewhere. The
     * Diagnostics card says these logs are kept on the phone and ADR-0011 is the reason it
     * says so (ARC-071). Sharing stays: mailing a log to someone is a real thing to want.
     * It is just no longer the only door.
     *
     * Failure is logged and not shown, which matches [exportLogs] and is the weaker half of
     * this: the user sees a picker close and nothing happen. Noted in ARC-071.
     */
    fun saveLogs(target: Uri) {
        val app = getApplication<Application>()
        val sink = (app as HarkenApplication).logSink
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val zip = LogExport.zip(sink, File(app.filesDir, LOG_ZIP_NAME))
                // The picker already created the document, so this opens an existing empty
                // file rather than making one.
                app.contentResolver.openOutputStream(target)?.use { out ->
                    zip.inputStream().use { it.copyTo(out) }
                } ?: error("the picker returned a URI that will not open for writing: $target")
            }.onFailure { Log.e(TAG, "Could not write the log export to $target", it) }
        }
    }

    /**
     * The name the save picker opens with. Dated, because the interesting case is comparing
     * one monitoring run against another and `harken-logs.zip` twice in a downloads folder
     * tells you nothing about which is which.
     */
    fun suggestedLogFileName(): String = "harken-logs-${LOG_DATE_FORMAT.format(Date())}.zip"

    /**
     * Zips the durable event/crash log (see `Telemetry.attachFileSink`) and opens the share
     * sheet, for the monitoring builds this app is not otherwise wired to upload anything
     * from (ADR-0011: everything stays on the phone unless the user explicitly shares it).
     */
    fun exportLogs() {
        val app = getApplication<Application>()
        val sink = (app as HarkenApplication).logSink
        val zipFile = File(app.filesDir, LOG_ZIP_NAME)
        val uri =
            runCatching { LogExport.zip(sink, zipFile) }
                .mapCatching { FileProvider.getUriForFile(app, "${app.packageName}.files", it) }
                .getOrElse { e ->
                    Log.e(TAG, "Could not build a share URI for the log export", e)
                    return
                }
        val chooser =
            Intent
                .createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, app.getString(R.string.settings_export_logs))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    app.getString(R.string.settings_export_logs),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(chooser) }
            .onFailure { e -> Log.e(TAG, "No app accepted the log export share intent", e) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    /**
     * Re-downloads the model even if one is already present — the Settings "update" action.
     *
     * The installed model is left alone until the new one has finished downloading. Deleting
     * it up front, as this used to, meant an update interrupted by a dropped connection left
     * the user with no model and no transcription at all.
     */
    fun updateModel() {
        viewModelScope.launch {
            modelDownloadManager
                .downloadProgress(replaceExisting = true)
                .asDownloadUi(_uiState.value.download) { modelDownloadManager.isModelPresent() }
                .collect { download -> _uiState.value = _uiState.value.copy(download = download) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        application = checkNotNull(this[APPLICATION_KEY]),
                        settings = container.settings,
                        modelDownloadManager = container.modelDownloadManager,
                    )
                }
            }
    }
}
