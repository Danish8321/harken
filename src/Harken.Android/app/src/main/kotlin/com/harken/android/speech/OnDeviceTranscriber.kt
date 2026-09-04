package com.harken.android.speech

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.harken.android.audio.SpeechSpans
import com.harken.android.audio.WavFormat
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bytes pulled from the WAV per read. 64 KiB is 2 seconds of 16 kHz mono 16-bit audio. */
private const val ReadBlockBytes = 64 * 1024

/**
 * A single decoded segment from an on-device whisper.cpp transcription. Deliberately not
 * the backend's `TranscribedSegment` shape (see network/HarkenApi.kt) — this is a
 * local-only, on-device concept with no server counterpart (ADR-0011).
 */
data class LocalTranscribedSegment(
    val offsetSeconds: Int,
    val text: String,
)

// Wire shape returned by nativeTranscribe's JSON, kept private — callers only see
// LocalTranscribedSegment.
private data class NativeSegment(
    @SerializedName("offsetMs") val offsetMs: Long,
    @SerializedName("text") val text: String,
)

/**
 * Seam so TranscriptionCoordinator can be unit-tested with a hand-written fake instead of
 * the real JNI-backed [OnDeviceTranscriber] (whose companion object loads a native library
 * that doesn't exist on the JVM test runner).
 */
interface Transcriber {
    suspend fun transcribe(wavPath: String, modelPath: String): List<LocalTranscribedSegment>
    fun release()
}

/**
 * Thin Kotlin wrapper over the JNI bridge in
 * app/src/main/cpp/harken_whisper_jni.cpp. TranscriptionCoordinator releases the native
 * handle after every transcription (single-flight, so no concurrent use is possible);
 * native calls are blocking CPU work, so they're dispatched off Main.
 */
class OnDeviceTranscriber : Transcriber {
    private val gson = Gson()
    private var modelHandle: Long? = null

    /**
     * Transcribes a 16kHz mono 16-bit PCM WAV file (the shape WavWriter always produces)
     * at [modelPath]. Loads the model if it isn't already loaded — the caller is expected
     * to call [release] when done, per instance, per transcription.
     */
    override suspend fun transcribe(wavPath: String, modelPath: String): List<LocalTranscribedSegment> =
        withContext(Dispatchers.Default) {
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
                val spans = SpeechSpans.assemble(scanWindowSilence(file, sampleCount), sampleCount)
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
                )

                var decodeMs = 0L
                var readMs = 0L
                val segments = spans.flatMapIndexed { index, span ->
                    val readStartNs = System.nanoTime()
                    val pcm16 = readSamples(file, span.startSample, span.sampleCount)
                    readMs += Telemetry.elapsedMsSince(readStartNs)

                    val decodeStartNs = System.nanoTime()
                    val json = nativeTranscribe(handle, pcm16, WavFormat.SampleRate)
                    val spanDecodeMs = Telemetry.elapsedMsSince(decodeStartNs)
                    decodeMs += spanDecodeMs

                    val nativeSegments = gson.fromJson(json, Array<NativeSegment>::class.java) ?: emptyArray()
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
        if (audioSeconds <= 0) "0.00" else String.format("%.2f", decodeMs / (audioSeconds * 1000.0))

    /** Releases the native model handle. Safe to call even if a model was never loaded. */
    override fun release() {
        modelHandle?.let { nativeFreeModel(it) }
        modelHandle = null
    }

    /**
     * Samples in the PCM payload of a WAV written by [com.harken.android.audio.WavWriter]
     * (fixed 44-byte canonical header, 16-bit little-endian). No general-purpose WAV
     * parsing is needed since this app only ever produces WavWriter's exact format.
     */
    private fun pcmSampleCount(file: RandomAccessFile): Int =
        ((file.length() - WavFormat.HeaderLength).coerceAtLeast(0) / 2).toInt()

    /**
     * One silence verdict per [SpeechSpans.WindowSeconds] of the file, read a window at a
     * time so a three-hour recording costs one window of memory rather than all of it.
     */
    private fun scanWindowSilence(file: RandomAccessFile, sampleCount: Int): BooleanArray {
        val windowSamples = WavFormat.SampleRate * SpeechSpans.WindowSeconds
        val windowCount = (sampleCount + windowSamples - 1) / windowSamples
        val window = ShortArray(windowSamples)
        val block = ByteArray(windowSamples * 2)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)

        file.seek(WavFormat.HeaderLength.toLong())
        return BooleanArray(windowCount) { index ->
            val wanted = minOf(windowSamples, sampleCount - index * windowSamples)
            file.readFully(block, 0, wanted * 2)
            buffer.clear()
            buffer.asShortBuffer().get(window, 0, wanted)
            SpeechSpans.isWindowSilent(window, 0, wanted)
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

        @JvmStatic
        external fun nativeFreeModel(handle: Long)
    }
}
