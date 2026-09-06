# ARC-016 — A finished recording is saved by the UI, not by the recorder

- **Severity:** medium
- **Status:** open
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
