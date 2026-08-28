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

val ProtoHeadingFont = FontFamily(
    Font(googleFont = GoogleFont("Caprasimo"), fontProvider = protoFontProvider, weight = FontWeight.Normal),
)
val ProtoBodyFont = FontFamily(
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = protoFontProvider, weight = FontWeight.ExtraBold),
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

// Wire: cool slate neutrals, a single oscilloscope-cyan accent, amber reserved
// for the live/recording state only. Picked over two other directions (Signal —
// mint-on-near-black; Tape — brick-red-on-warm-graphite) in a side-by-side
// comparison; replaces the prior warm-cream + terracotta palette (UI-009).
private val darkInk = Color(0xFFE7EEF0)
private val lightInk = Color(0xFF10161A)

val ProtoDarkColors = ProtoColors(
    screenBg = Color(0xFF0E1316),
    sheetBg = Color(0xFF0E1316),
    card = Color(0xFF161C1F),
    cardBorder = Color(0xFF212A2E),
    text = Color(0xFFE7EEF0),
    textSecondary = Color(0xFF7C8A8F),
    navBg = Color(0xFF161C1F),
    navBorder = Color(0xFF212A2E),
    pillTrack = Color(0xFF161C1F),
    grabber = Color(0xFFE7EEF0),
    rowHighlight = Color(0xFF161C1F),
    skeleton = Color(0xFF212A2E),
    accent = Color(0xFF4FC3D6),
    onAccent = Color(0xFF04262B),
    stateLive = Color(0xFF332711),
    stateLiveFg = Color(0xFFF0A93E),
    stateDone = Color(0xFF1A2A1F),
    stateDoneFg = Color(0xFF7FB88A),
    stateDoneSoft = Color(0xFF152219),
    success = Color(0xFF7FB88A),
    stateError = Color(0xFFE2584F),
    stateErrorFg = Color(0xFF2B0B08),
    meterBg = Color(0xFF0A0D0F),
    inkFaint = darkInk.copy(alpha = 0.28f),
    inkMuted = darkInk.copy(alpha = 0.40f),
    inkSubtle = darkInk.copy(alpha = 0.55f),
    inkStrong = darkInk.copy(alpha = 0.70f),
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
    accent = Color(0xFF1F9CB0),
    onAccent = Color(0xFFEAFBFD),
    stateLive = Color(0xFFF0A93E),
    stateLiveFg = Color(0xFF3A2708),
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
