package com.harken.android.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.ui.theme.Organic
import com.harken.android.ui.theme.PillShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Ports src/Harken.Mobile/Pages/SettingsPage.xaml.cs — same validate-then-save.
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)

    private val _baseUrl = MutableStateFlow(AppSettings.DefaultBaseUrl)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { _baseUrl.value = it }
        }
    }

    fun save(url: String, onResult: (success: Boolean, message: String) -> Unit) {
        if (!AppSettings.isValid(url)) {
            onResult(false, "Enter a valid http(s) URL")
            return
        }
        viewModelScope.launch {
            settings.setBaseUrl(url)
            onResult(true, "Base URL saved.")
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val savedBaseUrl by viewModel.baseUrl.collectAsState()
    var text by remember(savedBaseUrl) { mutableStateOf(savedBaseUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

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
            value = text,
            onValueChange = { text = it },
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

        // Plain terracotta text confirmation, no pill/background — matches the
        // one-accent decision (approved: "Terracotta, no pill").
        message?.let {
            Text(
                text = it,
                color = if (isError) MaterialTheme.colorScheme.error else Organic.Accent600,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Box(modifier = Modifier.weight(1f))

        // Full-width bottom-anchored CTA, matching Capture's and the mockup's
        // bottom-action pattern instead of an inline non-full-width button.
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 20.dp),
            shape = PillShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            onClick = {
                viewModel.save(text) { success, msg ->
                    isError = !success
                    message = msg
                }
            },
        ) {
            Text("Save")
        }
    }
}
