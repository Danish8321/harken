package com.harken.android.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

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
object HarkenMotion {

    /** Small controls: icons, chips, FAB shape, toggle knobs. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 1400f, visibilityThreshold = null)

    /** The default for movement: cards, indicators, sheets under half-screen. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 700f, visibilityThreshold = null)

    /** Large surfaces: the session sheet, the host screen behind it. */
    @Composable
    @ReadOnlyComposable
    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 300f, visibilityThreshold = null)

    /** Colour and alpha on small controls. */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f, visibilityThreshold = null)

    /** Colour and alpha, the default. */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f, visibilityThreshold = null)

    /** Scrims and other full-screen fades. */
    @Composable
    @ReadOnlyComposable
    fun <T> effectsSlow(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 200f, visibilityThreshold = null)
}
