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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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

enum class ConnectionCheck { None, Checking, Connected, Failed }

data class OnboardingUiState(
    val step: Int = 1,
    val baseUrl: String = AppSettings.DefaultBaseUrl,
    val connectionCheck: ConnectionCheck = ConnectionCheck.None,
    val connectionMessage: String? = null,
)

// Ports src/Harken.Mobile/Pages/OnboardingPage.xaml.cs — same 3-step wizard, same
// test-connection-before-save flow (a bad URL never gets persisted).
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(baseUrl = url)
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(baseUrl = value, connectionCheck = ConnectionCheck.None)
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
                    _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Connected, connectionMessage = "Connected.")
                } else {
                    _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = "Backend responded with ${response.code}.")
                }
                response.close()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = "Could not reach backend: ${e.message}")
            }
        }
    }

    fun back() {
        _uiState.value = _uiState.value.copy(step = (_uiState.value.step - 1).coerceAtLeast(1))
    }

    fun next() {
        _uiState.value = _uiState.value.copy(step = (_uiState.value.step + 1).coerceAtMost(3))
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            val url = _uiState.value.baseUrl
            if (AppSettings.isValid(url)) {
                settings.setBaseUrl(url)
            }
            settings.setOnboardingComplete(true)
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            LinearProgressIndicator(
                progress = { state.step / 3f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(PillShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
            Text(
                "STEP ${state.step} OF 3",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            when (state.step) {
                1 -> {
                    Text(
                        "Connect to your backend",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "Enter the address of your Harken backend.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::onBaseUrlChanged,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        singleLine = true,
                        shape = PillShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        label = { Text("http://host:port") },
                    )
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        modifier = Modifier.padding(top = 16.dp),
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
                            ConnectedTag(modifier = Modifier.padding(top = 12.dp))
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
                }
                2 -> OnboardingExplainer(
                    icon = Icons.Filled.Lock,
                    text = "Harken records via a foreground service with a persistent notification, so it keeps recording when your screen locks. Grant microphone access when asked.",
                )
                3 -> OnboardingExplainer(
                    icon = Icons.Filled.GraphicEq,
                    text = "Record, then upload — Harken keeps the local WAV file and uploads it to your backend for transcription and summary.",
                )
            }

            Box(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.step > 1) {
                    OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(1f), shape = PillShape) { Text("Back") }
                }
                if (state.step < 3) {
                    Button(
                        onClick = viewModel::next,
                        modifier = Modifier.weight(1f),
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("Continue") }
                } else {
                    Button(
                        onClick = { viewModel.finish(onFinished) },
                        modifier = Modifier.weight(1f),
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
private fun ConnectedTag(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
            "Connected",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun OnboardingExplainer(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(modifier = Modifier.padding(top = 32.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
