package com.harken.android.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.data.AppSettings
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import kotlinx.coroutines.launch

enum class ConnectionCheck { None, Checking, Connected, Failed }

/** Which of the two theme-resolved fill pairs the step's icon badge uses. */
private enum class StepTone { Warm, Sage }

private data class OnboardStep(
    val tone: StepTone,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int,
)

private val steps = listOf(
    OnboardStep(StepTone.Warm, Icons.Filled.Mic, R.string.onboarding_step1_title, R.string.onboarding_step1_body),
    OnboardStep(StepTone.Sage, Icons.Filled.LibraryMusic, R.string.onboarding_step2_title, R.string.onboarding_step2_body),
    OnboardStep(StepTone.Warm, Icons.Filled.AutoAwesome, R.string.onboarding_step3_title, R.string.onboarding_step3_body),
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
    val reduced = LocalReducedMotion.current
    var step by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().background(c.screenBg).padding(horizontal = 24.dp, vertical = 36.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // transitionSpec is not a composable scope, so the specs are resolved out here.
            val fade = HarkenMotion.effectsDefault<Float>()
            val slide = HarkenMotion.spatialDefault<androidx.compose.ui.unit.IntOffset>()
            AnimatedContent(
                targetState = step,
                label = "onboard-step",
                // A cross-fade/slide is an enter/exit transition, not a value animation, so
                // a motion token cannot snap it — under reduced motion the step is replaced
                // outright.
                transitionSpec = {
                    if (reduced) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // Shared-axis: forward advances slide from the right, back slides
                        // from the left — same direction the step dots move in.
                        val forward = targetState > initialState
                        val enterOffset = if (forward) { w: Int -> w / 3 } else { w: Int -> -w / 3 }
                        val exitOffset = if (forward) { w: Int -> -w / 3 } else { w: Int -> w / 3 }
                        (slideInHorizontally(slide, enterOffset) + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(slide, exitOffset) + fadeOut(fade))
                    }
                },
            ) { s ->
                val current = steps[s]
                val badgeBg = if (current.tone == StepTone.Warm) c.accent else c.stateDone
                val badgeFg = if (current.tone == StepTone.Warm) c.onAccent else c.stateDoneFg
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).background(badgeBg, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(current.icon, contentDescription = null, tint = badgeFg, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(current.title), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 27.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(current.body),
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
                val width by animateDpAsState(if (active) 22.dp else 7.dp, HarkenMotion.spatialFast(), label = "dot")
                Box(
                    Modifier.padding(horizontal = 3.5.dp).height(7.dp).width(width)
                        .background(if (active) c.accent else c.cardBorder, RoundedCornerShape(4.dp)),
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Real buttons rather than Box{}.clickable: these are the only way through
            // onboarding, and as bare boxes TalkBack announced them as plain text with no
            // hint they were actionable. Button/TextButton bring the role, focus order and
            // keyboard activation with them.
            if (step < steps.lastIndex) {
                TextButton(
                    onClick = { viewModel.finish(onFinished) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = c.text),
                ) { Text(stringResource(R.string.onboarding_skip), fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.5.sp) }
            }
            Button(
                onClick = { if (step < steps.lastIndex) step += 1 else viewModel.finish(onFinished) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.onAccent,
                ),
                // The prototype's buttons are flat; Button's default elevation would put a
                // shadow under this one that no other control in the app has.
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    stringResource(if (step < steps.lastIndex) R.string.onboarding_next else R.string.onboarding_finish),
                    fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 14.5.sp,
                )
            }
        }
    }
}
