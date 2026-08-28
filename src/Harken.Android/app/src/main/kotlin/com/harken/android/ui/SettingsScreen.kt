package com.harken.android.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.ui.theme.PillShape
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoColors
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors

// Prototype card styling wired to the real SettingsViewModel/AppSettings. The
// prototype's Storage "warn before cap" and Transcription provider controls have no
// backing setting anywhere in the app, so they're dropped rather than left as switches
// that silently do nothing.
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val c = rememberProtoColors()
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().background(c.screenBg).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.settings_title), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 26.sp) }

        item {
            SettingsCard(c) {
                Eyebrow(c, stringResource(R.string.settings_backend_header))
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::onBaseUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = PillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.pillTrack,
                        unfocusedContainerColor = c.pillTrack,
                        focusedTextColor = c.text,
                        unfocusedTextColor = c.text,
                        focusedBorderColor = c.accent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = c.accent,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = state.connectionCheck != ConnectionCheck.Checking,
                        shape = PillShape,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                        border = BorderStroke(1.dp, c.textSecondary),
                    ) { Text(stringResource(if (state.connectionCheck == ConnectionCheck.Checking) R.string.settings_testing else R.string.settings_test), fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                    when (state.connectionCheck) {
                        ConnectionCheck.Checking -> Text(stringResource(R.string.settings_checking), color = c.textSecondary, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        ConnectionCheck.Connected -> Row(Modifier.background(c.stateDone, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(state.connectionMessage ?: stringResource(R.string.settings_reachable), color = c.stateDoneFg, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        }
                        ConnectionCheck.Failed -> Text(
                            state.connectionMessage ?: stringResource(R.string.settings_unreachable),
                            color = c.stateError, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 11.5.sp,
                        )
                        ConnectionCheck.None -> {}
                    }
                }
            }
        }

        item {
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
        }

        item {
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
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_wallpaper_title), color = c.text, fontFamily = ProtoBodyFont, fontSize = 14.sp)
                        Text(stringResource(R.string.settings_wallpaper_body), color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor, colors = protoSwitchColors(c))
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

enum class ThemeMode(@StringRes val label: Int) {
    System(R.string.settings_theme_system),
    Light(R.string.settings_theme_light),
    Dark(R.string.settings_theme_dark),
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
    checkedThumbColor = c.accent,
    checkedTrackColor = c.stateLive,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = c.textSecondary,
    uncheckedTrackColor = c.pillTrack,
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun protoSegmentedColors(c: ProtoColors) = SegmentedButtonDefaults.colors(
    activeContainerColor = c.stateLive,
    activeContentColor = c.stateLiveFg,
    inactiveContainerColor = c.pillTrack,
    inactiveContentColor = c.textSecondary,
    activeBorderColor = Color.Transparent,
    inactiveBorderColor = c.cardBorder,
)
