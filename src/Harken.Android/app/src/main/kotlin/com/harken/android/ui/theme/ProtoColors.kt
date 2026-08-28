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

val ProtoAccentColor = Color(0xFFF6A06B)
val ProtoAccentOn = Color(0xFF402310)

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
    val accentFill: Color,
    val accentFillFg: Color,
    val accentFill2: Color,
    val accentFill2Fg: Color,
    val accentFill2Soft: Color,
    /** "Done / connected / healthy" foreground, legible directly on [card] and [screenBg]. */
    val success: Color,
    val dangerFill: Color,
    val dangerFillFg: Color,
    val meterBg: Color,
    val ink28: Color,
    val ink4: Color,
    val ink55: Color,
    val ink7: Color,
)

private val darkInk = Color(0xFFF5EAD8)
private val lightInk = Color(0xFF241F1B)

val ProtoDarkColors = ProtoColors(
    screenBg = Color(0xFF1C1A17),
    sheetBg = Color(0xFF1C1A17),
    card = Color(0xFF262320),
    cardBorder = Color(0xFF474238),
    text = Color(0xFFF5EAD8),
    textSecondary = Color(0xFFA19786),
    navBg = Color(0xFF262320),
    navBorder = Color(0xFF474238),
    pillTrack = Color(0xFF302C27),
    grabber = Color(0xFFF5EAD8),
    rowHighlight = Color(0xFF262320),
    skeleton = Color(0xFF474238),
    accentFill = Color(0xFF4A2E19),
    accentFillFg = Color(0xFFFFC6A5),
    accentFill2 = Color(0xFF333B26),
    accentFill2Fg = Color(0xFFCCDBB2),
    accentFill2Soft = Color(0xFF2B3320),
    success = Color(0xFFCCDBB2),
    dangerFill = Color(0xFF8C1D18),
    dangerFillFg = Color(0xFFF9DEDC),
    meterBg = Color(0xFF100E0C),
    ink28 = darkInk.copy(alpha = 0.28f),
    ink4 = darkInk.copy(alpha = 0.40f),
    ink55 = darkInk.copy(alpha = 0.55f),
    ink7 = darkInk.copy(alpha = 0.70f),
)

val ProtoLightColors = ProtoColors(
    screenBg = Color(0xFFFAF1E1),
    sheetBg = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE4D6BC),
    text = Color(0xFF241F1B),
    textSecondary = Color(0xFF6E6153),
    navBg = Color(0xFFFFFFFF),
    navBorder = Color(0xFFE4D6BC),
    pillTrack = Color(0xFFF1E5CC),
    grabber = Color(0xFF241F1B),
    rowHighlight = Color(0xFFF5EBD6),
    skeleton = Color(0xFFE4D6BC),
    accentFill = Color(0xFFF0975C),
    accentFillFg = Color(0xFF3A1C0B),
    accentFill2 = Color(0xFF9FB37D),
    accentFill2Fg = Color(0xFF20280F),
    accentFill2Soft = Color(0xFFC4D2A9),
    success = Color(0xFF4F6B2A),
    dangerFill = Color(0xFFE8735A),
    dangerFillFg = Color(0xFFFFFFFF),
    meterBg = Color(0xFFF1E7D0),
    ink28 = lightInk.copy(alpha = 0.32f),
    ink4 = lightInk.copy(alpha = 0.40f),
    ink55 = lightInk.copy(alpha = 0.60f),
    ink7 = lightInk.copy(alpha = 0.72f),
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
