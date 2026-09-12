# ARC-059 — A second Record tap abandons the first capture with the microphone still open

- **Severity:** high
- **Status:** fixed
- **Area:** `recording/RecordingForegroundService.kt`, `recording/RecordingController.kt`, `recording/RecordingState.kt`

## Problem

`RecordingForegroundService.onStartCommand` has no idea whether it is already recording. Its
start path assigns over three fields unconditionally:

```kotlin
synchronized(writerGate) {
    writer = WavWriter(RandomAccessFile(filePath, "rw"))
    silenceDetector = SilenceDetector(...)
}
activeRecordingId = recordingId
activeFilePath = filePath
...
capture = AudioRecordCapture(onChunk = ::writeChunk, onError = ::onCaptureError, scope = captureScope)
capture?.start()
```

Nothing stops the capture that was in the field, and nothing closes the writer that was in the
field. A second start while one is running leaves behind:

- **An `AudioRecordCapture` nobody can stop.** The only reference to it was the `capture`
  field, which has just been overwritten. Its `isRunning` is still true and its loop is a
  `while (isRunning)` around a blocking `record.read`, with no suspension point — so
  `captureScope.cancel()` in `onDestroy` does not end it either. It holds an initialised,
  started `AudioRecord` until the process dies. The microphone indicator stays lit with no
  recording on screen and no notification to stop.
- **A `WavWriter` nobody can close.** Its `RandomAccessFile` is leaked and its length header is
  never patched, so the first recording's WAV is left at its placeholder length with no session
  row. Recovery adopts it at the next launch — the audio is not lost — but until then it is a
  file the app reports as zero seconds long.
- **Both loops calling the same `writeChunk`.** `onChunk` is `::writeChunk`, a bound reference
  to the service, so the abandoned loop keeps delivering into whatever `writer` now holds. Two
  independent capture streams appending to one WAV.

`RecordingController.startRecording` makes this worse before the service ever sees it:

```kotlin
RecordingState.markStarted(recordingId, filePath)
RecordingForegroundService.start(context, recordingId, filePath)
```

`markStarted` does `current.set(...)` — an unconditional overwrite. The first recording's
`InProgress` is gone, so `markStopped` will emit a `RecordingCompleted` naming the *second*
recording's id and path, and the elapsed clock restarts from zero.

## Reachability

One tap that lands before the UI has flipped the button from Record to Stop.

The window is not one frame. `onStartCommand` runs on the main looper, and it does
`startForeground`, opens a `RandomAccessFile`, constructs an `AudioRecord` and calls
`startRecording()` — all of it ahead of the recomposition that swaps the button. Then the path
from `RecordingState._isRecording` to `CaptureUiState.isRecording` is a collector hop in
`CaptureViewModel` before Compose is even asked to recompose. Cold-mic acquisition is tens to
hundreds of milliseconds, which is exactly the interval in which a user who is not sure the
first tap registered taps again.

Not reproduced on a device. What is certain from the code is the shape: there is no guard
anywhere between the button and the two overwritten fields.

## Related but not this

ARC-046 was the same class of bug one tier over — finishing one transcription killing the next
one's foreground service — and was fixed by making the owner check identity before acting. This
is that check missing on the recording side.

ARC-034 is why pausing does not touch `AudioRecordCapture` at all: giving up the microphone is
the expensive, failure-prone operation. That reasoning is what makes leaking a started
`AudioRecord` the bad outcome it is here.

## The fix

Refuse the second start at the point that knows, and only there.

`RecordingState.markStarted` becomes a claim rather than an assignment, and says whether it was
granted:

```kotlin
fun markStarted(recordingId: UUID, filePath: String): Boolean
```

- No recording in flight: claims it, returns true — what it always did.
- A recording in flight under a *different* id: refuses, returns false, touches nothing.
- The same id again: re-anchors the clock and returns true.

That last case is not a special case for its own sake. Both the controller and the service call
`markStarted` for one recording: the controller on the tap, so the UI flips before the service
is scheduled, and the service when capture actually begins. The service's call is what makes the
on-screen counter agree with the length of the WAV — everything between the tap and
`AudioRecord.startRecording()` captured no audio — so it has to keep re-anchoring.

`RecordingController.startRecording` then returns the id only when the claim was granted:

```kotlin
fun startRecording(context: Context): UUID? {
    ...
    if (!RecordingState.markStarted(recordingId, filePath)) return null
    RecordingForegroundService.start(context, recordingId, filePath)
    return recordingId
}
```

and the service keeps its own guard, because it owns the `AudioRecord` and the file handle and
an intent can be delivered to it by something other than the controller:

```kotlin
if (activeRecordingId != null) { ...; return START_NOT_STICKY }
```

Two guards, not one, and they are not the same guard twice. The controller's stops the
`RecordingState` overwrite, which the service cannot undo by then. The service's stops the
leaked `AudioRecord` and the leaked writer, which the controller cannot promise to prevent.

## Evidence

`.claude/scripts/test-fast.sh`: **211 tests, 0 failures** (208 before). `check.sh` OK.

Three tests in `RecordingStateTest`:

- `a second start under a different id is refused and the first recording survives`
- `the recorder re-anchoring the same recording is not a second start`
- `a start after the previous recording stopped is granted`

Falsified by making the claim unconditional again (`current.updateAndGet { claimed }`): exactly
`a second start under a different id is refused and the first recording survives` fails
(211 tests, 1 failure), and the other two stay green — which is the point, since they are what
pins the re-anchor the fix must not break.

One more change the ticket's own fix forced, with no test of its own for the same
Robolectric reason: `stopRecording` now clears `activeRecordingId` and `activeFilePath` before
it calls `markStopped`. Without that the service's new guard outlives the state it guards —
`RecordingState` reports idle, the controller's claim is granted, and the service refuses the
Intent, leaving the UI showing a capture with no microphone open.

The service's own guard has no test and cannot have one here: there is no Robolectric in this
module, so `RecordingForegroundService` is not constructible off a device. It is three lines
around a null check on a field the same method assigns, and the branch it adds is the one the
controller's guard already makes unreachable in normal use — which is why it is a backstop and
not the fix.
