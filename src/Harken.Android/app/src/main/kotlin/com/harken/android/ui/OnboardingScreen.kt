package com.harken.android.ui

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.ui.theme.ProtoAccentColor
import com.harken.android.ui.theme.ProtoAccentOn
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import kotlinx.coroutines.launch

enum class ConnectionCheck { None, Checking, Connected, Failed }

/** Which of the two theme-resolved fill pairs the step's icon badge uses. */
private enum class StepTone { Warm, Sage }

private data class OnboardStep(val tone: StepTone, val icon: ImageVector, val title: String, val body: String)

private val steps = listOf(
    OnboardStep(StepTone.Warm, Icons.Filled.Mic, "Meet Harken", "A quiet recorder for meetings and field notes. Audio, transcripts and summaries — kept on your own network."),
    OnboardStep(StepTone.Sage, Icons.Filled.LibraryMusic, "Point it at your studio Mac", "Recordings upload over your own Wi-Fi to a backend you run. Nothing goes further than your LAN."),
    OnboardStep(StepTone.Warm, Icons.Filled.AutoAwesome, "Ready when you are", "Tap Record any time. We'll ask for the microphone only when you actually start."),
)

// Real ADR-0010 onboarding had a backend-URL entry step; the prototype's three-step
// intro is purely informational. Finish persists AppSettings.DefaultBaseUrl (editable
// later from the Backend card in Settings) and marks onboarding complete for real.
class OnboardingFinishViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingFinishViewModel = viewModel()) {
    val c = rememberProtoColors()
    var step by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().background(c.screenBg).padding(horizontal = 24.dp, vertical = 36.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(targetState = step, label = "onboard-step") { s ->
                val current = steps[s]
                val badgeBg = if (current.tone == StepTone.Warm) c.accentFill else c.accentFill2
                val badgeFg = if (current.tone == StepTone.Warm) c.accentFillFg else c.accentFill2Fg
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).background(badgeBg, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(current.icon, contentDescription = null, tint = badgeFg, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(current.title, color = c.text, fontFamily = ProtoHeadingFont, fontSize = 27.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        current.body,
                        color = c.textSecondary,
                        fontFamily = ProtoBodyFont,
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(260.dp),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (i in steps.indices) {
                val active = step == i
                val width by animateDpAsState(if (active) 22.dp else 7.dp, tween(300), label = "dot")
                Box(
                    Modifier.padding(horizontal = 3.5.dp).height(7.dp).width(width)
                        .background(if (active) ProtoAccentColor else c.cardBorder, RoundedCornerShape(4.dp)),
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step < steps.lastIndex) {
                Box(
                    Modifier.weight(1f).height(52.dp).background(Color.Transparent, RoundedCornerShape(999.dp))
                        .clickable { viewModel.finish(onFinished) },
                    contentAlignment = Alignment.Center,
                ) { Text("Skip", color = c.text, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.5.sp) }
            }
            Box(
                Modifier.weight(1f).height(52.dp).background(ProtoAccentColor, RoundedCornerShape(999.dp))
                    .clickable {
                        if (step < steps.lastIndex) step += 1 else viewModel.finish(onFinished)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (step < steps.lastIndex) "Next" else "Get started",
                    color = ProtoAccentOn, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.5.sp,
                )
            }
        }
    }
}
