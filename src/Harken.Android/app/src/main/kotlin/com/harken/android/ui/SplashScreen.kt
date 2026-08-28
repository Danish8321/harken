package com.harken.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harken.android.R
import com.harken.android.ui.theme.HarkenWaveform
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoEaseOut
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.sin

// Standard "back" overshoot curve (c1=1.70158) — bars spring past full height before
// settling, reading as a livelier pluck than a flat rise.
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
}

/**
 * Shown on every cold launch (~1.8s, tap-to-skip), before Onboarding or Record.
 *
 * The wordmark is the one element that persists across the whole sequence: it fades in
 * big and centered, then — only when the destination is Record — slides and shrinks
 * into the exact slot Record's own wordmark occupies (20dp/16dp inset, 20sp), so the
 * handoff reads as one continuous object settling into place rather than two
 * disconnected animations (UI-011). The mark above it is a separate, simpler fade:
 * it introduces the moment and dissolves as the wordmark settles, it doesn't itself
 * travel anywhere. Onboarding has no equivalent wordmark position to land on, so for a
 * first-run destination the whole thing just fades out instead of morphing.
 */
@Composable
fun SplashScreen(destinationIsRecord: Boolean, onFinished: () -> Unit) {
    val c = rememberProtoColors()
    val reducedMotion = LocalReducedMotion.current

    if (reducedMotion) {
        LaunchedEffect(Unit) { onFinished() }
        Box(Modifier.fillMaxSize().background(c.screenBg))
        return
    }

    // 0..0.15 enter (mark + wordmark fade/scale in) · 0.15..0.85 hold · 0.85..1 exit
    // (mark dissolves; wordmark morphs to Record's slot, or the whole thing fades).
    val t = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Linear over wall-clock time — the phase math below depends on t.value tracking
        // elapsed time proportionally. ProtoEaseOut is applied locally to each sub-phase's own
        // fraction instead: driving the whole 1800ms through that ease-out curve made the
        // raw value race to ~0.9 early and crawl the rest of the way, so the hard-cut phase
        // boundaries below fired well before animateTo actually completed, leaving a long
        // blank tail before onFinished().
        t.animateTo(1f, tween(1800, easing = LinearEasing))
        onFinished()
    }

    val enterT = ProtoEaseOut.transform((t.value / 0.15f).coerceIn(0f, 1f))
    val exitT = ProtoEaseOut.transform(((t.value - 0.85f) / 0.15f).coerceIn(0f, 1f))
    val morphT = if (destinationIsRecord) exitT else 0f
    val fadeOutT = if (destinationIsRecord) 0f else exitT

    Box(
        // Background stays fully opaque through exit — only the content fades. Fading the
        // background too let the raw (white) activity ground show through for the tail of
        // the exit before Onboarding composed in, reading as a stray blank flash.
        Modifier
            .fillMaxSize()
            .background(c.screenBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.splash_skip),
            ) {
                // A second animateTo on the same Animatable cancels the LaunchedEffect's
                // in-flight one (CancellationException, silently absorbed) — this call
                // owns finishing the sequence from here instead.
                scope.launch {
                    t.snapTo(1f)
                    onFinished()
                }
            },
    ) {
        val loop = rememberInfiniteTransition(label = "splashWaveform")
        val loopT by loop.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
            label = "splashWaveformT",
        )
        val glowPulse = sin(loopT * 0.923f) * 0.5f + 0.5f // slightly detuned from bar breathe so it doesn't lock-step

        // The mic itself just fades/scales in — the motion that actually reads as "an
        // instrument that's listening" is the waveform underneath it: a traveling wave
        // whose crest marches left-to-right across the full width, mirrored around the
        // row's vertical center (an oscilloscope trace, not a bar chart growing from a
        // floor) — each bar springing in with a slight overshoot rather than a flat rise.
        // A bare fading circle read as a static logo.
        val barCount = HarkenWaveform.BarCount

        // Truly centered on the screen — the earlier bottom padding was a hand-tuned
        // offset for the smaller pre-UI-020 block, but with the full-width waveform,
        // bigger wordmark and tagline it just pushed the whole thing off-center.
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The glow lives in the same Box as the mic circle, both centered on it —
            // it used to be a sibling centered on the whole screen instead, so once the
            // wave/wordmark/tagline pushed the mic above screen-center the glow stayed
            // behind, centered lower than the mic it was meant to halo.
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(260.dp)
                        .alpha((0.08f + 0.14f * glowPulse) * enterT * (1f - exitT))
                        .background(Brush.radialGradient(listOf(c.accent, c.accent.copy(alpha = 0f)))),
                )
                // Same circle/icon size as RecordScreen's RecordButton (88dp FAB, 32dp
                // icon) — same instrument, same weight, not a scaled-down standalone mark.
                Box(
                    Modifier
                        .size(88.dp)
                        .alpha(enterT * (1f - exitT))
                        .scale(0.7f + 0.3f * enterT)
                        .background(c.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(32.dp))
                }
            }
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().padding(horizontal = 32.dp).height(36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until barCount) {
                    val barEnterRaw = ((enterT * barCount) - i).coerceIn(0f, 1f)
                    val barEnter = easeOutBack(barEnterRaw)
                    val barExit = ((exitT * barCount) - (barCount - 1 - i)).coerceIn(0f, 1f)
                    // Shared shape, local envelope: the marching height comes from
                    // HarkenWaveform, and only the splash's own enter/exit scaling is
                    // applied on top of it here.
                    val h = HarkenWaveform.barHeight(loopT, i) * barEnter * (1f - barExit)
                    Box(
                        Modifier
                            .width(HarkenWaveform.BarWidth)
                            .height(h)
                            .background(c.accent, HarkenWaveform.BarShape),
                    )
                }
            }
            Text(
                stringResource(R.string.record_wordmark),
                color = c.text,
                fontFamily = ProtoHeadingFont,
                fontSize = 34.sp,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .graphicsLayer { alpha = enterT * (1f - fadeOutT) * (1f - morphT) },
            )
            Text(
                stringResource(R.string.splash_tagline),
                color = c.textSecondary,
                fontFamily = ProtoBodyFont,
                fontSize = 13.5.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .graphicsLayer { alpha = enterT * (1f - fadeOutT) * (1f - morphT) },
            )
        }

        // Only when landing on Record does the wordmark leave the small mark behind and
        // settle into Record's own header slot — a second, independently-positioned copy
        // cross-fades in as the compact one fades out, rather than one element visually
        // leaping from mid-screen to the corner.
        if (destinationIsRecord) {
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    stringResource(R.string.record_wordmark),
                    color = c.text,
                    fontFamily = ProtoHeadingFont,
                    fontSize = 20.sp,
                    modifier = Modifier.align(Alignment.TopStart).graphicsLayer { alpha = morphT },
                )
            }
        }
    }
}
