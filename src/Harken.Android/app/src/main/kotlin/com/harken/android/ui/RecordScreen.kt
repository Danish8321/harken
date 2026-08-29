package com.harken.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.data.AppSettings
import com.harken.android.recording.RecordingState
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoColors
import com.harken.android.ui.theme.HarkenWaveform
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.ProtoMonoFont
import com.harken.android.ui.theme.LocalProtoColors
import com.harken.android.ui.theme.rememberRecordShape
import com.harken.android.ui.components.HarkenErrorDialog
import java.util.UUID
import kotlinx.coroutines.launch

// Prototype visuals (Claude Design .dc.html port), wired to the real CaptureViewModel:
// real mic permission flow, real RecordingController start/stop, real elapsed timer keyed
// off RecordingState.isRecording, and a live amplitude readout off RecordingState.amplitude
// — no decorative or fake animation left in this screen.

@Composable
fun RecordScreen(
    onOpenSession: (UUID) -> Unit = {},
    viewModel: CaptureViewModel = viewModel(),
) {
    val c = LocalProtoColors.current
    // Banner fades resolved once here: enter/exit params are not a composable scope.
    val fade = HarkenMotion.effectsDefault<Float>()
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val haptics = LocalHapticFeedback.current

    // Fires exactly once per genuine upload-status transition, not on every recomposition —
    // LaunchedEffect only restarts when the key (state.uploadStatus) itself changes.
    LaunchedEffect(state.uploadStatus) {
        when (state.uploadStatus) {
            UploadStatus.Succeeded -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            UploadStatus.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Only the recording notification depends on this (Android 13+) — a denial doesn't
    // block recording itself, so there's no launcher callback branch to react to here.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (granted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ensureNotificationPermission()
            viewModel.startRecording()
        }
    }

    var recordingError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        RecordingState.error.collect { error -> recordingError = error.message }
    }
    recordingError?.let { message ->
        HarkenErrorDialog(
            title = stringResource(R.string.error_recording_failed),
            body = message,
            onDismiss = { recordingError = null },
        )
    }

    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.isRecording) {
        if (state.isRecording) {
            elapsed = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                elapsed += 1
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.screenBg)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.record_wordmark), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 20.sp)
        }

        // Idle <-> live crossfades as one block instead of a hard cut — IdleMeter's wave
        // is a fresh infinite-transition each time it recomposes in, so an instant swap
        // popped its bars straight to their mid-loop heights, reading as a jarring reset
        // rather than a state change.
        AnimatedContent(
            targetState = state.isRecording,
            label = "idleLiveMeter",
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(fade) togetherWith fadeOut(fade)
                }
            },
        ) { recording ->
            if (!recording) {
                Column {
                    Spacer(Modifier.height(18.dp))
                    Text(stringResource(R.string.record_idle_headline), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 32.sp, lineHeight = 37.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.record_format_line), color = c.textSecondary, fontFamily = ProtoMonoFont, fontSize = 14.sp)
                    Spacer(Modifier.height(22.dp))
                    IdleMeter(c)
                }
            } else {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Text(formatElapsed(elapsed), color = c.text, fontFamily = ProtoMonoFont, fontWeight = FontWeight.Medium, fontSize = 36.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.background(c.stateLive, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(c.accent, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.record_capturing), color = c.stateLiveFg, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    LiveMeter(c, formatElapsed(elapsed))

                    AnimatedVisibility(elapsed >= 10500, enter = fadeIn(fade), exit = fadeOut(fade)) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(24.dp)).padding(16.dp)) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.record_cap_warning),
                                    color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 12.sp, lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(state.uploadStatus != UploadStatus.Idle, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Column {
                Spacer(Modifier.height(14.dp))
                UploadStatusCard(c, state.uploadStatus, state.lastSessionId, state.lastError, onOpenSession, viewModel::retryUpload)
            }
        }

        Spacer(Modifier.weight(1f, fill = true).heightIn(max = 140.dp))
        if (state.isRecording) {
            Text(
                stringResource(R.string.record_silence_hint),
                color = c.textSecondary,
                fontFamily = ProtoBodyFont,
                fontSize = 12.5.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
            RecordButton(state.isRecording) {
                when {
                    !hasMicPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    state.isRecording -> {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.stopRecording()
                    }
                    else -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ensureNotificationPermission()
                        viewModel.startRecording()
                    }
                }
            }
        }
    }
}

/**
 * Idle bars hold the splash waveform's shape at rest, unanimated — the marching trace is
 * a launch moment, not an ambient loop; playing it continuously while nothing is being
 * recorded read as the app "listening" when it plainly isn't (per review feedback).
 */
@Composable
private fun IdleMeter(c: ProtoColors) {
    MeterCard(
        c = c,
        height = 150.dp,
        iconTint = c.inkSubtle,
        label = stringResource(R.string.record_meter_idle),
        labelColor = c.inkSubtle,
        footerLeft = stringResource(R.string.record_meter_idle_elapsed),
        footerRight = stringResource(R.string.record_meter_idle_hint),
        footerColor = c.inkSubtle,
        footerLeftFont = ProtoMonoFont,
        footerRightFont = ProtoBodyFont,
    ) {
        // Same shape/spacing as SplashScreen's waveform, held at rest (moving = false) —
        // nothing is being recorded, so nothing marches.
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until HarkenWaveform.BarCount) {
                val h = HarkenWaveform.barHeight(0f, i, moving = false)
                Box(
                    Modifier
                        .width(HarkenWaveform.BarWidth)
                        .height(h)
                        .background(c.accent, HarkenWaveform.BarShape),
                )
            }
        }
    }
}

/**
 * Shared chrome for the idle/live meter cards: rounded [c.meterBg] surface, an icon+label
 * header row, and a footer row of two labels — [content] is the middle band each meter
 * fills with its own waveform. Extracted because IdleMeter and LiveMeter differed only in
 * that middle band; the surrounding padding/shape/header/footer structure was identical.
 */
@Composable
private fun MeterCard(
    c: ProtoColors,
    height: androidx.compose.ui.unit.Dp,
    iconTint: androidx.compose.ui.graphics.Color,
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    footerLeft: String,
    footerRight: String,
    footerColor: androidx.compose.ui.graphics.Color,
    footerLeftFont: androidx.compose.ui.text.font.FontFamily,
    footerRightFont: androidx.compose.ui.text.font.FontFamily,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().height(height).background(c.meterBg, RoundedCornerShape(30.dp)).padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = iconTint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = labelColor, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Black, fontSize = 11.sp)
        }
        content()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(footerLeft, color = footerColor, fontFamily = footerLeftFont, fontSize = 12.5.sp)
            Text(footerRight, color = footerColor, fontFamily = footerRightFont, fontSize = 12.5.sp)
        }
    }
}

/**
 * One card whose content morphs in place across the upload lifecycle — a spinner, then a
 * check-mark or warning glyph, via [AnimatedContent] keyed on [status] — rather than two
 * disconnected fade-in/fade-out composables that happened to occupy the same slot. Idle
 * renders nothing; the caller gates visibility.
 */
@Composable
private fun UploadStatusCard(
    c: ProtoColors,
    status: UploadStatus,
    lastSessionId: UUID?,
    lastError: String?,
    onOpenSession: (UUID) -> Unit,
    onRetry: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }

    val bg by animateColorAsState(
        when (status) {
            UploadStatus.Failed -> c.stateError
            else -> c.card
        },
        HarkenMotion.effectsDefault(),
        label = "uploadCardBg",
    )
    val shakeSpec = HarkenMotion.spatialFast<Float>()

    Row(
        Modifier
            .fillMaxWidth()
            .offset(x = shake.value.dp)
            .background(bg, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, enabled = status == UploadStatus.Succeeded || status == UploadStatus.Failed) {
                when (status) {
                    UploadStatus.Succeeded -> lastSessionId?.let(onOpenSession)
                    UploadStatus.Failed -> {
                        onRetry()
                        if (!reduced) {
                            scope.launch {
                                for (target in listOf(10f, -10f, 6f, -6f, 0f)) {
                                    shake.animateTo(target, shakeSpec)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // transitionSpec is not a composable scope, so the specs are resolved out here.
        val iconSpatial = HarkenMotion.spatialFast<Float>()
        val iconEffects = HarkenMotion.effectsFast<Float>()
        AnimatedContent(
            targetState = status,
            label = "uploadStatusIcon",
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (scaleIn(iconSpatial) + fadeIn(iconEffects)) togetherWith
                        (scaleOut(iconSpatial) + fadeOut(iconEffects))
                }
            },
        ) { s ->
            when (s) {
                UploadStatus.Succeeded -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(20.dp))
                UploadStatus.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = c.stateErrorFg, modifier = Modifier.size(20.dp))
                UploadStatus.Idle -> Unit
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val titleColor = if (status == UploadStatus.Failed) c.stateErrorFg else c.text
            val bodyColor = if (status == UploadStatus.Failed) c.stateErrorFg else c.textSecondary
            when (status) {
                UploadStatus.Succeeded -> {
                    Text(
                        stringResource(R.string.record_saved_local_title),
                        color = titleColor, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                    Text(
                        stringResource(R.string.record_saved_local_body),
                        color = bodyColor, fontFamily = ProtoBodyFont, fontSize = 12.sp,
                    )
                }
                UploadStatus.Failed -> {
                    Text(stringResource(R.string.record_upload_failed_title), color = titleColor, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        stringResource(R.string.record_upload_failed_body, lastError ?: stringResource(R.string.record_upload_failed_path_unknown)),
                        color = bodyColor, fontFamily = ProtoBodyFont, fontSize = 12.sp,
                    )
                }
                UploadStatus.Idle -> Unit
            }
        }
    }
}

/** Real amplitude off RecordingState.amplitude — the bars go flat the instant audio stops. */
@Composable
private fun LiveMeter(c: ProtoColors, elapsed: String) {
    // Same bar count as the splash/idle trace (HarkenWaveform.BarCount) — a sparser
    // buffer here read as a different, chunkier instrument instead of the same wave.
    val bars = remember { mutableStateListOf<Float>().apply { repeat(HarkenWaveform.BarCount) { add(0.1f) } } }
    val amplitude by RecordingState.amplitude.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(90)
            val next = (amplitude * 3.2f).coerceIn(0.1f, 1f)
            bars.removeAt(0)
            bars.add(next)
        }
    }

    MeterCard(
        c = c,
        height = 180.dp,
        iconTint = c.accent,
        label = stringResource(R.string.record_meter_live),
        labelColor = c.inkStrong,
        footerLeft = elapsed,
        footerRight = stringResource(R.string.record_meter_cap),
        footerColor = c.inkStrong,
        footerLeftFont = ProtoMonoFont,
        footerRightFont = ProtoMonoFont,
    ) {
        // Same oscilloscope trace as the splash/idle waveform — mirrored around center,
        // SpaceBetween across the full width — just driven by real amplitude per bar
        // instead of the phase clock, so recording reads as the same instrument at rest
        // and in use rather than switching to an unrelated bottom-anchored bar chart.
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            bars.forEach { value ->
                Box(
                    Modifier
                        .width(HarkenWaveform.BarWidth)
                        .height(HarkenWaveform.amplitudeHeight(value))
                        .background(c.accent, HarkenWaveform.BarShape),
                )
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onTap: () -> Unit) {
    val c = LocalProtoColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = HarkenMotion.spatialFast(),
        label = "recordPress",
    )
    // stateLive now rides the same accent as the resting button (UI-020), so the
    // recording/idle transition is carried entirely by the shape morph (MorphShapes.kt)
    // — no separate recolor needed.
    FloatingActionButton(
        onClick = onTap,
        modifier = Modifier.size(88.dp).scale(scale),
        shape = rememberRecordShape(recording),
        containerColor = c.accent,
        contentColor = c.onAccent,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 10.dp, pressedElevation = 4.dp),
        interactionSource = interaction,
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = stringResource(if (recording) R.string.record_stop else R.string.record_start),
            modifier = Modifier.size(32.dp),
        )
    }
}

internal fun formatElapsed(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
