package com.harken.android.audio

/**
 * Why a capture stopped. The audio layer knows the cause; it does not know the reader's
 * language, so it names the cause and lets the screen write the sentence.
 */
enum class CaptureFailure {
    /** The microphone could not be opened — most often another app holds it. */
    MicrophoneUnavailable,

    /** The read loop got an AudioRecord error code mid-capture (ERROR_DEAD_OBJECT etc). */
    MicrophoneStopped,
}
