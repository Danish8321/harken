package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.AppSettings
import com.harken.android.speech.ModelDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

data class SettingsUiState(
    val savedMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.NotStarted,
    val modelDownloadProgress: Int = 0,
    val modelDownloadError: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val modelDownloadManager = ModelDownloadManager(application)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            modelDownloadState = if (modelDownloadManager.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
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

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    /** Re-downloads the model even if one is already present — the Settings "update" action. */
    fun updateModel() {
        if (_uiState.value.modelDownloadState == ModelDownloadState.Downloading) return
        modelDownloadManager.deleteModel()
        _uiState.value = _uiState.value.copy(modelDownloadState = ModelDownloadState.Downloading, modelDownloadError = null)
        viewModelScope.launch {
            modelDownloadManager.downloadProgress()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        modelDownloadState = ModelDownloadState.Failed,
                        modelDownloadError = e.message ?: "Download failed",
                    )
                }
                .onCompletion { failure ->
                    if (failure == null && _uiState.value.modelDownloadState != ModelDownloadState.Failed) {
                        _uiState.value = _uiState.value.copy(modelDownloadState = ModelDownloadState.Ready, modelDownloadProgress = 100)
                    }
                }
                .collect { percent ->
                    _uiState.value = _uiState.value.copy(modelDownloadProgress = percent)
                }
        }
    }
}
