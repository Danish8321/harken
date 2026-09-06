package com.harken.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.harken.android.container
import com.harken.android.data.AppSettings
import com.harken.android.device.DeviceCapability
import com.harken.android.export.ExportService
import com.harken.android.export.ExportState
import com.harken.android.export.ExportStatus
import com.harken.android.speech.ModelDownloadFailure
import com.harken.android.speech.ModelDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.NotStarted,
    val modelDownloadProgress: Int = 0,
    val modelDownloadError: ModelDownloadFailure? = null,
    /**
     * Whether a usable model is installed *right now*, which is no longer the same question
     * as "did the last download succeed": an update that fails leaves the previous model in
     * place, and the screen has to say so rather than offer a first-time "Download".
     */
    val modelPresent: Boolean = false,
    /**
     * The device this is running on, so the model card can say why a transcription may not
     * finish here. Read once at construction — it cannot change while the app is running.
     */
    val device: DeviceCapability = DeviceCapability(0),
)

private const val TAG = "SettingsViewModel"

// Every recording is transcribed entirely on-device (ADR-0011): no backend URL to configure,
// so Settings is theme + model management only.
class SettingsViewModel(
    application: Application,
    private val settings: AppSettings,
    private val modelDownloadManager: ModelDownloadManager,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            modelDownloadState = if (modelDownloadManager.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
            modelPresent = modelDownloadManager.isModelPresent(),
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
        val taken = runCatching {
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
        if (_uiState.value.modelDownloadState == ModelDownloadState.Downloading) return
        _uiState.value = _uiState.value.copy(modelDownloadState = ModelDownloadState.Downloading, modelDownloadError = null)
        viewModelScope.launch {
            modelDownloadManager.downloadProgress(replaceExisting = true)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        modelDownloadState = ModelDownloadState.Failed,
                        modelDownloadError = ModelDownloadFailure.of(e),
                        modelPresent = modelDownloadManager.isModelPresent(),
                    )
                }
                .onCompletion { failure ->
                    if (failure == null && _uiState.value.modelDownloadState != ModelDownloadState.Failed) {
                        _uiState.value = _uiState.value.copy(
                            modelDownloadState = ModelDownloadState.Ready,
                            modelDownloadProgress = 100,
                            modelPresent = true,
                        )
                    }
                }
                .collect { percent ->
                    _uiState.value = _uiState.value.copy(modelDownloadProgress = percent)
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
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
