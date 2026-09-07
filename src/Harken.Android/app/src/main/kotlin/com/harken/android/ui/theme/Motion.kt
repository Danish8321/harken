package com.harken.android.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.IntOffset

// Motion in this app is bound to spring tokens, never to a duration. The values below
// mirror Material 3 Expressive's own MotionScheme constants, reimplemented here because
// androidx.compose.material3's MotionScheme/MaterialTheme.motionScheme is still
// Kotlin-internal in the released material3 1.4.0 / 1.5.0-alpha artifacts (confirmed by
// inspecting both jars — the API is public at the JVM bytecode level but marked internal
// in Kotlin metadata, so it cannot be called from outside the module). Two families, and
// the distinction is not cosmetic:
//
//   spatial*  overshoot and settle. Use for anything that MOVES or RESIZES —
//             offset, scale, size, corner radius, shape morph.
//   effects*  never overshoot. Use for anything that FADES or RECOLOURS —
//             alpha, colour, elevation tint.
//
// Bouncing a colour reads as a rendering glitch, so effects tokens exist precisely so
// that you cannot accidentally spring a paint change. Speed follows element size:
// fast for small controls, default for most things, slow for full-screen surfaces.
// Every token below collapses to snap() when the user has asked the system to remove
// animations (UI-006). Doing it here rather than at each call site means a new animation
// bound to a HarkenMotion token honours the setting by construction; only animations that
// cannot be expressed as a spec — infinite loops, enter/exit transitions — read
// LocalReducedMotion themselves.
object HarkenMotion {
    /** Small controls: icons, chips, FAB shape, toggle knobs. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else spring(dampingRatio = 0.8f, stiffness = 1400f, visibilityThreshold = null)

    /** The default for movement: cards, indicators, sheets under half-screen. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialDefault(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else spring(dampingRatio = 0.8f, stiffness = 700f, visibilityThreshold = null)

    /** Large surfaces: the session sheet, the host screen behind it. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else spring(dampingRatio = 0.8f, stiffness = 300f, visibilityThreshold = null)

    /** Colour and alpha on small controls. */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 1600f,
                visibilityThreshold = null,
            )
        }

    /** Colour and alpha, the default. */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 800f,
                visibilityThreshold = null,
            )
        }

    /** Scrims and other full-screen fades. */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsSlow(): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 200f,
                visibilityThreshold = null,
            )
        }
}

/**
 * Shared-axis slide+fade: forward advances from the right, back slides from the left,
 * collapsing to a plain swap under reduced motion. [offsetDivisor] controls how far the
 * slide travels relative to the content width — smaller divisor, bigger travel.
 *
 * Enter and exit are separate functions rather than one `ContentTransform` because the
 * only caller is a NavHost, which takes the two halves individually. It used to be a
 * single transitionSpec for an AnimatedContent inside each tab's own pane, which could
 * not work: that AnimatedContent's lambda ignored its target state, so both halves of
 * the transition rendered the same screen and every tab change animated a pane against
 * itself. Lint says so directly — UnusedContentLambdaTargetStateParameter, ARC-020 —
 * and the fix is to let the navigation graph own the transition, since it is the only
 * thing that knows both the screen being left and the screen being entered.
 */
fun sharedAxisEnter(
    reduced: Boolean,
    forward: Boolean,
    fade: FiniteAnimationSpec<Float>,
    slide: FiniteAnimationSpec<IntOffset>,
    offsetDivisor: Int,
): EnterTransition =
    if (reduced) {
        EnterTransition.None
    } else {
        slideInHorizontally(slide) { w -> if (forward) w / offsetDivisor else -w / offsetDivisor } + fadeIn(fade)
    }

/** The exit half of [sharedAxisEnter]; [forward] means the same thing in both. */
fun sharedAxisExit(
    reduced: Boolean,
    forward: Boolean,
    fade: FiniteAnimationSpec<Float>,
    slide: FiniteAnimationSpec<IntOffset>,
    offsetDivisor: Int,
): ExitTransition =
    if (reduced) {
        ExitTransition.None
    } else {
        slideOutHorizontally(slide) { w -> if (forward) -w / offsetDivisor else w / offsetDivisor } + fadeOut(fade)
    }
