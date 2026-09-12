package com.harken.android.recording

import android.content.Context
import java.io.File
import java.util.UUID

// Starts the foreground service with the recording id and path as intent extras (a
// separate component, so it cannot be handed an object reference), and marks
// RecordingState started here so the UI sees "recording" the instant it asks rather than
// after the service is scheduled.
object RecordingController {
    /**
     * Starts a recording, or returns null if one is already running.
     *
     * The claim is what decides, not a read of [RecordingState.recordingId]: a tap that lands
     * before the button has flipped to Stop used to overwrite the running recording's id and
     * path here, and then start a second capture the service had no way to tell from the
     * first (ARC-059).
     */
    fun startRecording(context: Context): UUID? {
        val recordingId = UUID.randomUUID()
        val filePath = File(context.filesDir, "$recordingId.wav").absolutePath

        if (!RecordingState.markStarted(recordingId, filePath)) return null
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
