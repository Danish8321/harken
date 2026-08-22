package com.harken.android.ui.theme

import androidx.compose.ui.graphics.Color

// Organic ramps, unchanged in value from the previous Color.kt — what changed is that
// each role now has exactly one job (see docs/adr/0010-expressive-redesign.md):
//
//   Ink        audio surfaces only: capture stage, player, floating toolbar
//   Accent     "live" and the single primary action on a screen, at full strength
//   Accent2    "finished and safe": transcribed, summarized, reachable, progress
//   Neutral    everything else: ground, card, inset field, hairline
//
// The old build spent Accent500 at 42%, 24%, 18% and 10% alpha as decoration, which is
// why nothing on screen had a foreground. Tinted fills now come from the ramp's own
// light steps (100-300), never from an alpha of the base.
object Organic {
    val Background = Color(0xFFF5EAD8)
    val Surface = Color(0xFFEBDDC5)
    val TextPrimary = Color(0xFF201E1D)

    val Accent100 = Color(0xFFFFF2EB)
    val Accent200 = Color(0xFFFFE1D0)
    val Accent300 = Color(0xFFFFC6A5)
    val Accent400 = Color(0xFFF6A06B)
    val Accent500 = Color(0xFFC67139)
    val Accent600 = Color(0xFFB2622D)
    val Accent700 = Color(0xFF8C491A)
    val Accent800 = Color(0xFF643312)
    val Accent900 = Color(0xFF402310)

    val Accent2_100 = Color(0xFFF0FAE1)
    val Accent2_200 = Color(0xFFE1EECC)
    val Accent2_300 = Color(0xFFCCDBB2)
    val Accent2_400 = Color(0xFFAEBF92)
    val Accent2_500 = Color(0xFF7A8A5E)
    val Accent2_600 = Color(0xFF728157)
    val Accent2_700 = Color(0xFF566338)
    val Accent2_800 = Color(0xFF3D472B)
    val Accent2_900 = Color(0xFF272E1B)

    val Neutral100 = Color(0xFFF9F4ED)
    val Neutral200 = Color(0xFFEEE7DB)
    val Neutral300 = Color(0xFFDCD3C4)
    val Neutral400 = Color(0xFFC0B6A5)
    val Neutral500 = Color(0xFFA19786)
    val Neutral600 = Color(0xFF82796A)
    val Neutral700 = Color(0xFF645C50)
    val Neutral800 = Color(0xFF474238)
    val Neutral900 = Color(0xFF2E2B25)

    // The ink anchor. Not in the generated ramp as a role, but it is Neutral900 in light
    // and a step darker in dark, so it stays inside the system rather than beside it.
    val InkLight = Neutral900
    val InkDark = Color(0xFF100E0C)
    val OnInk = Background
}
