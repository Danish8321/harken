# ARC-045 — The recording capture loop is back on `Dispatchers.Default`

- **Severity:** high
- **Status:** open
- **Area:** `recording/RecordingForegroundService.kt`

## Problem

ARC-012 moved the blocking `AudioRecord.read()` loop and its synchronous
`WavWriter.write()` disk calls off `Dispatchers.Default` and onto
`Dispatchers.IO`, because a permanently blocked thread on the CPU-sized
`Default` pool for the length of a recording (up to three hours) starves
everything else scheduled there — including the whisper decode.

`AudioRecordCapture`'s `scope` constructor parameter defaults to
`Dispatchers.IO` specifically to keep that fix in force. But
`RecordingForegroundService` builds its own scope:

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

and passes it explicitly at the one call site that matters:

    capture = AudioRecordCapture(onChunk = ::writeChunk, onError = ::onCaptureError, scope = scope)

(`RecordingForegroundService.kt:85` and `:173`). The explicit argument
overrides the class's safe default, silently reintroducing the exact
regression ARC-012 closed.

## Fix

Give `AudioRecordCapture` its own `Dispatchers.IO`-based scope, or drop the
`scope =` argument entirely and let `AudioRecordCapture`'s default apply.
Keep `Dispatchers.Default` for the service's own short-lived coroutines
(e.g. `stopRecording`) that don't block.

## Found by

Fresh full-repo audit, 2026-09-08.
