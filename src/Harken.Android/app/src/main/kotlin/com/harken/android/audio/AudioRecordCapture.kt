package com.harken.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.harken.android.telemetry.Telemetry
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
    /**
     * Called with the capture buffer itself and the number of bytes in it, which is valid
     * only for the duration of the call. Handing over a copy instead cost a fresh 5 KB
     * array 6.25 times a second — ~345 MB of garbage over a three-hour session, collected
     * on the one path where a GC pause is a gap in the recording (ARC-013).
     */
    private val onChunk: (chunk: ByteArray, length: Int) -> Unit,
    // Fatal for the in-flight recording: init failed, or the read loop hit an AudioRecord
    // error code (ERROR_DEAD_OBJECT etc) it can't just spin through. Called at most once.
    private val onError: (String) -> Unit = {},
    // IO, not Default: the loop below spends its life inside record.read(), which parks a
    // thread rather than using a core, and Default is sized to the core count (ARC-012).
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
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
                onChunk(buffer, bytesRead)
            } else if (bytesRead < 0) {
                Log.e(TAG, "AudioRecord.read returned error code $bytesRead")
                isRunning = false
                onError("Microphone stopped responding (code $bytesRead)")
            }
        }
    }

    suspend fun stop() {
        val record = audioRecord ?: return
        audioRecord = null
        val job = captureJob
        captureJob = null

        isRunning = false
        // stop() before the join, not after. read() blocks until its buffer fills — four
        // times the device minimum, so a phone under load can sit in there longer than any
        // timeout worth waiting for — and stopping the record is what unblocks it. Joining
        // first meant the timeout expired with the loop still inside read(), holding a
        // pointer to the object the next line freed (ARC-006).
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop on an already-stopped record", e)
        }

        val joined = job == null || withTimeoutOrNull(JoinTimeoutMs) { job.join() } != null
        if (joined) {
            record.release()
            return
        }

        // The loop is still in native code with this object's pointer. Freeing it here is
        // a use-after-free in the audio HAL — a SIGSEGV that looks nothing like its cause.
        // The buffer is a few hundred kilobytes and the process is about to be a candidate
        // for death anyway; leaking it is the cheaper wrong outcome, and it gets reported
        // rather than hidden.
        Log.e(TAG, "Capture loop still running after ${JoinTimeoutMs}ms; leaking AudioRecord rather than freeing it under a live reader")
        Telemetry.event("capture_stop_join_timeout", "timeoutMs" to JoinTimeoutMs)
    }

    private companion object {
        /**
         * How long stop() waits for the capture loop. Generous now that the record is
         * stopped first: a read that has already been unblocked returns in milliseconds, so
         * reaching this bound means something is wrong rather than merely slow.
         */
        const val JoinTimeoutMs = 2000L
    }
}
