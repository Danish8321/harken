# ARC-016 — A finished recording is saved by the UI, not by the recorder

- **Severity:** medium
- **Status:** closed
- **Area:** `recording/RecordingState.kt`, `ui/CaptureViewModel.kt`

## Problem

`RecordingForegroundService` finishes a capture by calling
`RecordingState.markStopped()`, which does `_completed.tryEmit(...)` on a
`MutableSharedFlow(extraBufferCapacity = 1)` — **replay = 0**. The only
collector in the app is `CaptureViewModel`, which then writes the session row.

So the durable record of a recording exists only if a ViewModel belonging to
one screen happened to be alive and collecting at the instant the service
stopped. `tryEmit` with no subscriber returns `true` and drops the event.

In practice the back stack keeps that ViewModel alive, and `RecordingRecovery`
adopts anything missed at the next launch — which is why this has not been seen
as data loss. What the user sees instead is the recording simply not appearing
in Library until they restart the app, after an auto-stop that happened while
they were elsewhere. And a service restarted into a process with no Activity
has no collector at all.

The dependency points the wrong way: the recorder owns the recording, so the
recorder should own the write. The UI should learn about it by observing the
database, as Library already does.

## Fix

Write the session row from the service (or from a repository call the service
makes) and let `RecordingState.completed` remain a UI notification only.

## Resolution

`RecordingForegroundService` writes the session row itself, in the same stop path
that closes the WAV, and passes the outcome to `RecordingState.markStopped`:

```kotlin
val durationSeconds = WavFormat.durationSeconds(File(filePath))
val saveError = saveSession(recordingId, filePath, durationSeconds)
RecordingState.markStopped(stopReason, durationSeconds, saveError)
```

`RecordingCompleted` gained a `saveError`, and `CaptureViewModel` now only
reports what it is told — its `saveLocal` is gone. `retrySave` stays, because a
save that failed still has its audio on disk and a retry is a user action, but it
is no longer the path a recording is saved by.

The dependency now points the way the domain does: the recorder owns the
recording, so the recorder owns the write, and the UI learns about it by
observing the database as Library already does.

## Evidence

`check.sh` OK, `test-fast.sh` OK, including a new `RecordingStateTest` case for a
completion that carries a save failure.

## Device verification

Nothing Phone 2, fresh install. A 25-second capture appears in Library as a
0m 25s row with no restart; a capture interrupted by a process kill is recovered
at the next launch. The removal of the dropped-event window is structural — the
write no longer passes through a `MutableSharedFlow` with `replay = 0` — rather
than something a device run can show directly, since the back stack usually kept
that collector alive.
