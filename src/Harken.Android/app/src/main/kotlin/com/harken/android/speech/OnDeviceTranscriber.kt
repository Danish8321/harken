package com.harken.android.speech

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.harken.android.audio.WavFormat
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
 * Thin Kotlin wrapper over the JNI bridge in
 * app/src/main/cpp/harken_whisper_jni.cpp. Loads the whisper.cpp model once and reuses
 * the native handle for subsequent transcriptions; native calls are blocking CPU work,
 * so they're dispatched off Main.
 */
class OnDeviceTranscriber {
    private val gson = Gson()
    private var modelHandle: Long? = null

    /**
     * Transcribes a 16kHz mono 16-bit PCM WAV file (the shape WavWriter always produces)
     * at [modelPath], loading the model on first use and reusing the handle afterward.
     */
    suspend fun transcribe(wavPath: String, modelPath: String): List<LocalTranscribedSegment> =
        withContext(Dispatchers.Default) {
            val handle = modelHandle ?: nativeLoadModel(modelPath).also { loaded ->
                if (loaded == 0L) {
                    error("Failed to load whisper model at $modelPath")
                }
                modelHandle = loaded
            }

            val pcm16 = readWavPcm16(wavPath)
            val json = nativeTranscribe(handle, pcm16, WavFormat.SampleRate)
            val nativeSegments = gson.fromJson(json, Array<NativeSegment>::class.java) ?: emptyArray()

            nativeSegments.map { segment ->
                LocalTranscribedSegment(
                    offsetSeconds = (segment.offsetMs / 1000L).toInt(),
                    text = segment.text,
                )
            }
        }

    /** Releases the native model handle. Safe to call even if a model was never loaded. */
    fun release() {
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
