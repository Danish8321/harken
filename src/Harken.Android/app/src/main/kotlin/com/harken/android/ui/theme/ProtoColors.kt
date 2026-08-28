package com.harken.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Color system carried over from the Claude Design prototype port (formerly
// ui/prototype/ProtoTheme.kt) — this is now the merged app's visual language for
// Record/Library/Settings/Onboarding content.

// Fonts and easings live in Type.kt — this file is colour only (UI-029).

/**
 * Semantic color roles for the app's visual language. Fields are named for what they
 * mean, not their hue or position: [stateLive] / [stateDone] / [stateError] are the
 * three status colors (warm = live/active, sage = connected/summarized/success,
 * red = failed), each with a foreground that stays legible on its own fill.
 *
 * [accent] / [onAccent] were formerly standalone top-level vals (`ProtoAccentColor` /
 * `ProtoAccentOn`) identical in both themes — moving them here doesn't change either
 * theme's rendering, but it means a restyle only ever touches this file.
 *
 * The `ink*` ramp is opacities of the same ink color, ordered subtle -> strong. They
 * used to be named after their dark-theme alpha (`ink28`, `ink7`), but the light theme
 * uses different alphas for the same roles, which made the numeric names wrong in
 * light mode. Naming by tier instead of number stays true in both themes.
 */
@Immutable
data class ProtoColors(
    val screenBg: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textSecondary: Color,
    val navBg: Color,
    val pillTrack: Color,
    val skeleton: Color,
    /** Theme-aware solid accent — the only true brand primitive; same hex in both themes today. */
    val accent: Color,
    val onAccent: Color,
    /** Warm fill: "live / active" — the recording pill, the capturing badge. */
    val stateLive: Color,
    val stateLiveFg: Color,
    /** Sage fill: "connected / summarized / done". */
    val stateDone: Color,
    val stateDoneFg: Color,
    val stateDoneSoft: Color,
    /** "Done / connected / healthy" foreground, legible directly on [card] and [screenBg]. */
    val success: Color,
    val stateError: Color,
    val stateErrorFg: Color,
    val meterBg: Color,
    val inkSubtle: Color,
    val inkStrong: Color,
)

// Wire: slate neutrals, a single warm tan/gold accent used for both the resting
// brand color and the live/recording state (UI-020) — error and success stay their
// own distinct hues (red/green) since those are safety-relevant status signals, not
// decoration.
//
// UI-024 lifts the dark theme off near-black onto the four-swatch reference strip
// (#2C313A ground / #BFA789 accent / #464D56 border / #A0A6AD muted ink). The strip is
// flat, authored color, so it's the spec; the per-role values below come from the
// rendered mockup screens where they agree with it. Two roles deliberately don't:
//   - inactive nav (#828A94 in the mockup) is only 3.7:1 on the new ground, which passes
//     for an icon but not for the label sitting under it — nav uses textSecondary
//     (#A0A6AD, 5.3:1) instead, and #828A94 survives as the skeleton/ink tone.
//   - the ink ramp's alphas step up across the board: the same 0.28 that read as a
//     visible hairline on #0E1316 nearly vanishes against a ground this light, which
//     would have quietly erased the idle meter's waveform bars.
private val darkInk = Color(0xFFD1C9BE)
private val lightInk = Color(0xFF10161A)

val ProtoDarkColors = ProtoColors(
    screenBg = Color(0xFF2C313A),
    card = Color(0xFF3C414A),
    cardBorder = Color(0xFF464D56),
    text = Color(0xFFD1C9BE),
    textSecondary = Color(0xFFA0A6AD),
    navBg = Color(0xFF3C414A),
    pillTrack = Color(0xFF464D56),
    skeleton = Color(0xFF828A94),
    accent = Color(0xFFBFA789),
    onAccent = Color(0xFF2B2016),
    // Recording-live now rides the same accent as everything else (UI-020) — same
    // solid-tan-fill / dark-brown-icon pattern as the resting mic circle, so "live"
    // and "idle" read as the same instrument rather than two different color systems.
    stateLive = Color(0xFFBFA789),
    stateLiveFg = Color(0xFF2B2016),
    stateDone = Color(0xFF2E3D33),
    stateDoneFg = Color(0xFF8FBF9A),
    stateDoneSoft = Color(0xFF28352C),
    success = Color(0xFF8FBF9A),
    stateError = Color(0xFFE74C3C),
    stateErrorFg = Color(0xFF2B0B08),
    // The one surface that still goes darker than the ground — the meter is a readout
    // behind the signal, and it has to sit under the card tier, not float above it.
    meterBg = Color(0xFF232830),
    inkSubtle = darkInk.copy(alpha = 0.60f),
    inkStrong = darkInk.copy(alpha = 0.75f),
)

val ProtoLightColors = ProtoColors(
    screenBg = Color(0xFFF3F6F7),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFDDE6E8),
    text = Color(0xFF10161A),
    textSecondary = Color(0xFF5B676C),
    navBg = Color(0xFFFFFFFF),
    pillTrack = Color(0xFFE4EAEC),
    skeleton = Color(0xFFDDE6E8),
    accent = Color(0xFF8A744A),
    onAccent = Color(0xFFFDF4E8),
    stateLive = Color(0xFF8A744A),
    stateLiveFg = Color(0xFFFDF4E8),
    stateDone = Color(0xFFDCEBDF),
    stateDoneFg = Color(0xFF1F4A2B),
    stateDoneSoft = Color(0xFFC9E0CE),
    success = Color(0xFF2F6B3E),
    stateError = Color(0xFFC7392F),
    stateErrorFg = Color(0xFFFFFFFF),
    meterBg = Color(0xFFE4EAEC),
    inkSubtle = lightInk.copy(alpha = 0.60f),
    inkStrong = lightInk.copy(alpha = 0.72f),
)

fun protoColors(light: Boolean): ProtoColors = if (light) ProtoLightColors else ProtoDarkColors

/**
 * The single resolved palette for the current theme, provided once by [HarkenTheme] from
 * the real persisted setting. Screens read this instead of each independently resolving
 * their own AppSettings/DataStore collection — before this, 8 composables did that
 * redundantly, and theme resolution (system-dark fallback, mode mapping) was written three
 * times across MainActivity/Theme.kt/this file.
 */
val LocalProtoColors = staticCompositionLocalOf<ProtoColors> {
    error("LocalProtoColors not provided — wrap content in HarkenTheme")
}
