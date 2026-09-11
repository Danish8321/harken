package com.harken.android.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Collapses an interleaved multi-channel buffer to mono by averaging the channels.
 *
 * Averaging rather than taking the left channel: a stereo recording of a meeting often has
 * one voice louder on each side, and dropping a channel drops a speaker.
 */
object Downmix {
    /** How many mono samples [toMono] will write for [length] interleaved samples. */
    fun monoLength(
        length: Int,
        channels: Int,
    ): Int {
        require(channels >= 1) { "channels must be positive" }
        return length / channels
    }

    /**
     * Writes the mono form of the first [length] samples of [interleaved] into [out],
     * returning how many samples were written. Caller-supplied output, so a decode loop
     * reuses one buffer per file rather than allocating one per codec buffer (ARC-013).
     */
    fun toMono(
        interleaved: ShortArray,
        length: Int,
        channels: Int,
        out: ShortArray,
    ): Int {
        val frames = monoLength(length, channels)
        require(out.size >= frames) { "out holds ${out.size}, needs $frames" }
        if (channels == 1) {
            interleaved.copyInto(out, 0, 0, frames)
            return frames
        }
        var read = 0
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                sum += interleaved[read]
                read++
            }
            out[frame] = (sum / channels).toShort()
        }
        return frames
    }
}

/**
 * Converts a mono stream at any sample rate to the 16 kHz [WavFormat] speaks.
 *
 * **Why not linear interpolation.** There is already a linear resampler in the JNI layer
 * (`ToWhisperPcm`), and it would be a dozen lines here. It is the wrong tool for coming
 * *down* from 44.1 or 48 kHz, which is what every import does: with no filter ahead of the
 * decimation, everything above 8 kHz folds back into the audible band as noise sitting
 * directly on top of the speech. A 12 kHz hiss arrives as a 4 kHz tone, in the middle of
 * where consonants live. Nothing about the result looks wrong — the transcript is just
 * quietly worse, and the blame lands on Whisper. So the band is cleared before the rate
 * changes (ADR-0016).
 *
 * **Stateful, not a pure function.** A decoder hands over one buffer at a time, and a
 * filter this wide needs the samples either side of a buffer boundary to compute the
 * output that straddles it. Resampling each buffer independently would put a discontinuity
 * every few milliseconds — a click at every seam. So history crosses the boundary, and the
 * caller must [drain] at the end of the file to get the tail.
 *
 * Cost is per *output* sample, not per input sample, which is what makes a windowed-sinc
 * affordable here: [TAPS] multiply-accumulates 16000 times a second of audio, regardless
 * of how high the source rate was.
 */
class Resampler16k(
    private val sourceRate: Int,
) {
    init {
        require(sourceRate > 0) { "sourceRate must be positive" }
    }

    /** A source already at 16 kHz is passed through untouched: filtering it would only lose. */
    private val passthrough = sourceRate == WavFormat.SAMPLE_RATE

    private val kernel = if (passthrough) emptyArray() else buildKernel(sourceRate)

    // Source samples still needed by an output yet to be produced, as floats so the tap
    // sum does not re-convert on every access. history[0] is absolute source index
    // historyStart, which begins negative: the first outputs need taps from before the
    // file starts, and those are zero.
    private var history = FloatArray(INITIAL_HISTORY)
    private var historyLength = HALF_TAPS
    private var historyStart = -HALF_TAPS.toLong()
    private var nextOutput = 0L

    /** An upper bound on the outputs [process] can return for [length] input samples. */
    fun maxOutputFor(length: Int): Int = if (passthrough) length else (length.toLong() * WavFormat.SAMPLE_RATE / sourceRate).toInt() + 2

    /** An upper bound on the outputs [drain] can return. */
    fun maxDrainOutput(): Int = if (passthrough) 0 else (HALF_TAPS.toLong() * WavFormat.SAMPLE_RATE / sourceRate).toInt() + 2

    /**
     * Consumes [length] samples of [input] and writes however many 16 kHz samples are now
     * fully determined into [out], returning that count. Sizing [out] with [maxOutputFor]
     * is the caller's job.
     */
    fun process(
        input: ShortArray,
        length: Int,
        out: ShortArray,
    ): Int {
        require(length <= input.size) { "length $length exceeds input ${input.size}" }
        if (passthrough) {
            require(out.size >= length) { "out holds ${out.size}, needs $length" }
            input.copyInto(out, 0, 0, length)
            return length
        }
        append(input, length)
        val produced = produce(out)
        compact()
        return produced
    }

    /**
     * Flushes the tail: pads the history with the silence that follows the last sample so
     * the final outputs — the ones whose window runs off the end of the file — can be
     * computed. Call once, after the last [process].
     */
    fun drain(out: ShortArray): Int {
        if (passthrough) return 0
        append(ShortArray(HALF_TAPS), HALF_TAPS)
        return produce(out)
    }

    private fun append(
        input: ShortArray,
        length: Int,
    ) {
        if (historyLength + length > history.size) {
            history = history.copyOf(maxOf(historyLength + length, history.size * 2))
        }
        for (i in 0 until length) {
            history[historyLength + i] = input[i].toFloat()
        }
        historyLength += length
    }

    private fun produce(out: ShortArray): Int {
        var written = 0
        val lastAvailable = historyStart + historyLength - 1
        while (written < out.size) {
            val position = nextOutput * sourceRate.toDouble() / WavFormat.SAMPLE_RATE
            val centre = floor(position).toLong()
            if (centre + HALF_TAPS > lastAvailable) break

            val phase = ((position - centre) * PHASES).roundToInt().coerceIn(0, PHASES)
            val row = kernel[phase]
            val base = (centre - HALF_TAPS + 1 - historyStart).toInt()

            var sum = 0f
            for (tap in 0 until TAPS) {
                sum += history[base + tap] * row[tap]
            }
            out[written] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            written++
            nextOutput++
        }
        return written
    }

    /** Forgets the samples no future output can reach. Without it, history is the whole file. */
    private fun compact() {
        val nextCentre = floor(nextOutput * sourceRate.toDouble() / WavFormat.SAMPLE_RATE).toLong()
        val lowestNeeded = nextCentre - HALF_TAPS + 1
        val drop = (lowestNeeded - historyStart).toInt()
        if (drop <= 0) return
        history.copyInto(history, 0, drop, historyLength)
        historyLength -= drop
        historyStart += drop
    }

    companion object {
        /**
         * Half the filter width, in source samples.
         *
         * Sets how sharply the band can be cleared: with a Blackman window the transition
         * spans roughly 5.5/[TAPS] of the source rate, which at 44.1 kHz is ~2.5 kHz. Put
         * around [CUTOFF_HZ] that leaves everything the source has above ~8.3 kHz — which
         * is what would fold — attenuated by the window's stopband, and everything below
         * ~5.7 kHz untouched. Doubling the width would buy a sharper corner at twice the
         * arithmetic; halving it starts eating the fricatives it is here to protect.
         */
        private const val HALF_TAPS = 48
        private const val TAPS = HALF_TAPS * 2

        /**
         * Where the filter turns over, in Hz.
         *
         * Just under the 8 kHz Nyquist of the 16 kHz target, so the transition straddles
         * 8 kHz rather than starting there — a filter that only begins to fall at Nyquist
         * does not stop aliasing, it just makes it quieter.
         */
        private const val CUTOFF_HZ = 7000.0

        /**
         * How finely the sub-sample position is quantised.
         *
         * Outputs almost never land on a source sample, so each needs the kernel evaluated
         * at its own fractional offset. Computing a sinc per output would dominate the
         * import; 512 precomputed phases put the quantisation error near -60 dB, below the
         * noise of any real recording, for ~200 KB of table built once per file.
         */
        private const val PHASES = 512

        private const val INITIAL_HISTORY = 8192

        /**
         * A windowed sinc per phase, each normalised to unity gain at DC so a constant
         * signal comes through at its own level rather than the window's.
         */
        private fun buildKernel(sourceRate: Int): Array<FloatArray> {
            // Below 16 kHz there is nothing to fold, and the filter's only job is to
            // interpolate; cutting at the source's own Nyquist keeps everything it holds.
            val cutoff = minOf(CUTOFF_HZ, sourceRate * 0.45)
            return Array(PHASES + 1) { phase ->
                val fraction = phase.toDouble() / PHASES
                val row = DoubleArray(TAPS)
                var sum = 0.0
                for (tap in 0 until TAPS) {
                    val offset = (tap - HALF_TAPS + 1) - fraction
                    val value = sinc(2.0 * cutoff / sourceRate * offset) * blackman(offset / HALF_TAPS)
                    row[tap] = value
                    sum += value
                }
                FloatArray(TAPS) { (row[it] / sum).toFloat() }
            }
        }

        private fun sinc(x: Double): Double = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

        private fun blackman(u: Double): Double = if (abs(u) > 1.0) 0.0 else 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2.0 * PI * u)
    }
}
