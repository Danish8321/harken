package com.harken.android.speech

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.harken.android.audio.SpeechSpans
import com.harken.android.audio.WavFormat
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

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

            val readStartNs = System.nanoTime()
            val pcm16 = readWavPcm16(wavPath)
            val readMs = Telemetry.elapsedMsSince(readStartNs)

            // Silence is decoded once and only where it borders speech. Handing a whole
            // recording to whisper made it pay full price for the quiet parts and invent
            // words to fill them — see SpeechSpans.
            val spanStartNs = System.nanoTime()
            val spans = SpeechSpans.find(pcm16)
            val spanMs = Telemetry.elapsedMsSince(spanStartNs)

            val audioSeconds = pcm16.size / WavFormat.SampleRate
            val decodedSamples = spans.sumOf { it.sampleCount.toLong() }
            Telemetry.event(
                "transcribe_prepared",
                "audioSeconds" to audioSeconds,
                "decodedSeconds" to decodedSamples / WavFormat.SampleRate,
                "spans" to spans.size,
                "modelLoadMs" to loadMs,
                "modelCached" to alreadyLoaded,
                "wavReadMs" to readMs,
                "spanFindMs" to spanMs,
            )

            var decodeMs = 0L
            val segments = spans.flatMapIndexed { index, span ->
                val decodeStartNs = System.nanoTime()
                val json = nativeTranscribe(
                    handle,
                    pcm16.copyOfRange(span.startSample, span.endSampleExclusive),
                    WavFormat.SampleRate,
                )
                val spanDecodeMs = Telemetry.elapsedMsSince(decodeStartNs)
                decodeMs += spanDecodeMs

                val nativeSegments = gson.fromJson(json, Array<NativeSegment>::class.java) ?: emptyArray()
                val spanOffsetMs = span.startSample * 1000L / WavFormat.SampleRate

                // Per span, not just per recording: one pathological span in an otherwise
                // fast transcription is exactly the case a total would average away.
                Telemetry.event(
                    "span_decoded",
                    "index" to index,
                    "startSecond" to span.startSample / WavFormat.SampleRate,
                    "spanSeconds" to span.sampleCount / WavFormat.SampleRate,
                    "decodeMs" to spanDecodeMs,
                    "segments" to nativeSegments.size,
                )

                nativeSegments.map { segment ->
                    // Whisper times each segment from the start of what it was given, so
                    // offsets are relative to the span, not to the recording.
                    LocalTranscribedSegment(
                        offsetSeconds = ((spanOffsetMs + segment.offsetMs) / 1000L).toInt(),
                        text = segment.text,
                    )
                }
            }

            Telemetry.event(
                "transcribe_decoded",
                "audioSeconds" to audioSeconds,
                "decodeMs" to decodeMs,
                // Faster than real time is < 1.0. The number that decides whether a
                // three-hour recording is usable on this phone at all.
                "realtimeFactor" to realtimeFactor(decodeMs, audioSeconds),
                "segments" to segments.size,
            )

            segments
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
     * Reads the PCM payload of a WAV file written by [com.harken.android.audio.WavWriter]
     * (fixed 44-byte canonical header, 16-bit little-endian samples) into a ShortArray
     * suitable for [nativeTranscribe]. No general-purpose WAV parsing is needed since this
     * app only ever produces WavWriter's exact format.
     */
    private fun readWavPcm16(path: String): ShortArray {
        RandomAccessFile(path, "r").use { file ->
            val dataLength = (file.length() - WavFormat.HeaderLength).coerceAtLeast(0)
            val sampleCount = (dataLength / 2).toInt()
            val samples = ShortArray(sampleCount)

            file.seek(WavFormat.HeaderLength.toLong())
            val bytes = ByteArray(2)
            for (i in 0 until sampleCount) {
                file.readFully(bytes)
                samples[i] = ((bytes[0].toInt() and 0xFF) or (bytes[1].toInt() shl 8)).toShort()
            }
            return samples
        }
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
