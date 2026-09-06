package com.harken.android.audio

/**
 * Feeds a chunk of PCM the way the recorder does — level read once, then handed over.
 *
 * [SilenceDetector.add] takes a level rather than bytes (ARC-010), so this is the one
 * place the tests turn synthesised audio into one. Keeping it here rather than asserting
 * on hand-picked levels means the detector's tests still run against real sample data.
 */
internal fun SilenceDetector.add(pcm: ByteArray, offset: Int, length: Int): RecordingStopReason =
    add(Pcm16.rms(pcm, offset, length), length)
