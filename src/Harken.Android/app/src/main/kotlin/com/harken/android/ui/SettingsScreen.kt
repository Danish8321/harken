package com.harken.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.ui.components.ErrorState
import com.harken.android.ui.components.HarkenCard
import com.harken.android.ui.components.StatusChip
import com.harken.android.ui.theme.PillShape

// Same ViewModel as before — this is a presentation change only. What changed is that
// Settings now uses the SAME card, radius and padding as a Library row, instead of being
// a bare form with a bottom-pinned CTA that appeared nowhere else in the app.
//
// Save is gone as a separate button: a validated URL persists on blur, and Test is the
// diagnostic it always was. A form with two buttons where one silently double-booked as a
// network call was the confusing part.
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 14.dp))

        HarkenCard(Modifier.fillMaxWidth()) {
            Text("BACKEND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = PillShape,
                leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                label = { Text("http://host:port") },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = state.connectionCheck != ConnectionCheck.Checking,
                    shape = PillShape,
                    modifier = Modifier.height(44.dp),
                ) { Text(if (state.connectionCheck == ConnectionCheck.Checking) "Checking…" else "Test") }

                if (state.connectionCheck == ConnectionCheck.Connected) {
                    StatusChip(
                        label = state.connectionMessage ?: "Reachable",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        leading = {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        if (state.connectionCheck == ConnectionCheck.Failed) {
            ErrorState(
                title = "Could not reach the backend",
                body = state.connectionMessage
                    ?: "Nothing answered. Check the machine is awake, on this Wi-Fi, and started with --urls http://0.0.0.0:5057.",
                onRetry = viewModel::testConnection,
            )
        }

        HarkenCard(Modifier.fillMaxWidth()) {
            Text("SPEECH MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.modelDownloadState) {
                            ModelDownloadState.Ready -> "Ready — used for on-device transcription"
                            ModelDownloadState.Downloading -> "Downloading… ${state.modelDownloadProgress}%"
                            ModelDownloadState.Failed -> state.modelDownloadError ?: "Download failed"
                            ModelDownloadState.NotStarted -> "Not downloaded yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = viewModel::updateModel,
                    enabled = state.modelDownloadState != ModelDownloadState.Downloading,
                    shape = PillShape,
                    modifier = Modifier.height(40.dp),
                ) { Text(if (state.modelDownloadState == ModelDownloadState.Ready) "Update" else "Download") }
            }
            if (state.modelDownloadState == ModelDownloadState.Downloading) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { state.modelDownloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
            }
        }

        HarkenCard(Modifier.fillMaxWidth()) {
            Text("CAPTURE LIMITS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow("Session cap", "3 hours")
            Divider()
            SettingRow("Silence timeout", "5 minutes")
            Divider()
            SettingRow("Format", "16 kHz · 16-bit · mono")
            Text(
                "Both limits end the recording and upload it, so a forgotten session lands on the backend rather than on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HarkenCard(Modifier.fillMaxWidth()) {
            Text("APPEARANCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        icon = {},
                        label = { Text(mode.label, maxLines = 1) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wallpaper colours", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Tints the neutral ground only — terracotta and sage stay put, because in this app they mean \"live\" and \"done\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp)) {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
