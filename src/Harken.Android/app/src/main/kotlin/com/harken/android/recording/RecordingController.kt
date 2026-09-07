package com.harken.android.recording

import android.content.Context
import java.io.File
import java.util.UUID

// Starts the foreground service with the recording id and path as intent extras (a
// separate component, so it cannot be handed an object reference), and marks
// RecordingState started here so the UI sees "recording" the instant it asks rather than
// after the service is scheduled.
object RecordingController {
    fun startRecording(context: Context): UUID {
        val recordingId = UUID.randomUUID()
        val filePath = File(context.filesDir, "$recordingId.wav").absolutePath

        RecordingState.markStarted(recordingId, filePath)
        RecordingForegroundService.start(context, recordingId, filePath)
        return recordingId
    }

    /**
     * Pauses or resumes the write side of the capture. The state itself is flipped by the
     * service, which owns the writer — this only asks.
     */
    fun setPaused(
        context: Context,
        paused: Boolean,
    ) {
        RecordingForegroundService.setPaused(context, paused)
    }

    fun stopRecording(context: Context) {
        RecordingForegroundService.stop(context)
    }
}
