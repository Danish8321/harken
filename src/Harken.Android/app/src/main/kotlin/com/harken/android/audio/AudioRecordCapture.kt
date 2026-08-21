package com.harken.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

// Ports src/Harken.Mobile/Platforms/Android/AndroidAudioCapture.cs directly against
// AudioRecord — no MAUI binding layer in between.
class AudioRecordCapture(
    private val onChunk: (ByteArray) -> Unit,
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

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            WavFormat.SampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        audioRecord = record
        record.startRecording()
        isRunning = true

        captureJob = scope.launch { captureLoop(record, bufferSize) }
    }

    private suspend fun captureLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (isRunning) {
            val bytesRead = record.read(buffer, 0, buffer.size)
            if (bytesRead > 0) {
                onChunk(buffer.copyOf(bytesRead))
            }
        }
    }

    suspend fun stop() {
        isRunning = false
        withTimeoutOrNull(1000) { captureJob?.join() }
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
