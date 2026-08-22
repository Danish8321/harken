package com.harken.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.ui.components.HarkenCard
import com.harken.android.ui.components.InkSurface
import com.harken.android.ui.components.StatusChip
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalInk
import com.harken.android.ui.theme.rememberRecordShape
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.random.Random

// Renamed from CaptureScreen. One screen, one job: start and stop a recording, and make
// it visible that audio is arriving. The layout is flush-left per the Organic direction —
// the old build centred everything, which is what made three screens look like three
// different products.
//
// The design sync's FloatingActionButtonMenu / ToggleFloatingActionButton are, like
// MotionScheme, Kotlin-internal in the released material3 1.4.0 artifact — see
// ui/theme/Motion.kt. The input-options affordance below is a plain FAB + DropdownMenu
// instead, same job, stable API.

@Composable
fun RecordScreen(
    onOpenSession: (UUID) -> Unit = {},
    viewModel: CaptureViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMicPermission = it
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

    var menuExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Harken", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            StatusChip(
                label = "studio-mac",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                leading = {
                    Box(Modifier.size(7.dp).then(Modifier), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(7.dp)) { drawCircle(color = Color(0xFF7A8A5E)) }
                    }
                },
            )
        }

        Spacer(Modifier.height(18.dp))

        // The elapsed time IS the display element while recording — there is nothing more
        // important on the screen at that moment, so it takes the largest type.
        Text(
            text = if (state.isRecording) formatElapsed(elapsed) else "Ready when\nyou are.",
            style = if (state.isRecording) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
        )

        Spacer(Modifier.height(10.dp))

        Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
            if (state.isRecording) {
                StatusChip(
                    label = "CAPTURING",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    leading = { LiveDot() },
                )
            } else {
                Text(
                    "16 kHz mono · caps at 3 h",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Box {
            CaptureStage(recording = state.isRecording, elapsed = elapsed)

            // The FAB half-overlaps the stage's bottom-right corner: asymmetric per the
            // Organic direction, and it keeps the primary action inside the object it acts on.
            Box(Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp, y = 34.dp)) {
                FloatingActionButton(
                    onClick = { menuExpanded = true },
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Filled.SettingsInputComponent, contentDescription = "Input options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        onClick = { menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                        text = { Text("Import audio") },
                    )
                    DropdownMenuItem(
                        onClick = { menuExpanded = false },
                        leadingIcon = { Icon(Icons.Filled.SettingsInputComponent, contentDescription = null) },
                        text = { Text("Input source") },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(visible = state.uploadStatus == UploadStatus.Succeeded && state.lastSessionId != null) {
            HarkenCard(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                onClick = { state.lastSessionId?.let(onOpenSession) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Filled.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(Modifier.weight(1f)) {
                        Text("Uploaded · transcribing", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap to follow the transcript",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Upload failure keeps the file and says so HERE, on the screen that caused it —
        // not in a banner on another tab.
        AnimatedVisibility(visible = state.uploadStatus == UploadStatus.Failed) {
            com.harken.android.ui.components.ErrorState(
                title = "Upload failed",
                body = "The recording is still on this phone at ${state.lastError ?: "its original path"} and will not be deleted. Retry when the backend is reachable.",
                onRetry = viewModel::retryUpload,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        RecordButton(
            recording = state.isRecording,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                when {
                    !hasMicPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    state.isRecording -> viewModel.stopRecording()
                    else -> viewModel.startRecording()
                }
            },
        )
    }
}

/** The ink capture stage: the dark anchor, and the only place the live waveform lives. */
@Composable
private fun CaptureStage(recording: Boolean, elapsed: Int) {
    val ink = LocalInk.current
    InkSurface(Modifier.fillMaxWidth().height(258.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = ink.onInkDim, modifier = Modifier.size(16.dp))
            Text(
                if (recording) "LIVE INPUT · 16 KHZ MONO" else "INPUT IDLE",
                style = MaterialTheme.typography.labelSmall,
                color = ink.onInkDim,
            )
        }
        LiveWaveform(recording = recording, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatElapsed(elapsed), style = MaterialTheme.typography.bodySmall, color = ink.onInkDim, maxLines = 1)
            Text(
                if (recording) "cap 3:00:00" else "tap to start",
                style = MaterialTheme.typography.bodySmall,
                color = ink.onInkDim,
                maxLines = 1,
            )
        }
    }
}

/**
 * Real amplitude, not a decorative sine. The bars scroll left as samples arrive, so the
 * waveform STOPS when the audio stops — which is the point. A recording that has gone
 * silent is visible at a glance instead of looking identical to one that has not.
 *
 * Deliberately linear rather than sprung: this is a data readout, not a state change, so
 * no spatial or effects token applies (see HarkenMotion's note).
 */
@Composable
private fun LiveWaveform(recording: Boolean, modifier: Modifier = Modifier) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(34) { add(0.06f) } } }
    val colour = MaterialTheme.colorScheme.primary
    val idle = LocalInk.current.onInkDim

    LaunchedEffect(recording) {
        while (recording) {
            kotlinx.coroutines.delay(75)
            // TODO(backend-free): swap for the real RMS of the AudioRecord buffer once
            // RecordingForegroundService exposes it — the shape of this loop does not change.
            val previous = bars.lastOrNull() ?: 0.3f
            val speech = 0.35f + 0.45f * sin(System.currentTimeMillis() / 520.0).absoluteValue.toFloat()
            val next = (previous * 0.45f + speech * 0.55f + (Random.nextFloat() - 0.42f) * 0.2f).coerceIn(0.06f, 1f)
            bars.removeAt(0)
            bars.add(next)
        }
        if (!recording) {
            for (i in bars.indices) bars[i] = 0.06f
        }
    }

    Canvas(modifier) {
        val slot = size.width / bars.size
        val barWidth = slot * 0.55f
        bars.forEachIndexed { index, value ->
            val h = (size.height * value).coerceAtLeast(4f)
            drawRoundRect(
                color = if (recording) colour else idle,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * One morphing shape reports the state: a circle at rest, a slowly turning twelve-lobe
 * cookie while capturing. The old build stacked an animated halo, a pulsing ring and a
 * separate waveform behind a static circle to say the same thing three times.
 */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = HarkenMotion.spatialFast(),
        label = "recordPress",
    )
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(96.dp).scale(scale),
        shape = rememberRecordShape(recording),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction,
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "Stop recording" else "Start recording",
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun LiveDot() {
    val alpha by androidx.compose.animation.core.rememberInfiniteTransition(label = "liveDot").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        // Ambient pulse, not a state change — a tween, deliberately.
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1300),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "liveDotAlpha",
    )
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(8.dp)) { drawCircle(color = colour.copy(alpha = alpha)) }
}

internal fun formatElapsed(totalSeconds: Int): String =
    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
