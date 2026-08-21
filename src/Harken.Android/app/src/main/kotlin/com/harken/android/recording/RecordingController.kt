package com.harken.android.recording

import android.content.Context
import java.io.File
import java.util.UUID

// Ports src/Harken.Mobile/Platforms/Android/AndroidRecordingService.cs — starts the
// foreground service with the recording id/path as intent extras (a separate component,
// so it can't be handed an object reference), and marks RecordingState started here so
// the UI sees "recording" the instant it asks rather than after the service is scheduled.
object RecordingController {
    fun startRecording(context: Context): UUID {
        val recordingId = UUID.randomUUID()
        val filePath = File(context.filesDir, "$recordingId.wav").absolutePath

        RecordingState.markStarted(recordingId, filePath)
        RecordingForegroundService.start(context, recordingId, filePath)
        return recordingId
    }

    fun stopRecording(context: Context) {
        RecordingForegroundService.stop(context)
    }
}
