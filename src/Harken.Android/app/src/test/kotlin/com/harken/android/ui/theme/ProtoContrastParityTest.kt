package com.harken.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast parity between the two Proto palettes (UI-042 item 7).
 *
 * The bug class this exists for: a component is eyeballed in one theme and is silently
 * illegible in the other, because the two palettes are two independent lists of hex values
 * and nothing compares them. Every pair below is a foreground actually painted on that
 * background somewhere in the app, checked in BOTH palettes at once.
 *
 * Ratios are WCAG 2.1: 4.5:1 for normal text, 3:1 for large text and UI components. Every
 * text pair clears 4.5 since UI-044 closed. One pair is asserted below it — `accent` on
 * `card`, at its measured 4.45 — and that one is a fill, not text, so its real bar is 3:1
 * and the floor is there only to stop a re-palette dropping it further. A floor here is a ratchet, not a pass: it fails the build if a re-palette
 * makes one of them worse, and none of them may be raised without the ticket being closed.
 */
class ProtoContrastParityTest {
    private val aa = 4.5

    /** WCAG relative luminance. Compose's Color is a value class, so this needs no device. */
    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(
        fg: Color,
        bg: Color,
    ): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** A translucent colour drawn over an opaque one — what the eye actually gets. */
    private fun composite(
        over: Color,
        under: Color,
    ): Color =
        Color(
            red = over.red * over.alpha + under.red * (1f - over.alpha),
            green = over.green * over.alpha + under.green * (1f - over.alpha),
            blue = over.blue * over.alpha + under.blue * (1f - over.alpha),
        )

    /** Asserts one foreground/background role in both palettes, so neither can drift alone. */
    private fun bothThemes(
        role: String,
        minimum: Double,
        pick: (ProtoColors) -> Pair<Color, Color>,
    ) {
        for ((theme, palette) in listOf("dark" to ProtoDarkColors, "light" to ProtoLightColors)) {
            val (fg, bg) = pick(palette)
            val ratio = contrast(fg, bg)
            assertTrue(
                "$role in $theme is %.2f:1, below the %.1f:1 this role needs".format(ratio, minimum),
                ratio >= minimum,
            )
        }
    }

    @Test
    fun `body text is legible on every surface it is painted on`() {
        bothThemes("text on screenBg", aa) { it.text to it.screenBg }
        bothThemes("text on card", aa) { it.text to it.card }
        bothThemes("text on navBg", aa) { it.text to it.navBg }
        bothThemes("text on meterBg", aa) { it.text to it.meterBg }
    }

    @Test
    fun `secondary text is legible on every surface it is painted on`() {
        bothThemes("textSecondary on screenBg", aa) { it.textSecondary to it.screenBg }
        bothThemes("textSecondary on meterBg", aa) { it.textSecondary to it.meterBg }
        // Was the pair UI-044 option 2 is about: 4.18:1 in dark while every card meta line
        // and every nav label sat on it. #B4BAC1 clears AA on both surfaces.
        bothThemes("textSecondary on card", aa) { it.textSecondary to it.card }
        bothThemes("textSecondary on navBg", aa) { it.textSecondary to it.navBg }
        // Needed pillTrack to move, not the ink: darkening it to #414851 took this from
        // 4.37:1 to 4.73:1 in dark (UI-044 option 3). Four roles ride this pair — search
        // field, Transcribed chip, inactive segmented label, unchecked switch — and all
        // four gained a cardBorder edge to pay for the fainter fill.
        bothThemes("textSecondary on pillTrack", aa) { it.textSecondary to it.pillTrack }
    }

    @Test
    fun `every status fill carries a foreground that survives on it`() {
        bothThemes("stateDoneFg on stateDone", aa) { it.stateDoneFg to it.stateDone }
        bothThemes("stateDoneFg on stateDoneSoft", aa) { it.stateDoneFg to it.stateDoneSoft }
        bothThemes("stateErrorFg on stateError", aa) { it.stateErrorFg to it.stateError }
        // Material's errorContainer is the fill at 18% over the surface under it, and the
        // scheme's onErrorContainer has to read on the result — not on the fill itself,
        // which is the mistake that left it at 2.03:1 (UI-044).
        bothThemes("onErrorContainer on errorContainer", aa) {
            it.errorInk to composite(it.stateError.copy(alpha = 0.18f), it.card)
        }
        // stateLive is the same value as accent in both palettes today, and its foreground
        // pairs with it the same way — asserted separately so splitting them later is caught.
        // Was 4.12:1 in light, and no foreground fixed it — a pure-white onAccent only
        // reaches 4.49:1. Deepening the light accent to #836E46 took it to 4.50:1 (UI-044).
        bothThemes("onAccent on accent", aa) { it.onAccent to it.accent }
        bothThemes("stateLiveFg on stateLive", aa) { it.stateLiveFg to it.stateLive }
        // accentInk is Material's `primary`, which is a Button's container as well as a
        // TextButton's label, so onPrimary has to survive on it too.
        bothThemes("onAccent on accentInk", aa) { it.onAccent to it.accentInk }
    }

    @Test
    fun `status colours used as ink, not as fill, survive on the surfaces under them`() {
        bothThemes("success on card", aa) { it.success to it.card }
        bothThemes("success on screenBg", aa) { it.success to it.screenBg }
        // Was the worst pair here at 2.69:1 in dark, when the error fill doubled as ink.
        // errorInk is that split (UI-044): every error label and error icon on a neutral
        // surface reads this, and the fill below keeps stateErrorFg.
        bothThemes("errorInk on card", aa) { it.errorInk to it.card }
        bothThemes("errorInk on screenBg", aa) { it.errorInk to it.screenBg }
        // errorInk is also Material's `error` role, so it has to survive its own fill's
        // foreground — a Button(containerColor = error) with onError content.
        bothThemes("stateErrorFg on errorInk", aa) { it.stateErrorFg to it.errorInk }
        // accentInk is the accent split the same way stateError was (UI-044): every
        // TextButton label in the app is rendered in it through Material's `primary`, as is
        // the Library's bold search-match highlight.
        bothThemes("accentInk on card", aa) { it.accentInk to it.card }
        bothThemes("accentInk on screenBg", aa) { it.accentInk to it.screenBg }
        // Kept at its measured 4.45:1 rather than raised or deleted. This is not debt any
        // more: `accent` is a fill here — the record button, the waveform, the nav tab —
        // and 4.45 is well past the 3:1 a UI component needs. The assertion stays as a
        // ratchet so a future re-palette cannot quietly take the fill below that.
        bothThemes("accent on card", 4.4) { it.accent to it.card }
        bothThemes("accent on screenBg", aa) { it.accent to it.screenBg }
    }

    @Test
    fun `the ink surface reads in both themes`() {
        // Ink is one bespoke pair outside the palette, deliberately the same in both themes
        // — but it is painted over two different grounds, so it is checked here with them.
        assertTrue(contrast(Organic.OnInk, Organic.InkLight) >= aa)
        assertTrue(contrast(Organic.OnInk, Organic.InkDark) >= aa)
    }
}
