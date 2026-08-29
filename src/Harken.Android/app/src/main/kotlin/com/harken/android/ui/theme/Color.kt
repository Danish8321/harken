package com.harken.android.ui.theme

import androidx.compose.ui.graphics.Color

// The Organic accent/neutral ramps this object used to hold are gone (UI-002): the app
// ran two independent color systems side by side — Proto screens on ProtoColors.kt,
// Material components on a MaterialTheme.colorScheme built from this ramp. Material now
// derives its scheme from ProtoColors too (see Theme.kt's protoColorScheme), so only the
// ink anchor survives here — it isn't a Material role, and staying a fixed dark/light
// pair regardless of app theme is deliberate (see LocalInk's doc comment).
object Organic {
    private val Background = Color(0xFFF5EAD8)
    private val Neutral900 = Color(0xFF2E2B25)

    val InkLight = Neutral900
    val InkDark = Color(0xFF100E0C)
    val OnInk = Background
}
