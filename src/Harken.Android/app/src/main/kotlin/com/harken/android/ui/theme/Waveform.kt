package com.harken.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The waveform motif, in one place.
 *
 * A crest that marches left-to-right, mirrored around the row's vertical centre — an
 * oscilloscope trace, not a bar chart growing from a floor. It appears on the splash and
 * in the idle meter, and the two are meant to read as the same object in two positions.
 *
 * They were the same object by copy-paste until UI-029: the constants below were written
 * out twice, and "match the splash waveform" was a manual step someone had to remember
 * on every tweak. Four rounds of tuning went through both files by hand. They are one
 * declaration now, so the two cannot drift.
 *
 * The live meter is deliberately NOT here — it is driven by real input amplitude, not by
 * this phase clock, and only looks similar.
 */
object HarkenWaveform {
    /**
     * Bar count sets the visual density, because bars are laid out with `SpaceBetween`
     * across the full width — at 18 they read as scattered ticks rather than a trace.
     */
    const val BarCount = 34

    val BarWidth = 3.5.dp
    val BarShape = RoundedCornerShape(2.dp)

    /** Height at the trough — the bar never fully collapses, so the trace stays continuous. */
    private val BarMinHeight = 4.dp

    /** Added to [BarMinHeight] at the crest. */
    private val BarTravelHeight = 32.dp

    /**
     * Height for a bar driven by a real 0..1 amplitude reading rather than the phase
     * clock — the live meter's crest, same trough/crest range as the idle/splash trace so
     * the three read as one instrument rather than two different bar styles.
     */
    fun amplitudeHeight(value: Float): Dp = BarMinHeight + BarTravelHeight * value.coerceIn(0f, 1f)

    /** How fast the crest travels. Paired with [PhaseStep]; changing one alone re-tunes the look. */
    private const val TravelSpeed = 1.6154f

    /**
     * Phase offset per bar — this is what makes the crest travel instead of every bar
     * breathing in place. It scales inversely with [BarCount]: raising the density
     * without dropping this bunches the wave up into a much shorter wavelength.
     */
    private const val PhaseStep = 0.4f

    /** 0..1 position of bar [i] in its cycle at phase [t] (radians). */
    fun travel(t: Float, i: Int): Float = sin(t * TravelSpeed - i * PhaseStep) * 0.5f + 0.5f

    /**
     * Height of bar [i] at phase [t].
     *
     * @param moving false holds every bar at the trough — the reduced-motion rendering,
     * which keeps the shape on screen but stops it animating.
     */
    fun barHeight(t: Float, i: Int, moving: Boolean = true): Dp =
        if (!moving) BarMinHeight else BarMinHeight + BarTravelHeight * travel(t, i)
}
