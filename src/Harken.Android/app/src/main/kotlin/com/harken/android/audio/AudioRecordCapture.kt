package com.harken.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

private const val TAG = "AudioRecordCapture"

// Ports src/Harken.Mobile/Platforms/Android/AndroidAudioCapture.cs directly against
// AudioRecord — no MAUI binding layer in between.
class AudioRecordCapture(
    private val onChunk: (ByteArray) -> Unit,
    // Fatal for the in-flight recording: init failed, or the read loop hit an AudioRecord
    // error code (ERROR_DEAD_OBJECT etc) it can't just spin through. Called at most once.
    private val onError: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    @Volatile private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            WavFormat.SampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = minBufferSize * 4

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavFormat.SampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            onError(e.message ?: "Microphone unavailable")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize, state=${record.state}")
            record.release()
            onError("Microphone unavailable")
            return
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.startRecording failed", e)
            record.release()
            audioRecord = null
            onError(e.message ?: "Microphone unavailable")
            return
        }
        isRunning = true

        captureJob = scope.launch { captureLoop(record, bufferSize) }
    }

    private suspend fun captureLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (isRunning) {
            val bytesRead = record.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                onChunk(buffer.copyOf(bytesRead))
            } else if (bytesRead < 0) {
                Log.e(TAG, "AudioRecord.read returned error code $bytesRead")
                isRunning = false
                onError("Microphone stopped responding (code $bytesRead)")
            }
        }
    }

    suspend fun stop() {
        isRunning = false
        withTimeoutOrNull(1000) { captureJob?.join() }
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop on an already-stopped record", e)
        }
        audioRecord?.release()
        audioRecord = null
    }
}
