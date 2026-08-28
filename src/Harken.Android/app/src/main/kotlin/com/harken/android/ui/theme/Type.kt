package com.harken.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.harken.android.R

// The app's type and easing primitives. These lived in ProtoColors.kt, which meant a
// file named for colours also owned the fonts, and Theme.kt kept a second, identical
// declaration of the same two families for its Material Typography (UI-029). Two copies
// of a font stack is the same failure as two copies of a hex: they agree until one is
// edited. There is now one declaration, used by both.

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

// Caprasimo (a rounded bubblegum slab) replaced with Space Grotesk (UI-010) — geometric
// and technical, matching the palette's precision-instrument direction rather than
// fighting it. Figtree stays for body copy; it was never the mismatch.
val ProtoHeadingFont = FontFamily(
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Space Grotesk"), fontProvider = fontProvider, weight = FontWeight.Bold),
)
val ProtoBodyFont = FontFamily(
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Figtree"), fontProvider = fontProvider, weight = FontWeight.ExtraBold),
)

/** Numeric/technical readouts only — recording timer, meter labels, cap countdown. */
val ProtoMonoFont = FontFamily(
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = fontProvider, weight = FontWeight.Medium),
)

val ProtoEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
