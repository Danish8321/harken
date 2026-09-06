# ARC-006 — `AudioRecordCapture.stop()` can release a native object mid-read

- **Severity:** high
- **Status:** open
- **Area:** `audio/AudioRecordCapture.kt`

## Problem

```kotlin
isRunning = false
withTimeoutOrNull(1000) { captureJob?.join() }
audioRecord?.stop()
audioRecord?.release()
```

`withTimeoutOrNull` returns `null` on timeout and execution falls straight
through to `release()`. The capture loop is then still inside
`AudioRecord.read()`, which holds a pointer to the object being freed. That is
a use-after-free in native code — a SIGSEGV in the audio HAL, and a tombstone
that looks nothing like its cause.

A one-second timeout is not generous: `read()` blocks until the buffer fills,
and the buffer is `minBufferSize * 4`. A device under load can exceed it.

## Fix

Call `audioRecord.stop()` *before* joining — that unblocks `read()` — and
release only after the join actually completes. If the join still times out,
leak the object rather than free it under a live reader, and report it in
telemetry.
