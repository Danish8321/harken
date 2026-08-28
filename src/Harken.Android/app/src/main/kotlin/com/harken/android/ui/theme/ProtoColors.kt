package com.harken.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.harken.android.R
import com.harken.android.data.AppSettings
import com.harken.android.ui.ThemeMode

// Color/type system carried over from the Claude Design prototype port
// (formerly ui/prototype/ProtoTheme.kt) — this is now the merged app's visual
// language for Record/Library/Settings/Onboarding content. Every hex value below
// is copied verbatim from the .dc.html's renderVals() theme object.

private val protoFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// Caprasimo (a rounded bubblegum slab) replaced with Space Grotesk (UI-010) — geometric
// and technical, matching the Wire palette's precision-instrument direction rather than
// fighting it. Figtree stays for body copy; it was never the mismatch.
val ProtoHeadingFont = FontFamily(
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = protoFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = protoFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = protoFontProvider, weight = FontWeight.Bold),
)
val ProtoBodyFont = FontFamily(
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.ExtraBold),
)
/** Numeric/technical readouts only — recording timer, meter labels, cap countdown. */
val ProtoMonoFont = FontFamily(
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = protoFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = protoFontProvider, weight = FontWeight.Medium),
)

val ProtoEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
val ProtoOvershoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

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
 * The `ink*` ramp is four opacities of the same ink color, ordered faint -> strong.
 * They used to be named after their dark-theme alpha (`ink28`, `ink55`, `ink7`), but
 * the light theme uses different alphas for the same roles (0.32/0.60/0.72), which
 * made three of the four names wrong in light mode. Naming by tier instead of number
 * stays true in both themes, since the ordering (faint < muted < subtle < strong) holds
 * either way.
 */
@Immutable
data class ProtoColors(
    val screenBg: Color,
    val sheetBg: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textSecondary: Color,
    val navBg: Color,
    val navBorder: Color,
    val pillTrack: Color,
    val grabber: Color,
    val rowHighlight: Color,
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
    val inkFaint: Color,
    val inkMuted: Color,
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
    // One step up from the ground so a sheet reads as lifted above the screen behind it
    // rather than seamless with it — at this lightness an identical fill loses the edge.
    sheetBg = Color(0xFF333944),
    card = Color(0xFF3C414A),
    cardBorder = Color(0xFF464D56),
    text = Color(0xFFD1C9BE),
    textSecondary = Color(0xFFA0A6AD),
    navBg = Color(0xFF3C414A),
    navBorder = Color(0xFF464D56),
    pillTrack = Color(0xFF464D56),
    grabber = Color(0xFFD1C9BE),
    rowHighlight = Color(0xFF464D56),
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
    inkFaint = darkInk.copy(alpha = 0.32f),
    inkMuted = darkInk.copy(alpha = 0.45f),
    inkSubtle = darkInk.copy(alpha = 0.60f),
    inkStrong = darkInk.copy(alpha = 0.75f),
)

val ProtoLightColors = ProtoColors(
    screenBg = Color(0xFFF3F6F7),
    sheetBg = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFDDE6E8),
    text = Color(0xFF10161A),
    textSecondary = Color(0xFF5B676C),
    navBg = Color(0xFFFFFFFF),
    navBorder = Color(0xFFDDE6E8),
    pillTrack = Color(0xFFE4EAEC),
    grabber = Color(0xFF10161A),
    rowHighlight = Color(0xFFEDF2F3),
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
    inkFaint = lightInk.copy(alpha = 0.32f),
    inkMuted = lightInk.copy(alpha = 0.40f),
    inkSubtle = lightInk.copy(alpha = 0.60f),
    inkStrong = lightInk.copy(alpha = 0.72f),
)

fun protoColors(light: Boolean): ProtoColors = if (light) ProtoLightColors else ProtoDarkColors

/** Resolves the merged app's dark/light palette from the real, persisted theme setting. */
@Composable
fun rememberProtoColors(): ProtoColors {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val mode by settings.themeMode.collectAsState(initial = ThemeMode.System)
    val systemDark = isSystemInDarkTheme()
    val light = when (mode) {
        ThemeMode.Light -> true
        ThemeMode.Dark -> false
        ThemeMode.System -> !systemDark
    }
    return protoColors(light)
}
