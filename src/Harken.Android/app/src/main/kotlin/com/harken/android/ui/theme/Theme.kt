package com.harken.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harken.android.R

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// Caprasimo replaced with Space Grotesk (UI-010) — matches ProtoColors.kt's ProtoHeadingFont.
private val HeadingFont = FontFamily(
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = fontProvider, weight = FontWeight.Bold),
)
private val BodyFont = FontFamily(
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.ExtraBold),
)

// Material's ColorScheme, built from ProtoColors rather than a separate Organic-based
// palette (UI-002): the app used to run two independent color systems nested inside one
// another — Proto screens on ProtoColors, Material components (SessionSheet, AppNav,
// HarkenStates, HarkenSurfaces) on this scheme. Deriving the scheme from the same
// ProtoColors instance means every Material component now inherits the Proto palette
// instead of keeping its own copy in sync by hand.
private fun protoColorScheme(c: ProtoColors, darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.stateLive,
        onPrimaryContainer = c.stateLiveFg,
        secondary = c.stateDone,
        onSecondary = c.stateDoneFg,
        secondaryContainer = c.stateDoneSoft,
        onSecondaryContainer = c.stateDoneFg,
        background = c.screenBg,
        onBackground = c.text,
        surface = c.card,
        onSurface = c.text,
        surfaceVariant = c.pillTrack,
        onSurfaceVariant = c.textSecondary,
        outline = c.cardBorder,
        outlineVariant = c.cardBorder,
        error = c.stateError,
        onError = c.stateErrorFg,
        errorContainer = c.stateError.copy(alpha = 0.18f),
        onErrorContainer = c.stateErrorFg,
    )
}

// One ramp, six steps, nothing below 12sp. The old build's 9.5sp and 10.5sp text and its
// un-overridden labelMedium (which silently rendered the nav bar in Roboto) are both
// gone: every style below names BodyFont or HeadingFont explicitly.
//
// Material 3 Expressive's "emphasized" type variants are expressed here as the weight
// step rather than a second family — 700 for titles and labels, 800 for eyebrows.
private val HarkenTypography = Typography(
    displaySmall = TextStyle(fontFamily = HeadingFont, fontSize = 38.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = HeadingFont, fontSize = 34.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = HeadingFont, fontSize = 32.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = HeadingFont, fontSize = 24.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = HeadingFont, fontSize = 21.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = BodyFont, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp),
)

// Expressive corner tokens. Containers are over-rounded per the Organic direction, and
// the two largest steps carry the ink surfaces (capture stage, player).
private val HarkenShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

val PillShape = RoundedCornerShape(999.dp)

/** Ink is a role, not a colour scheme slot — see Color.kt. */
data class InkColors(val ink: Color, val onInk: Color, val onInkDim: Color)

val LocalInk = compositionLocalOf { InkColors(Organic.InkLight, Organic.OnInk, Organic.OnInk.copy(alpha = 0.6f)) }

/**
 * @param dynamicColor when true, wallpaper extraction supplies the NEUTRALS only —
 * background, surface and outline. Primary and secondary stay on the Proto accents,
 * because in this app they are semantic: terracotta means "live" and sage means "done".
 * If the wallpaper could recolour them, those two words would stop meaning anything.
 */
@Composable
fun HarkenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = protoColorScheme(protoColors(light = !darkTheme), darkTheme)
    val scheme = if (!dynamicColor) {
        base
    } else {
        val context = LocalContext.current
        val wallpaper = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(
            background = wallpaper.background,
            onBackground = wallpaper.onBackground,
            surface = wallpaper.surface,
            onSurface = wallpaper.onSurface,
            surfaceVariant = wallpaper.surfaceVariant,
            onSurfaceVariant = wallpaper.onSurfaceVariant,
            outline = wallpaper.outline,
            outlineVariant = wallpaper.outlineVariant,
        )
    }

    val ink = if (darkTheme) {
        InkColors(Organic.InkDark, Organic.OnInk, Organic.OnInk.copy(alpha = 0.6f))
    } else {
        InkColors(Organic.InkLight, Organic.OnInk, Organic.OnInk.copy(alpha = 0.6f))
    }

    CompositionLocalProvider(
        LocalInk provides ink,
        LocalReducedMotion provides rememberReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HarkenTypography,
            shapes = HarkenShapes,
            content = content,
        )
    }
}
