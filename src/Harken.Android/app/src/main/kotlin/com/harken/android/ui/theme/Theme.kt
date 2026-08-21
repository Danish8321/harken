package com.harken.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harken.android.R

// "Organic" design system tokens (design_handoff_harken_mobile_ui): warm cream ground,
// terracotta accent for "recording" meaning, sage accent for "safe/complete" meaning.
// Caprasimo (display headings) over Figtree (body), fetched via the Google Fonts
// provider (Play services) rather than vendoring binary font files into the repo.
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
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Organic.Accent500,
    onPrimary = Color.White,
    primaryContainer = Organic.Accent100,
    onPrimaryContainer = Organic.Accent800,
    secondary = Organic.Accent2_500,
    onSecondary = Color.White,
    secondaryContainer = Organic.Accent2_100,
    onSecondaryContainer = Organic.Accent2_800,
    background = Organic.Background,
    onBackground = Organic.TextPrimary,
    surface = Organic.Surface,
    onSurface = Organic.TextPrimary,
    surfaceVariant = Organic.Neutral200,
    onSurfaceVariant = Organic.Neutral700,
    outline = Organic.Neutral400,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Organic.Accent400,
    onPrimary = Organic.Accent900,
    primaryContainer = Organic.Accent800,
    onPrimaryContainer = Organic.Accent100,
    secondary = Organic.Accent2_400,
    onSecondary = Organic.Accent2_900,
    secondaryContainer = Organic.Accent2_800,
    onSecondaryContainer = Organic.Accent2_100,
    background = Organic.Neutral900,
    onBackground = Organic.Neutral100,
    surface = Organic.Neutral800,
    onSurface = Organic.Neutral100,
    surfaceVariant = Organic.Neutral700,
    onSurfaceVariant = Organic.Neutral300,
    outline = Organic.Neutral600,
)

private val HarkenTypography = Typography(
    headlineMedium = TextStyle(fontFamily = HeadingFont, fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = HeadingFont, fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = HeadingFont, fontSize = 22.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

private val HarkenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Pill radius (999dp) for buttons, inputs and tags — Compose has no unbounded corner
// constant, so a large fixed dp reliably clips to a full pill at these control heights.
val PillShape = RoundedCornerShape(999.dp)

@Composable
fun HarkenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HarkenTypography,
        shapes = HarkenShapes,
        content = content,
    )
}
