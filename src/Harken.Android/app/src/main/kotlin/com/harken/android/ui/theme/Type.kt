package com.harken.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.harken.android.R

// The app's type and easing primitives. These lived in ProtoColors.kt, which meant a
// file named for colours also owned the fonts, and Theme.kt kept a second, identical
// declaration of the same two families for its Material Typography (UI-029). Two copies
// of a font stack is the same failure as two copies of a hex: they agree until one is
// edited. There is now one declaration, used by both.
//
// Bundled as res/font, not fetched through Play services' GoogleFont.Provider (ARC-038):
// that provider needs Play services installed and a network round trip for a typeface
// the app can just ship. Licensed under SIL OFL 1.1 — see assets/licenses/.

// Caprasimo (a rounded bubblegum slab) replaced with Space Grotesk (UI-010) — geometric
// and technical, matching the palette's precision-instrument direction rather than
// fighting it. Figtree stays for body copy; it was never the mismatch.
val ProtoHeadingFont =
    FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )
val ProtoBodyFont =
    FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
        Font(R.font.figtree_extrabold, FontWeight.ExtraBold),
    )

/** Numeric/technical readouts only — recording timer, meter labels, cap countdown. */
val ProtoMonoFont =
    FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )

val ProtoEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
