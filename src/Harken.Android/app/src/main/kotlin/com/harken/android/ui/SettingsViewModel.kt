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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SettingsUiState(
    val baseUrl: String = AppSettings.DefaultBaseUrl,
    val connectionCheck: ConnectionCheck = ConnectionCheck.None,
    val connectionMessage: String? = null,
    val savedMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.NotStarted,
    val modelDownloadProgress: Int = 0,
    val modelDownloadError: String? = null,
)

// Ports src/Harken.Mobile/Pages/SettingsPage.xaml.cs — same validate-then-save, and
// mirrors OnboardingViewModel's test-connection-before-save flow so the two backend-URL
// entry points behave identically.
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val modelDownloadManager = ModelDownloadManager(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            modelDownloadState = if (modelDownloadManager.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(baseUrl = url)
            }
        }
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

    fun onBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(baseUrl = value, connectionCheck = ConnectionCheck.None, connectionMessage = null, savedMessage = null)
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

    fun testConnection() {
        val url = _uiState.value.baseUrl
        if (!AppSettings.isValid(url)) {
            _uiState.value = _uiState.value.copy(
                connectionCheck = ConnectionCheck.Failed,
                connectionMessage = "Enter a valid http(s) URL",
            )
            return
        }

        _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Checking, connectionMessage = "Checking...")
        viewModelScope.launch {
            try {
                val request = Request.Builder().url("${url.trimEnd('/')}/health").build()
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
                if (response.isSuccessful) {
                    settings.setBaseUrl(url)
                    _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Connected, connectionMessage = "Connected and saved.")
                } else {
                    _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = "Backend responded with ${response.code}.")
                }
                response.close()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = "Could not reach backend: ${e.message}")
            }
        }
    }
}
