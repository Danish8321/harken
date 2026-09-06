package com.harken.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.device.DeviceCapability
import com.harken.android.export.ExportState
import com.harken.android.export.LibraryExporter
import com.harken.android.ui.theme.PillShape
import com.harken.android.ui.theme.DynamicColorAvailable
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoColors
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.LocalProtoColors

// Prototype card styling wired to the real SettingsViewModel/AppSettings. The
// prototype's Storage "warn before cap" and Transcription provider controls have no
// backing setting anywhere in the app, so they're dropped rather than left as switches
// that silently do nothing.
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val c = LocalProtoColors.current
    val state by viewModel.uiState.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(c.screenBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 26.sp)

        SettingsCard(c) {
            Eyebrow(c, stringResource(R.string.settings_model_header))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (state.modelDownloadState) {
                        ModelDownloadState.Ready -> stringResource(R.string.settings_model_ready)
                        ModelDownloadState.Downloading -> stringResource(R.string.settings_model_downloading, state.modelDownloadProgress)
                        ModelDownloadState.Failed -> {
                            val reason = stringResource(
                                state.modelDownloadError?.messageRes() ?: R.string.settings_model_download_failed,
                            )
                            // A failed *update* is not a missing model — the installed one is
                            // untouched and still transcribes. Saying only "failed" would read
                            // as though the user had lost it.
                            if (state.modelPresent) {
                                stringResource(R.string.settings_model_update_failed, reason)
                            } else {
                                reason
                            }
                        }
                        ModelDownloadState.NotStarted -> stringResource(R.string.settings_model_not_started)
                    },
                    color = if (state.modelDownloadState == ModelDownloadState.Failed) c.stateError else c.text,
                    fontFamily = ProtoBodyFont,
                    fontSize = 13.5.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = viewModel::updateModel,
                    enabled = state.modelDownloadState != ModelDownloadState.Downloading,
                    shape = PillShape,
                    modifier = Modifier.heightIn(min = 40.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                    border = BorderStroke(1.dp, c.textSecondary),
                ) {
                    Text(
                        stringResource(if (state.modelPresent) R.string.settings_model_update else R.string.settings_model_download),
                        fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
                    )
                }
            }
            if (state.modelDownloadState == ModelDownloadState.Downloading) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { state.modelDownloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = c.accent,
                    trackColor = c.pillTrack,
                )
            }
            // Under the model, because the model is the thing that will not finish. Says so
            // permanently rather than once: the device does not change, and a warning the
            // user dismissed six weeks ago is not there when the transcription dies.
            if (state.device.isBelowMinimum) {
                Text(
                    stringResource(R.string.settings_model_low_memory, DeviceCapability.MinimumNominalGb),
                    color = c.stateError,
                    fontFamily = ProtoBodyFont,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        SettingsCard(c) {
            Eyebrow(c, stringResource(R.string.settings_capture_header))
                CaptureLimitRow(c, stringResource(R.string.settings_session_cap), stringResource(R.string.settings_session_cap_value))
                CaptureLimitDivider(c)
                CaptureLimitRow(c, stringResource(R.string.settings_silence_timeout), stringResource(R.string.settings_silence_timeout_value))
                CaptureLimitDivider(c)
                CaptureLimitRow(c, stringResource(R.string.settings_format), stringResource(R.string.settings_format_value))
                Text(
                    stringResource(R.string.settings_capture_note),
                    color = c.textSecondary,
                    fontFamily = ProtoBodyFont,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
        }

        BackupCard(c, viewModel)

        SettingsCard(c) {
            Eyebrow(c, stringResource(R.string.settings_appearance_header))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            icon = {},
                            colors = protoSegmentedColors(c),
                            label = { Text(stringResource(mode.label), fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 12.5.sp) },
                        )
                    }
                }
                // Hidden, not disabled, below API 31: wallpaper extraction does not exist
                // there, so the switch had nothing to turn on. A control that moves and
                // changes nothing is worse than one that is not offered.
                if (DynamicColorAvailable) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_wallpaper_title), color = c.text, fontFamily = ProtoBodyFont, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_wallpaper_body), color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor, colors = protoSwitchColors(c))
                    }
                }
        }

        Spacer(Modifier.height(8.dp))
    }
}

enum class ThemeMode(@StringRes val label: Int) {
    System(R.string.settings_theme_system),
    Light(R.string.settings_theme_light),
    Dark(R.string.settings_theme_dark),
}

/**
 * The backup card.
 *
 * Sits under Capture because that is where the user is already thinking about what is on
 * the phone. Leads with why it exists rather than with the button: an export is not a
 * convenience here, it is the only copy of a recording that will survive the phone
 * (ARC-033).
 */
@Composable
private fun BackupCard(c: ProtoColors, viewModel: SettingsViewModel) {
    val state by viewModel.exportState.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // Null when the user backed out of the picker. Nothing to say about that: they
        // know they cancelled.
        uri?.let(viewModel::startExport)
    }
    val busy = state is ExportState.Preparing || state is ExportState.Running

    SettingsCard(c) {
        Eyebrow(c, stringResource(R.string.settings_backup_header))
        Text(
            stringResource(R.string.settings_backup_body),
            color = c.textSecondary,
            fontFamily = ProtoBodyFont,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = when (val current = state) {
                ExportState.Idle -> ""
                ExportState.Preparing -> stringResource(R.string.settings_backup_preparing)
                is ExportState.Running -> stringResource(R.string.settings_backup_progress, current.done, current.total)
                is ExportState.Finished -> exportSummary(current.report)
                ExportState.Cancelled -> stringResource(R.string.settings_backup_cancelled)
                is ExportState.Failed -> current.reason
                    ?.let { stringResource(R.string.settings_backup_failed, it) }
                    ?: stringResource(R.string.settings_backup_failed_unknown)
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    color = if (state is ExportState.Failed) c.stateError else c.text,
                    fontFamily = ProtoBodyFont,
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = {
                    when {
                        busy -> viewModel.cancelExport()
                        state is ExportState.Idle -> pickFolder.launch(null)
                        else -> viewModel.acknowledgeExport()
                    }
                },
                shape = PillShape,
                modifier = Modifier.heightIn(min = 40.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                border = BorderStroke(1.dp, c.textSecondary),
            ) {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.settings_backup_cancel
                            state is ExportState.Idle -> R.string.settings_backup_export
                            else -> R.string.settings_backup_dismiss
                        },
                    ),
                    fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
                )
            }
        }
        val running = state
        if (running is ExportState.Running && running.total > 0) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { running.done.toFloat() / running.total },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = c.accent,
                trackColor = c.pillTrack,
            )
        }
    }
}

/**
 * What the export actually wrote.
 *
 * Reports the parts that are not simply "it worked" — recordings whose audio was already
 * gone, files the destination refused — because a backup the user believes is complete
 * and is not is the failure this whole feature exists to prevent.
 */
@Composable
private fun exportSummary(report: LibraryExporter.Report): String {
    if (report.recordings == 0) return stringResource(R.string.settings_backup_empty)
    val done = pluralStringResource(
        R.plurals.settings_backup_done,
        report.recordings,
        report.recordings,
        LibraryExporter.formatBytes(report.bytes),
    )
    val missing = if (report.missingAudio > 0) {
        " " + stringResource(R.string.settings_backup_done_missing_audio, report.missingAudio)
    } else {
        ""
    }
    val failed = if (report.failed > 0) {
        " " + pluralStringResource(R.plurals.settings_backup_done_failed, report.failed, report.failed)
    } else {
        ""
    }
    return done + missing + failed
}

@Composable
private fun SettingsCard(c: ProtoColors, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(24.dp)).padding(16.dp), content = content)
}

@Composable
private fun Eyebrow(c: ProtoColors, text: String) {
    Text(text, color = c.textSecondary, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.2.sp)
}

@Composable
private fun CaptureLimitRow(c: ProtoColors, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = c.text, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
        Text(value, color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 13.5.sp)
    }
}

@Composable
private fun CaptureLimitDivider(c: ProtoColors) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.cardBorder))
}

@Composable
private fun protoSwitchColors(c: ProtoColors) = SwitchDefaults.colors(
    checkedThumbColor = c.onAccent,
    checkedTrackColor = c.accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = c.textSecondary,
    uncheckedTrackColor = c.pillTrack,
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun protoSegmentedColors(c: ProtoColors) = SegmentedButtonDefaults.colors(
    activeContainerColor = c.accent,
    activeContentColor = c.onAccent,
    inactiveContainerColor = c.pillTrack,
    inactiveContentColor = c.textSecondary,
    activeBorderColor = Color.Transparent,
    inactiveBorderColor = c.cardBorder,
)
