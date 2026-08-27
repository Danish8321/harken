package com.harken.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.recording.RecordingState
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.ProtoAccentColor
import com.harken.android.ui.theme.ProtoAccentOn
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoColors
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import com.harken.android.ui.theme.rememberRecordShape
import java.util.UUID

// Prototype visuals (Claude Design .dc.html port), wired to the real CaptureViewModel:
// real mic permission flow, real RecordingController start/stop, real elapsed timer keyed
// off RecordingState.isRecording, and a live amplitude readout off RecordingState.amplitude
// — no decorative or fake animation left in this screen.

@Composable
fun RecordScreen(
    onOpenSession: (UUID) -> Unit = {},
    viewModel: CaptureViewModel = viewModel(),
) {
    val c = rememberProtoColors()
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (granted) viewModel.startRecording()
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
            Text("Harken", color = c.text, fontFamily = ProtoHeadingFont, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.background(c.accentFill2, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(Color(0xFFAEBF92), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("studio-mac", color = c.accentFill2Fg, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        if (!state.isRecording) {
            Spacer(Modifier.height(18.dp))
            Text("Ready when\nyou are.", color = c.text, fontFamily = ProtoHeadingFont, fontSize = 32.sp, lineHeight = 37.sp)
            Spacer(Modifier.height(10.dp))
            Text("16 kHz mono · caps at 3 h", color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 15.sp)
            Spacer(Modifier.height(22.dp))
            IdleMeter(c)
        } else {
            Spacer(Modifier.height(14.dp))
            Text(formatElapsed(elapsed), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 36.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.background(c.accentFill, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(ProtoAccentColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text("CAPTURING", color = c.accentFillFg, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Black, fontSize = 11.sp)
            }
            Spacer(Modifier.height(18.dp))
            LiveMeter(c, formatElapsed(elapsed))

            AnimatedVisibility(elapsed >= 10500, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(22.dp)).padding(14.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Approaching the 3-hour cap — it'll stop and upload on its own.",
                            color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 12.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(state.uploadStatus == UploadStatus.Succeeded && state.lastSessionId != null, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.card, RoundedCornerShape(22.dp))
                        .clickable { state.lastSessionId?.let(onOpenSession) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFFCCDBB2), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Uploaded · transcribing", color = c.text, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Tap to follow the transcript", color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 12.sp)
                    }
                }
            }
        }

        AnimatedVisibility(state.uploadStatus == UploadStatus.Failed, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.dangerFill, RoundedCornerShape(22.dp))
                        .clickable { viewModel.retryUpload() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = c.dangerFillFg, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Upload failed · tap to retry", color = c.dangerFillFg, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Still on this phone at ${state.lastError ?: "its original path"}.",
                            color = c.dangerFillFg, fontFamily = ProtoBodyFont, fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (state.isRecording) {
            Text(
                "Stops after 5 min silence",
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
                    state.isRecording -> viewModel.stopRecording()
                    else -> viewModel.startRecording()
                }
            }
        }
    }
}

@Composable
private fun IdleMeter(c: ProtoColors) {
    Column(
        Modifier.fillMaxWidth().height(230.dp).background(c.meterBg, RoundedCornerShape(30.dp)).padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = c.ink55, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text("INPUT IDLE", color = c.ink55, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Black, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(12) {
                Box(Modifier.padding(horizontal = 2.5.dp).width(5.dp).height(7.dp).background(c.ink28, RoundedCornerShape(3.dp)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", color = c.ink55, fontFamily = ProtoBodyFont, fontSize = 12.5.sp)
            Text("tap to start", color = c.ink55, fontFamily = ProtoBodyFont, fontSize = 12.5.sp)
        }
    }
}

/** Real amplitude off RecordingState.amplitude — the bars go flat the instant audio stops. */
@Composable
private fun LiveMeter(c: ProtoColors, elapsed: String) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(12) { add(0.35f) } } }
    val amplitude by RecordingState.amplitude.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(90)
            val next = (amplitude * 3.2f).coerceIn(0.1f, 1f)
            bars.removeAt(0)
            bars.add(next)
        }
    }

    Column(
        Modifier.fillMaxWidth().height(180.dp).background(c.meterBg, RoundedCornerShape(30.dp)).padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = ProtoAccentColor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text("LIVE INPUT · 16 KHZ MONO", color = c.ink7, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Black, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            bars.forEach { value ->
                Box(
                    Modifier
                        .padding(horizontal = 2.5.dp)
                        .width(5.dp)
                        .height(40.dp)
                        .scale(scaleX = 1f, scaleY = value)
                        .background(ProtoAccentColor, RoundedCornerShape(3.dp)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(elapsed, color = c.ink7, fontFamily = ProtoBodyFont, fontSize = 12.5.sp)
            Text("cap 3:00:00", color = c.ink7, fontFamily = ProtoBodyFont, fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onTap: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = HarkenMotion.spatialFast(),
        label = "recordPress",
    )
    FloatingActionButton(
        onClick = onTap,
        modifier = Modifier.size(88.dp).scale(scale),
        shape = rememberRecordShape(recording),
        containerColor = ProtoAccentColor,
        contentColor = ProtoAccentOn,
        interactionSource = interaction,
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "Stop recording" else "Start recording",
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
