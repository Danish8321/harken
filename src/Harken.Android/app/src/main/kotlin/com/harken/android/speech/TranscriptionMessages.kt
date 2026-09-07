package com.harken.android.speech

/**
 * The sentences stored on a session when a transcription does not finish.
 *
 * Supplied by the caller rather than written here: [TranscriptionCoordinator] is a plain
 * object with no Context, and the reader's language is not the speech layer's business
 * (ARC-017). Before this existed the coordinator stored `Throwable.message` — the
 * platform's untranslated text, which the Library card renders verbatim, and which for a
 * failed model load was an absolute path inside the app's private storage.
 *
 * [modelUnavailable] takes the classification the download manager already produces, so a
 * transcription that could not get a model says the same thing Settings and onboarding say
 * about the same failure instead of a second wording of it.
 *
 * The defaults are only ever seen by the JVM tests: every caller on the device reads
 * `strings.xml`.
 */
class TranscriptionMessages(
    val cancelled: String = "Transcription cancelled.",
    val failed: String = "On-device transcription failed.",
    val modelUnavailable: (ModelDownloadFailure) -> String = { "The speech model isn't ready." },
)
