# ARC-045 — The recording capture loop is back on `Dispatchers.Default`

- **Severity:** high
- **Status:** fixed
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

## Resolution, 2026-09-08

Added a second scope, `captureScope` (`SupervisorJob() + Dispatchers.IO`), used only for
constructing `AudioRecordCapture`. The existing `scope` (`Dispatchers.Default`) stays as
is for the service's own short-lived coroutines (`stopRecording`'s `launch`). `onDestroy`
cancels both.

No dedicated test: which dispatcher a scope is built on isn't something a unit or
instrumented test can assert without reflecting into coroutine internals, and this repo's
gates don't include one for ARC-012 either. Verified by reading the fix against
`AudioRecordCapture`'s own constructor doc comment ("IO, not Default... ARC-012") and by
`check.sh`/`test-fast.sh`/`test-full.sh` all passing (device `AIN065 - 16`, fresh install,
full instrumented suite — confirms nothing else broke).
