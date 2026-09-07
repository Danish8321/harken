package com.harken.android.audio

/**
 * The level of a chunk of 16-bit little-endian PCM — the one place that number is derived.
 *
 * Three passes over every chunk used to compute it: the amplitude meter, the noise floor,
 * and the silence verdict, in two files, three ways. At the 160 ms chunks
 * [AudioRecordCapture] delivers that is ~48,000 redundant sample reads a second, on the
 * one path in the app that must never stall (ARC-010). The level is now read once where
 * the chunk arrives and passed down.
 */
object Pcm16 {
    /**
     * The level of the chunk, not its loudest sample.
     *
     * Reading the peak let one click speak for a whole chunk: measured on a Nothing Phone
     * 2, chunk peak cleared 500 in 86% of a 7m25s capture of an ordinary room, holding the
     * longest quiet run to 1.4s against the 300s the auto-stop needs.
     *
     * The mean square is taken in Long — the loudest possible chunk sums 32768² per sample
     * and stays inside it — and rooted once per chunk. The root is what makes the value
     * comparable to a floor read off other chunks.
     */
    fun rms(
        pcm: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var sumOfSquares = 0L
        var samples = 0L
        var i = offset
        val end = offset + length
        // Whole 16-bit little-endian samples only. A trailing odd byte cannot be read as a
        // sample and is ignored rather than misread as a loud one.
        while (i + 1 < end) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toLong()
            sumOfSquares += sample * sample
            samples++
            i += 2
        }
        if (samples == 0L) return 0
        return kotlin.math.sqrt(sumOfSquares.toDouble() / samples).toInt()
    }

    /** The same level as a fraction of full scale, which is what the meter draws. */
    fun normalized(rms: Int): Float = (rms / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
}
