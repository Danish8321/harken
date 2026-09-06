# ARC-006 — `AudioRecordCapture.stop()` can release a native object mid-read

- **Severity:** high
- **Status:** closed
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

## Resolution

`stop()` stops the record before joining, and releases only if the join
completes:

```kotlin
try { record.stop() } catch (e: IllegalStateException) { ... }
val joined = job == null || withTimeoutOrNull(JoinTimeoutMs) { job.join() } != null
if (joined) { record.release(); return }
Log.e(TAG, "Capture loop still running ...")
Telemetry.event("capture_stop_join_timeout", "timeoutMs" to JoinTimeoutMs)
```

Stopping is what unblocks `read()`, so the join now waits on a loop that is
already returning rather than on a full buffer. If it still times out the object
is leaked rather than freed under a live reader: a few hundred kilobytes is the
cheaper wrong outcome than a use-after-free in the audio HAL, and the leak is
reported instead of hidden.

The timeout moved to a named 2 s constant — generous for a read that has already
been unblocked, so reaching it now means something is wrong rather than slow.

## Evidence

`check.sh` OK, `test-fast.sh` OK. Not unit-tested: `AudioRecord` is a framework
final class with no seam, and androidTest never runs (ARC-023) — verified on
device instead.

## Device verification

Nothing Phone 2, fresh install. Two recordings stopped from the UI
(`recording_stopped ... elapsedMs=32969` and `elapsedMs=35127`), each followed by
a transcription. No `capture_stop_join_timeout` in the log, and no tombstone,
SIGSEGV or FATAL from the audio HAL.
