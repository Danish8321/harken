package com.harken.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val HeadingFont = FontFamily(
    Font(googleFont = GoogleFont("Caprasimo"), fontProvider = fontProvider, weight = FontWeight.Normal),
)
private val BodyFont = FontFamily(
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.ExtraBold),
)

private val LightColors = lightColorScheme(
    primary = Organic.Accent500,
    onPrimary = Color.White,
    primaryContainer = Organic.Accent200,
    onPrimaryContainer = Organic.Accent700,
    secondary = Organic.Accent2_500,
    onSecondary = Color.White,
    secondaryContainer = Organic.Accent2_200,
    onSecondaryContainer = Organic.Accent2_700,
    background = Organic.Background,
    onBackground = Organic.TextPrimary,
    surface = Organic.Neutral100,
    onSurface = Organic.TextPrimary,
    surfaceVariant = Organic.Surface,
    onSurfaceVariant = Organic.Neutral700,
    outline = Organic.Neutral400,
    outlineVariant = Organic.Neutral300,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Organic.Accent400,
    onPrimary = Organic.Accent900,
    primaryContainer = Color(0xFF4A2E19),
    onPrimaryContainer = Organic.Accent300,
    secondary = Organic.Accent2_400,
    onSecondary = Organic.Accent2_900,
    secondaryContainer = Color(0xFF333B26),
    onSecondaryContainer = Organic.Accent2_300,
    background = Color(0xFF1C1A17),
    onBackground = Organic.Background,
    surface = Color(0xFF262320),
    onSurface = Organic.Background,
    surfaceVariant = Color(0xFF302C27),
    onSurfaceVariant = Organic.Neutral500,
    outline = Organic.Neutral600,
    outlineVariant = Organic.Neutral800,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

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
 * background, surface and outline. Primary and secondary stay on the Organic accents,
 * because in this app they are semantic: terracotta means "live" and sage means "done".
 * If the wallpaper could recolour them, those two words would stop meaning anything.
 */
@Composable
fun HarkenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val organic = if (darkTheme) DarkColors else LightColors
    val scheme = if (!dynamicColor) {
        organic
    } else {
        val context = LocalContext.current
        val wallpaper = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        organic.copy(
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

    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HarkenTypography,
            shapes = HarkenShapes,
            content = content,
        )
    }
}
