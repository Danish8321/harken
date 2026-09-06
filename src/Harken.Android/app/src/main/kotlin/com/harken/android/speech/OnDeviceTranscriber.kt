package com.harken.android.speech

import com.harken.android.audio.SpeechSpans
import com.harken.android.audio.WavFormat
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Bytes pulled from the WAV per read. 64 KiB is 2 seconds of 16 kHz mono 16-bit audio. */
private const val ReadBlockBytes = 64 * 1024

/**
 * A single decoded segment from an on-device whisper.cpp transcription. Deliberately its
 * own type rather than a shape shared with a server: this is a local-only, on-device
 * concept, and the backend's transcription client was deleted outright (ADR-0011).
 */
data class LocalTranscribedSegment(
    val offsetSeconds: Int,
    val text: String,
)

// Wire shape returned by nativeTranscribe's JSON, kept private — callers only see
// LocalTranscribedSegment.
private data class NativeSegment(
    val offsetMs: Long,
    val text: String,
)

/**
 * The JSON [OnDeviceTranscriber.nativeTranscribe] returns:
 * `[{"offsetMs": 0, "text": " Hello."}, ...]`.
 *
 * Read field by field rather than through a reflective mapper. A mapper needs the
 * field names and the concrete class to survive minification, and when they do not
 * the transcription fails on release builds only — which is how it failed the first
 * time R8 was enabled: "Abstract classes can't be instantiated ... Class name: n2.f".
 * Two fields do not justify carrying that risk, or the dependency.
 */
private fun parseNativeSegments(json: String): List<NativeSegment> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val segment = array.getJSONObject(index)
        NativeSegment(
            offsetMs = segment.getLong("offsetMs"),
            text = segment.getString("text"),
        )
    }
}

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
            val handle = modelHandle ?: nativeLoadModel(modelPath).also { loaded ->
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
                val sampleCount = pcmSampleCount(file)

                val scanStartNs = System.nanoTime()
                // Blocking reads belong on IO; the decode below is the part that genuinely
                // uses a core, and it stays on Default (ARC-012).
                val windowRms = withContext(Dispatchers.IO) { scanWindowRms(file, sampleCount) }
                val spans = SpeechSpans.assemble(windowRms, sampleCount)
                val scanMs = Telemetry.elapsedMsSince(scanStartNs)

                val audioSeconds = sampleCount / WavFormat.SampleRate
                val decodedSamples = spans.sumOf { it.sampleCount.toLong() }
                Telemetry.event(
                    "transcribe_prepared",
                    "audioSeconds" to audioSeconds,
                    "decodedSeconds" to decodedSamples / WavFormat.SampleRate,
                    "spans" to spans.size,
                    "modelLoadMs" to loadMs,
                    "modelCached" to alreadyLoaded,
                    "wavScanMs" to scanMs,
                    "peakSpanSeconds" to (spans.maxOfOrNull { it.sampleCount } ?: 0) / WavFormat.SampleRate,
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
                val segments = spans.flatMapIndexed { index, span ->
                    val readStartNs = System.nanoTime()
                    val pcm16 = withContext(Dispatchers.IO) { readSamples(file, span.startSample, span.sampleCount) }
                    readMs += Telemetry.elapsedMsSince(readStartNs)

                    val decodeStartNs = System.nanoTime()
                    // A SIGSEGV in here takes the process with it, so the note has to be on
                    // disk before the call and gone after it.
                    breadcrumb?.enter(
                        spanIndex = index,
                        startSecond = span.startSample / WavFormat.SampleRate,
                        spanSeconds = span.sampleCount / WavFormat.SampleRate,
                    )
                    val json = try {
                        nativeTranscribe(handle, pcm16, WavFormat.SampleRate)
                    } finally {
                        breadcrumb?.leave()
                    }
                    // An aborted whisper_full returns an empty result rather than
                    // throwing, so the cancellation has to be raised here or the loop
                    // would quietly record the rest of the recording as silence.
                    decodeJob.ensureActive()
                    val spanDecodeMs = Telemetry.elapsedMsSince(decodeStartNs)
                    decodeMs += spanDecodeMs

                    val nativeSegments = parseNativeSegments(json)
                    val spanOffsetMs = span.startSample * 1000L / WavFormat.SampleRate

                    // Per span, not just per recording: one pathological span in an
                    // otherwise fast transcription is exactly the case a total would
                    // average away.
                    Telemetry.event(
                        "span_decoded",
                        "index" to index,
                        "startSecond" to span.startSample / WavFormat.SampleRate,
                        "spanSeconds" to span.sampleCount / WavFormat.SampleRate,
                        "decodeMs" to spanDecodeMs,
                        "segments" to nativeSegments.size,
                    )

                    decodedSoFar += span.sampleCount
                    if (decodedSamples > 0) onProgress(decodedSoFar.toFloat() / decodedSamples)

                    nativeSegments.map { segment ->
                        // Whisper times each segment from the start of what it was given,
                        // so offsets are relative to the span, not to the recording.
                        LocalTranscribedSegment(
                            offsetSeconds = ((spanOffsetMs + segment.offsetMs) / 1000L).toInt(),
                            text = segment.text,
                        )
                    }
                }

                val decodedSeconds = (decodedSamples / WavFormat.SampleRate).toInt()
                Telemetry.event(
                    "transcribe_decoded",
                    "audioSeconds" to audioSeconds,
                    "decodedSeconds" to decodedSeconds,
                    "decodeMs" to decodeMs,
                    "wavReadMs" to readMs,
                    // Against the whole recording: what the user waits, per second of what
                    // they recorded. Faster than real time is < 1.0, and this is the number
                    // that decides whether a three-hour capture is usable on this phone.
                    "realtimeFactor" to realtimeFactor(decodeMs, audioSeconds),
                    // Against only what was handed to whisper. The two diverge exactly as
                    // much as SpeechSpans skipped, so reporting one without the other makes
                    // a recording full of silence look like a fast decoder.
                    "decodedRealtimeFactor" to realtimeFactor(decodeMs, decodedSeconds),
                    "segments" to segments.size,
                )

                segments
            }
        }

    /**
     * Decode time as a multiple of the audio's own length, to two decimals. Below 1.0 the
     * phone decodes faster than the recording plays, which is what makes a long capture
     * bearable; at 3.0 a one-hour meeting costs three hours.
     */
    private fun realtimeFactor(decodeMs: Long, audioSeconds: Int): String =
        // Locale.ROOT: this goes into a key=value telemetry line, and a device set to a
        // decimal-comma locale would emit realtimeFactor=1,84 and break every reader of
        // those logs (ARC-029). Locale.getDefault() is for text a person reads.
        if (audioSeconds <= 0) "0.00" else String.format(Locale.ROOT, "%.2f", decodeMs / (audioSeconds * 1000.0))

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

    /**
     * Samples in the PCM payload of a WAV written by [com.harken.android.audio.WavWriter]
     * (fixed 44-byte canonical header, 16-bit little-endian). No general-purpose WAV
     * parsing is needed since this app only ever produces WavWriter's exact format.
     */
    private fun pcmSampleCount(file: RandomAccessFile): Int =
        ((file.length() - WavFormat.HeaderLength).coerceAtLeast(0) / 2).toInt()

    /**
     * One RMS reading per [SpeechSpans.WindowSeconds] of the file, read a window at a time
     * so a three-hour recording costs one window of memory rather than all of it. Levels
     * rather than verdicts, because what counts as silence depends on the whole recording
     * — see [SpeechSpans.amplitudeThreshold].
     */
    private fun scanWindowRms(file: RandomAccessFile, sampleCount: Int): IntArray {
        val windowSamples = WavFormat.SampleRate * SpeechSpans.WindowSeconds
        val windowCount = (sampleCount + windowSamples - 1) / windowSamples
        val window = ShortArray(windowSamples)
        val block = ByteArray(windowSamples * 2)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)

        file.seek(WavFormat.HeaderLength.toLong())
        return IntArray(windowCount) { index ->
            val wanted = minOf(windowSamples, sampleCount - index * windowSamples)
            file.readFully(block, 0, wanted * 2)
            buffer.clear()
            buffer.asShortBuffer().get(window, 0, wanted)
            SpeechSpans.windowRms(window, 0, wanted)
        }
    }

    /** [count] samples of the PCM payload starting at sample [startSample]. */
    private fun readSamples(file: RandomAccessFile, startSample: Int, count: Int): ShortArray {
        val samples = ShortArray(count)
        file.seek(WavFormat.HeaderLength.toLong() + startSample.toLong() * 2)

        // Read a block at a time and let ByteBuffer do the little-endian conversion. This
        // used to call readFully into a two-byte array once per sample: five minutes of
        // audio is 4.8 million of those calls, which measured 11.4 seconds on a Nothing
        // Phone 2 — 95% of the total time to "transcribe" a silent recording.
        val block = ByteArray(ReadBlockBytes)
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

    companion object {
        init {
            System.loadLibrary("harken_whisper_jni")
        }

        @JvmStatic
        external fun nativeLoadModel(path: String): Long

        @JvmStatic
        external fun nativeTranscribe(handle: Long, pcm16: ShortArray, sampleRate: Int): String

        /** Asks an in-flight [nativeTranscribe] to give up, and clears that request. */
        @JvmStatic
        external fun nativeSetAbort(abort: Boolean)

        @JvmStatic
        external fun nativeFreeModel(handle: Long)
    }
}
