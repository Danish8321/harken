package com.harken.android.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.ui.theme.PillShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SettingsUiState(
    val baseUrl: String = AppSettings.DefaultBaseUrl,
    val connectionCheck: ConnectionCheck = ConnectionCheck.None,
    val connectionMessage: String? = null,
    val savedMessage: String? = null,
)

// Ports src/Harken.Mobile/Pages/SettingsPage.xaml.cs — same validate-then-save, and
// mirrors OnboardingViewModel's test-connection-before-save flow so the two backend-URL
// entry points behave identically.
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(baseUrl = url)
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(baseUrl = value, connectionCheck = ConnectionCheck.None, connectionMessage = null, savedMessage = null)
    }

    // Separate from testConnection — Save persists a valid URL outright, exactly like
    // onboarding's Continue/finish does on step 1. Testing is a diagnostic aid, not a
    // save gate; conflating the two made Save silently double as a network call.
    fun save() {
        val url = _uiState.value.baseUrl
        if (!AppSettings.isValid(url)) {
            _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = "Enter a valid http(s) URL")
            return
        }
        viewModelScope.launch {
            settings.setBaseUrl(url)
            _uiState.value = _uiState.value.copy(savedMessage = "Saved.")
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

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
        // Centered header matching the pattern used across Capture, Recordings, and
        // the Session Detail modal — no icon needed here, so just the centered title.
        Box(modifier = Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Backend",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::onBaseUrlChanged,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            shape = PillShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            label = { Text("http://host:port") },
        )

        // Same test-connection-before-save affordance as onboarding step 1 — a bad
        // URL never gets persisted, and Settings shouldn't feel like a different flow.
        OutlinedButton(
            onClick = viewModel::testConnection,
            modifier = Modifier.padding(top = 16.dp).height(56.dp),
            shape = PillShape,
            enabled = state.connectionCheck != ConnectionCheck.Checking,
        ) {
            if (state.connectionCheck == ConnectionCheck.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Test connection")
            }
        }

        state.connectionMessage?.let {
            if (state.connectionCheck == ConnectionCheck.Connected) {
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(PillShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else if (state.connectionCheck != ConnectionCheck.Checking) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        state.savedMessage?.let {
            Text(
                text = it,
                color = com.harken.android.ui.theme.Organic.Accent600,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f))

        // Full-width bottom-anchored CTA, matching Capture's and the mockup's
        // bottom-action pattern instead of an inline non-full-width button. Persists
        // outright, same as onboarding's Continue — Test connection above is the
        // separate diagnostic step, not a save gate.
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 20.dp),
            shape = PillShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            enabled = AppSettings.isValid(state.baseUrl),
            onClick = viewModel::save,
        ) {
            Text("Save")
        }
    }
}
