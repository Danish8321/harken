package com.harken.android.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bytes pulled from the WAV per read. 64 KiB is 2 seconds of 16 kHz mono 16-bit audio. */
private const val READ_BLOCK_BYTES = 64 * 1024

/**
 * Reads the PCM payload of a WAV written by [WavWriter] — fixed 44-byte canonical header,
 * 16-bit little-endian, [WavFormat.SAMPLE_RATE] mono. No general-purpose WAV parsing: this
 * app only ever produces WavWriter's exact format, and the one reader of these files is the
 * transcriber.
 *
 * A recording is never held whole. [windowRms] walks it a window at a time and [samples]
 * reads back only the stretches [SpeechSpans] judged worth decoding, so peak memory is one
 * span rather than the file — 345 MB at the app's own three-hour cap, which is an
 * out-of-memory kill long before it is a slow transcription.
 *
 * Every function takes an open [RandomAccessFile] and seeks within it, rather than a path it
 * opens itself: a decode reads the same file once per span, and reopening it per call would
 * trade the seek for an open.
 */
object WavPayload {
    /** Samples in the payload — the file's length less the header, two bytes each. */
    fun sampleCount(file: RandomAccessFile): Int = ((file.length() - WavFormat.HEADER_LENGTH).coerceAtLeast(0) / 2).toInt()

    /**
     * One RMS reading per [SpeechSpans.WINDOW_SECONDS] of the file, read a window at a time.
     * Levels rather than verdicts, because what counts as silence depends on the whole
     * recording — see [SpeechSpans.amplitudeThreshold].
     *
     * Blocking reads: call it off the Main dispatcher.
     */
    fun windowRms(
        file: RandomAccessFile,
        sampleCount: Int,
    ): IntArray {
        val windowSamples = WavFormat.SAMPLE_RATE * SpeechSpans.WINDOW_SECONDS
        val windowCount = (sampleCount + windowSamples - 1) / windowSamples
        val window = ShortArray(windowSamples)
        val block = ByteArray(windowSamples * 2)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)

        file.seek(WavFormat.HEADER_LENGTH.toLong())
        return IntArray(windowCount) { index ->
            val wanted = minOf(windowSamples, sampleCount - index * windowSamples)
            file.readFully(block, 0, wanted * 2)
            buffer.clear()
            buffer.asShortBuffer().get(window, 0, wanted)
            SpeechSpans.windowRms(window, 0, wanted)
        }
    }

    /**
     * [count] samples of the payload starting at sample [startSample].
     *
     * Reads a block at a time and lets [ByteBuffer] do the little-endian conversion. This
     * used to call readFully into a two-byte array once per sample: five minutes of audio is
     * 4.8 million of those calls, which measured 11.4 seconds on a Nothing Phone 2 — 95% of
     * the total time to "transcribe" a silent recording.
     */
    fun samples(
        file: RandomAccessFile,
        startSample: Int,
        count: Int,
    ): ShortArray {
        val samples = ShortArray(count)
        file.seek(WavFormat.HEADER_LENGTH.toLong() + startSample.toLong() * 2)

        val block = ByteArray(READ_BLOCK_BYTES)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var written = 0
        while (written < count) {
            val wanted = minOf(block.size, (count - written) * 2)
            file.readFully(block, 0, wanted)
            buffer.clear()
            buffer.asShortBuffer().get(samples, written, wanted / 2)
            written += wanted / 2
        }
        return samples
    }
}
