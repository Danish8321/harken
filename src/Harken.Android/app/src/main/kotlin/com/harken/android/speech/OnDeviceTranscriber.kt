package com.harken.android.speech

import com.harken.android.audio.SpeechSpans
import com.harken.android.audio.WavFormat
import com.harken.android.audio.WavPayload
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * Seam so TranscriptionCoordinator can be unit-tested with a hand-written fake instead of
 * the real JNI-backed [OnDeviceTranscriber] (whose companion object loads a native library
 * that doesn't exist on the JVM test runner).
 */
interface Transcriber {
    /**
     * [onProgress] reports the fraction of the decodable audio finished, 0f..1f, once per
     * span. It exists because a decode is minutes long and now runs under a foreground
     * service notification the user can see — a progress bar that never moves is worse
     * than none. Called on whatever thread the decode is running on, so an implementation
     * of it must be safe there.
     */
    suspend fun transcribe(
        wavPath: String,
        modelPath: String,
        onProgress: (fraction: Float) -> Unit = {},
    ): List<LocalTranscribedSegment>

    fun release()
}

/**
 * Thin Kotlin wrapper over the JNI bridge in
 * app/src/main/cpp/harken_whisper_jni.cpp. TranscriptionCoordinator releases the native
 * handle after every transcription (single-flight, so no concurrent use is possible);
 * native calls are blocking CPU work, so they're dispatched off Main.
 */
class OnDeviceTranscriber(
    /**
     * Optional so the JVM tests and the fake in TranscriptionCoordinatorTest need no file
     * system; on the device it is always supplied.
     */
    private val breadcrumb: NativeDecodeBreadcrumb? = null,
) : Transcriber {
    private var modelHandle: Long? = null

    /**
     * Transcribes a 16kHz mono 16-bit PCM WAV file (the shape WavWriter always produces)
     * at [modelPath]. Loads the model if it isn't already loaded — the caller is expected
     * to call [release] when done, per instance, per transcription.
     */
    override suspend fun transcribe(
        wavPath: String,
        modelPath: String,
        onProgress: (fraction: Float) -> Unit,
    ): List<LocalTranscribedSegment> =
        withContext(Dispatchers.Default) {
            // whisper_full does not return until a whole span is decoded — up to 300
            // seconds of audio — so cancelling the coroutine alone leaves the user
            // watching a Cancel they already tapped. The native flag is what stops the
            // compute; the ensureActive below turns that into a CancellationException.
            // The handler needs no disposal: it is registered on this call's own job,
            // which completes a few lines later.
            nativeSetAbort(false)
            val decodeJob = currentCoroutineContext().job
            decodeJob.invokeOnCompletion { nativeSetAbort(true) }

            // Every phase below is timed separately because they fail differently: a slow
            // model load is a storage problem, a slow WAV read is an I/O problem, and slow
            // decoding is whisper doing too much work. Reported as one number they are
            // indistinguishable, which is how the silence defect stayed invisible.
            val loadStartNs = System.nanoTime()
            val alreadyLoaded = modelHandle != null
            val handle =
                modelHandle ?: nativeLoadModel(modelPath).also { loaded ->
                    if (loaded == 0L) {
                        error("Failed to load whisper model at $modelPath")
                    }
                    modelHandle = loaded
                }
            val loadMs = Telemetry.elapsedMsSince(loadStartNs)

            // The recording is never held whole. Scanning it for speech and then reading
            // back only the spans worth decoding keeps peak memory at one span, where
            // materialising the file cost two bytes per sample for its entire length — 345
            // MB at the app's own three-hour cap, on top of the model, which is an
            // out-of-memory kill long before it is a slow transcription.
            RandomAccessFile(wavPath, "r").use { file ->
                val sampleCount = WavPayload.sampleCount(file)

                val scanStartNs = System.nanoTime()
                // Blocking reads belong on IO; the decode below is the part that genuinely
                // uses a core, and it stays on Default (ARC-012).
                val windowRms = withContext(Dispatchers.IO) { WavPayload.windowRms(file, sampleCount) }
                val spans = SpeechSpans.assemble(windowRms, sampleCount)
                val scanMs = Telemetry.elapsedMsSince(scanStartNs)

                val audioSeconds = sampleCount / WavFormat.SAMPLE_RATE
                val decodedSamples = spans.sumOf { it.sampleCount.toLong() }
                Telemetry.event(
                    "transcribe_prepared",
                    "audioSeconds" to audioSeconds,
                    "decodedSeconds" to decodedSamples / WavFormat.SAMPLE_RATE,
                    "spans" to spans.size,
                    "modelLoadMs" to loadMs,
                    "modelCached" to alreadyLoaded,
                    "wavScanMs" to scanMs,
                    "peakSpanSeconds" to (spans.maxOfOrNull { it.sampleCount } ?: 0) / WavFormat.SAMPLE_RATE,
                    // The level this recording had to clear to count as speech. Without it
                    // a report of "it transcribed a tenth of my meeting" is unanswerable:
                    // the threshold is read off the recording, so it differs per recording.
                    "silenceThreshold" to SpeechSpans.amplitudeThreshold(windowRms),
                )

                var decodeMs = 0L
                var readMs = 0L
                // Progress is measured in samples handed to whisper, not in spans: spans
                // range from 30 to 300 seconds, so counting them would make the bar jump
                // ten times further for one span than the next.
                var decodedSoFar = 0L
                val segments =
                    spans.flatMapIndexed { index, span ->
                        val readStartNs = System.nanoTime()
                        val pcm16 = withContext(Dispatchers.IO) { WavPayload.samples(file, span.startSample, span.sampleCount) }
                        readMs += Telemetry.elapsedMsSince(readStartNs)

                        val decodeStartNs = System.nanoTime()
                        // A SIGSEGV in here takes the process with it, so the note has to be on
                        // disk before the call and gone after it.
                        breadcrumb?.enter(
                            spanIndex = index,
                            startSecond = span.startSample / WavFormat.SAMPLE_RATE,
                            spanSeconds = span.sampleCount / WavFormat.SAMPLE_RATE,
                        )
                        val json =
                            try {
                                nativeTranscribe(handle, pcm16, WavFormat.SAMPLE_RATE)
                            } finally {
                                breadcrumb?.leave()
                            }
                        // An aborted whisper_full returns an empty result rather than
                        // throwing, so the cancellation has to be raised here or the loop
                        // would quietly record the rest of the recording as silence. Still
                        // true after ARC-058: a *failed* decode now throws, but a cancelled
                        // one deliberately does not — it is not a decoder fault and the
                        // coordinator reports the two differently.
                        decodeJob.ensureActive()
                        val spanDecodeMs = Telemetry.elapsedMsSince(decodeStartNs)
                        decodeMs += spanDecodeMs

                        val nativeSegments = parseNativeSegments(json)

                        // Per span, not just per recording: one pathological span in an
                        // otherwise fast transcription is exactly the case a total would
                        // average away.
                        Telemetry.event(
                            "span_decoded",
                            "index" to index,
                            "startSecond" to span.startSample / WavFormat.SAMPLE_RATE,
                            "spanSeconds" to span.sampleCount / WavFormat.SAMPLE_RATE,
                            "decodeMs" to spanDecodeMs,
                            "segments" to nativeSegments.size,
                        )

                        decodedSoFar += span.sampleCount
                        if (decodedSamples > 0) onProgress(decodedSoFar.toFloat() / decodedSamples)

                        nativeSegments.atSpan(span.startSample)
                    }

                val decodedSeconds = (decodedSamples / WavFormat.SAMPLE_RATE).toInt()
                Telemetry.event(
                    "transcribe_decoded",
                    "audioSeconds" to audioSeconds,
                    "decodedSeconds" to decodedSeconds,
                    "decodeMs" to decodeMs,
                    "wavReadMs" to readMs,
                    // Against the whole recording: what the user waits, per second of what
                    // they recorded. Faster than real time is < 1.0, and this is the number
                    // that decides whether a three-hour capture is usable on this phone.
                    "realtimeFactor" to Telemetry.realtimeFactor(decodeMs, audioSeconds),
                    // Against only what was handed to whisper. The two diverge exactly as
                    // much as SpeechSpans skipped, so reporting one without the other makes
                    // a recording full of silence look like a fast decoder.
                    "decodedRealtimeFactor" to Telemetry.realtimeFactor(decodeMs, decodedSeconds),
                    "segments" to segments.size,
                )

                segments
            }
        }

    /** Releases the native model handle. Safe to call even if a model was never loaded. */
    override fun release() {
        // Cleared before the free, not after. If nativeFreeModel throws, the field would
        // otherwise still point at memory that may or may not have been released, and the
        // next transcribe would reuse it — a double free or a use-after-free in the 480 MB
        // allocation this app is sized around (ARC-031). A handle that leaks is a bounded
        // loss; a handle that is freed twice is a tombstone.
        val handle = modelHandle ?: return
        modelHandle = null
        nativeFreeModel(handle)
    }

    companion object {
        init {
            System.loadLibrary("harken_whisper_jni")
        }

        @JvmStatic
        external fun nativeLoadModel(path: String): Long

        /**
         * Decodes one span. Returns the segment JSON, or throws `IllegalStateException` if
         * the decode failed — an empty array means whisper heard nothing in this span and
         * never that something went wrong. It used to mean both, which is how a failed
         * `whisper_full` reached the user as a transcript with a silent hole in it and a
         * session marked Succeeded (ARC-058).
         */
        @JvmStatic
        external fun nativeTranscribe(
            handle: Long,
            pcm16: ShortArray,
            sampleRate: Int,
        ): String

        /** Asks an in-flight [nativeTranscribe] to give up, and clears that request. */
        @JvmStatic
        external fun nativeSetAbort(abort: Boolean)

        @JvmStatic
        external fun nativeFreeModel(handle: Long)
    }
}
