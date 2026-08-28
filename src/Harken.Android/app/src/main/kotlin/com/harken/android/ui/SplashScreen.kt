package com.harken.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harken.android.R
import com.harken.android.ui.theme.LocalReducedMotion
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import kotlinx.coroutines.launch

private val EnterExit = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

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

    // 0..0.35 enter (mark + wordmark fade/scale in) · 0.35..0.65 hold · 0.65..1 exit
    // (mark dissolves; wordmark morphs to Record's slot, or the whole thing fades).
    val t = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(1800, easing = EnterExit))
        onFinished()
    }

    val enterT = (t.value / 0.35f).coerceIn(0f, 1f)
    val exitT = ((t.value - 0.65f) / 0.35f).coerceIn(0f, 1f)
    val morphT = if (destinationIsRecord) exitT else 0f
    val fadeOutT = if (destinationIsRecord) 0f else exitT

    Box(
        Modifier
            .fillMaxSize()
            .background(c.screenBg)
            .alpha(1f - fadeOutT)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
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
        Box(
            Modifier
                .align(Alignment.Center)
                .padding(bottom = 64.dp)
                .size(72.dp)
                .alpha((enterT * (1f - exitT)))
                .scale(0.7f + 0.3f * enterT)
                .background(c.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(28.dp))
        }

        val bias = BiasAlignment(morphT * -1f, morphT * -1f)
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                stringResource(R.string.record_wordmark),
                color = c.text,
                fontFamily = ProtoHeadingFont,
                fontSize = (40 - 20 * morphT).sp,
                modifier = Modifier.align(bias).graphicsLayer { alpha = enterT },
            )
        }
    }
}
