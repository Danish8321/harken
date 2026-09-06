# ARC-003 — A transcription is killed whenever the user leaves the app

- **Severity:** critical
- **Status:** closed
- **Area:** `speech/TranscriptionCoordinator.kt`

## Problem

`TranscriptionCoordinator` is a process-wide `object` running the decode on
`CoroutineScope(SupervisorJob() + Dispatchers.Default)`. Its doc comment
explains that it lives outside a ViewModel so navigating away does not cancel
it — which is correct as far as it goes, and stops one screen short.

Nothing holds the *process* up. The moment the last Activity stops, the process
becomes a cached process with no foreground component, and it is then the first
thing Android reclaims — while holding the ~610 MB working set
[ADR-0014](../../docs/adr/0014-minimum-supported-device.md) measured. A decode
of a 21-minute meeting takes 597 seconds on the reference device. Ten minutes
of not-looking-at-the-app is the normal case, not the edge case.

`failInterruptedTranscriptions` already exists to clean up after this, which is
the tell: the app has a recovery path for its most common failure instead of a
fix for it. UI-035 taught the message to name low memory as a possible reason;
on a device *above* the bar the same kill still happens for the ordinary reason
that a cached process holding 610 MB is what the LMK takes first.

## Fix

Run the decode inside a `dataSync` foreground service (or an expedited
`WorkManager` job, which wraps one) for the duration, with a notification that
shows which recording is being transcribed and lets the user cancel. The
coordinator's one-at-a-time invariant moves into the service.

## Resolution

`speech/TranscriptionService.kt` — a `dataSync` foreground service that holds the
process up for the length of one decode. It is a lifetime holder, not a second
copy of the logic: it starts `TranscriptionCoordinator`, renders its progress,
and stops itself when `activeSessionId` goes back to null. The one-at-a-time
invariant stays in the coordinator's compare-and-set, so starting the service
twice cannot start two decodes, and finish, fail and cancel all leave by the
same path.

Supporting changes:

- `Transcriber.transcribe` gained an `onProgress(fraction)` parameter, reported
  in samples handed to whisper rather than in spans — spans run 30 to 300
  seconds, so counting them would make the bar jump ten times further for one
  span than the next.
- `TranscriptionCoordinator` keeps the running `Job`, exposes `cancel()`, and
  writes the cancelled message under `NonCancellable` before rethrowing —
  without that the row stays stuck at "Running", because a cancelled coroutine
  refuses the write.
- `LibraryViewModel.transcribe` starts the service instead of calling the
  coordinator directly.
- Notification strings moved into `strings.xml` (they are user-facing copy, and
  ARC-018 is the same defect elsewhere).

### The Cancel button needed a native seam

The first device run exposed a second defect behind the first: cancelling the
coroutine did nothing until the current span finished, because `whisper_full`
does not return until it has decoded everything it was given — measured at
**55.8 seconds** from tap to `outcome=cancelled`, with Android logging
`Stop FGS timeout` while it waited. A Cancel that lands a minute later is not a
Cancel.

whisper's `whisper_full_params.abort_callback` is the seam for this. The JNI now
carries one `std::atomic<bool>` (one flag, not one per context, because decoding
is single-flight by the coordinator's own invariant), set from a new
`nativeSetAbort` and read by ggml before each graph computation.
`OnDeviceTranscriber` clears it at the start of a decode, registers
`invokeOnCompletion { nativeSetAbort(true) }` on its own job, and calls
`ensureActive()` after each native call — an aborted `whisper_full` returns an
empty result rather than throwing, so without that the loop would quietly record
the rest of the recording as silence.

## Evidence

`.claude/scripts/check.sh` — `== check: OK ==` (dotnet build, assembleDebug,
assembleRelease, lintDebug).
`.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, including three new
`TranscriptionCoordinatorTest` cases: progress reaches the caller, a cancel
fails the session with the cancelled message and releases the transcriber, and a
new session can start after a cancel.

## Device verification

Nothing Phone 2, fresh uninstall + install of the debug build, 3m 34s recording.

- **Survives backgrounding.** Transcription started, HOME pressed 3 seconds
  later, decode ran to completion in the background:
  `transcribe_finished outcome=succeeded audioSeconds=249 segments=40`. Before
  this change the same sequence was what killed a decode.
- **Foreground service is what holds it up.** `dumpsys activity services` during
  the run: `isForeground=true foregroundId=1002 types=0x00000001` (dataSync),
  `category=progress`, one action.
- **Notification.** `Transcribing “Late night recording”` / `Transcribing on
  this phone. Nothing is uploaded.`, indeterminate at the start, then
  `android.progress=42` with `About 1 min left. Transcribing on this phone.`
  once past 5%.
- **Cancel.** Tapped in the shade at 04:58:30.147;
  `transcribe_finished outcome=cancelled` at 04:58:32.454 — **2.3 seconds**,
  against 55.8 before the abort callback. The service stopped itself
  (`ServiceRecord` count 0) and the Library row read
  `Transcription cancelled. Tap to try again.` with Transcribe re-enabled.
- **Retry after cancel.** Same recording transcribed again to
  `outcome=succeeded segments=48`, so the abort flag is cleared correctly rather
  than poisoning the next run.
