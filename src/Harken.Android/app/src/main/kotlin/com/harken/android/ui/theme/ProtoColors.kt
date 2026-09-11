package com.harken.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Color system carried over from the Claude Design prototype port (formerly
// ui/prototype/ProtoTheme.kt) — this is now the merged app's visual language for
// Record/Library/Settings/Onboarding content.

// Fonts and easings live in Type.kt — this file is colour only (UI-029).

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
 * The `ink*` ramp is opacities of the same ink color, ordered subtle -> strong. They
 * used to be named after their dark-theme alpha (`ink28`, `ink7`), but the light theme
 * uses different alphas for the same roles, which made the numeric names wrong in
 * light mode. Naming by tier instead of number stays true in both themes.
 */
@Immutable
data class ProtoColors(
    val screenBg: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textSecondary: Color,
    val navBg: Color,
    val pillTrack: Color,
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
    /** Red fill: "failed", with [stateErrorFg] as the only thing legible on it. */
    val stateError: Color,
    val stateErrorFg: Color,
    /**
     * "Failed" as ink rather than as fill — error text and error icons sitting directly on
     * [card] or [screenBg], the counterpart [success] already is for [stateDone].
     *
     * Separate because a fill and the ink over a neutral surface cannot be one value: the
     * red that carries [stateErrorFg] read 2.69:1 as text on a dark card, which is where
     * the app explains why a transcription failed (UI-044).
     */
    val errorInk: Color,
    val meterBg: Color,
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
//     for an icon but not for the label sitting under it — nav uses textSecondary instead
//     (the strip's #A0A6AD then, #B4BAC1 since UI-044), and #828A94 survives as the
//     skeleton/ink tone.
//   - the ink ramp's alphas step up across the board: the same 0.28 that read as a
//     visible hairline on #0E1316 nearly vanishes against a ground this light, which
//     would have quietly erased the idle meter's waveform bars.
private val darkInk = Color(0xFFD1C9BE)
private val lightInk = Color(0xFF10161A)

val ProtoDarkColors =
    ProtoColors(
        screenBg = Color(0xFF2C313A),
        card = Color(0xFF3C414A),
        cardBorder = Color(0xFF464D56),
        text = Color(0xFFD1C9BE),
        // Lifted off the reference strip's #A0A6AD (UI-044 option 2): that value was reasoned
        // about on the ground (5.3:1), but most of the app's secondary text sits on a card,
        // where it read 4.18:1. This is 5.24:1 there, and it costs a step of the ink
        // hierarchy — the gap to `text` narrows from 1.50:1 to 1.19:1, so the two now
        // separate on hue and weight more than on brightness.
        textSecondary = Color(0xFFB4BAC1),
        navBg = Color(0xFF3C414A),
        // A step below cardBorder, which it used to equal (UI-044 option 3). Four roles
        // paint textSecondary on this — search field, the Transcribed chip, the inactive
        // segmented label, the unchecked switch thumb — and at #464D56 all four read
        // 4.37:1. This is 4.73:1. The fill pays for it: the track goes from 1.20:1 to
        // 1.11:1 against card and 1.53:1 to 1.41:1 against the ground, which is too faint
        // to hold a shape on its own. So the shape moved to the edge — every one of those
        // surfaces now draws a cardBorder outline, and cardBorder on card is the same
        // 1.20:1 the fill used to carry.
        pillTrack = Color(0xFF414851),
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
        // Lighter than the fill, the way Material's own dark schemes carry error: 5.16:1
        // on card and 6.57:1 on the ground, against E74C3C's 2.69 and 3.42.
        errorInk = Color(0xFFFF9E93),
        // The one surface that still goes darker than the ground — the meter is a readout
        // behind the signal, and it has to sit under the card tier, not float above it.
        meterBg = Color(0xFF232830),
        inkSubtle = darkInk.copy(alpha = 0.60f),
        inkStrong = darkInk.copy(alpha = 0.75f),
    )

val ProtoLightColors =
    ProtoColors(
        screenBg = Color(0xFFF3F6F7),
        card = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFDDE6E8),
        text = Color(0xFF10161A),
        textSecondary = Color(0xFF5B676C),
        navBg = Color(0xFFFFFFFF),
        pillTrack = Color(0xFFE4EAEC),
        skeleton = Color(0xFFDDE6E8),
        // Deepened from #8A744A (UI-044). That tan was three pairs short at once and no
        // foreground fixed them, because two of the three are the accent reading as text on
        // a surface rather than something reading on the accent: onAccent on accent 4.12:1,
        // accent on card 4.49:1, accent on the ground 4.12:1. A pure-white onAccent was
        // measured and rejected — it only reaches 4.49:1 and leaves the other two. This one
        // value clears all three: 4.50:1, 4.90:1, 4.51:1. The cost is a visibly deeper tan
        // in light mode; dark's #BFA789 is untouched, so the brand still reads as one hue.
        accent = Color(0xFF836E46),
        onAccent = Color(0xFFFDF4E8),
        stateLive = Color(0xFF836E46),
        stateLiveFg = Color(0xFFFDF4E8),
        stateDone = Color(0xFFDCEBDF),
        stateDoneFg = Color(0xFF1F4A2B),
        stateDoneSoft = Color(0xFFC9E0CE),
        success = Color(0xFF2F6B3E),
        stateError = Color(0xFFC7392F),
        stateErrorFg = Color(0xFFFFFFFF),
        // A step deeper than the fill. C7392F already cleared AA as text on white, but not
        // on the error container tint under it (3.95:1); this clears both.
        errorInk = Color(0xFFAE2F24),
        meterBg = Color(0xFFE4EAEC),
        inkSubtle = lightInk.copy(alpha = 0.60f),
        inkStrong = lightInk.copy(alpha = 0.72f),
    )

fun protoColors(light: Boolean): ProtoColors = if (light) ProtoLightColors else ProtoDarkColors

/**
 * The single resolved palette for the current theme, provided once by [HarkenTheme] from
 * the real persisted setting. Screens read this instead of each independently resolving
 * their own AppSettings/DataStore collection — before this, 8 composables did that
 * redundantly, and theme resolution (system-dark fallback, mode mapping) was written three
 * times across MainActivity/Theme.kt/this file.
 */
val LocalProtoColors =
    staticCompositionLocalOf<ProtoColors> {
        error("LocalProtoColors not provided — wrap content in HarkenTheme")
    }
