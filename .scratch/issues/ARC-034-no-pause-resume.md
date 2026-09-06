# ARC-034 — A recording cannot be paused

- **Severity:** medium
- **Status:** fixed
- **Area:** `recording/RecordingForegroundService.kt`, `ui/RecordScreen.kt`

## Problem

Stop is the only control. A break in a meeting means either recording the
break — which the five-minute silence auto-stop will then end, splitting the
meeting in two — or stopping and starting a second recording that has to be
reconciled by hand afterwards.

The WAV format supports it trivially: pausing is not writing chunks. The
service already owns the writer gate and the silence detector, both of which
simply stop being fed.

## Fix

A pause action on the record screen and in the notification, that stops feeding
`writeChunk` and freezes the elapsed counter and the silence detector, without
touching `AudioRecord` (so no re-acquisition of the mic and no gap in the
capture pipeline).

## Resolution

Pause is a state *of* a recording, not a third state beside recording and idle:
`isRecording` stays true, the service keeps the microphone and the notification
stays ongoing. `AudioRecord` is never touched, so there is no re-acquisition and
no gap in the capture pipeline.

- `RecordingState` gained `pausedTotalMs` / `pausedAtElapsedMs` on its
  `InProgress` record and an `isPaused` flow. `elapsedMs()` measures to the
  moment the pause began and subtracts every completed break, so the on-screen
  counter, the notification chronometer and the length of the WAV all agree.
- `RecordingForegroundService` holds an `AtomicBoolean` and handles two new
  actions. `writeChunk` early-returns while paused, publishing amplitude 0 — so
  nothing reaches the writer *or* the silence detector, and the five-minute
  auto-stop freezes with the counter rather than firing during the break.
- `LiveUpdateNotification` gets a Pause/Resume action. Paused drops
  `setUsesChronometer` (a chronometer cannot be stopped) and shows the frozen
  time as text instead.
- `RecordScreen` shows a pause button to the left of the record control, inside
  an `AnimatedVisibility` so the record FAB stays centred when idle.

The service's own `startedAtElapsedMs` field was deleted: `RecordingState` is now
the single owner of the recording's clock, which is what makes the notification
and the screen incapable of disagreeing.

### Deviation from the fix as written

The ticket asked for the counter and the silence detector to freeze. They do,
but by different means: the counter freezes by arithmetic in `RecordingState`,
while the detector freezes because it stops being fed. Nothing in
`SilenceDetector` changed.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 120 unit tests,
  0 failures. Seven of them are new pause cases in `RecordingStateTest`.

`RecordingState` gained one seam to make those cases possible: an internal
`elapsedRealtime: () -> Long`, defaulting to `SystemClock::elapsedRealtime`.
JVM unit tests run against the Android stub, which returns 0 forever with
`isReturnDefaultValues`, so the pause arithmetic — the only arithmetic in the
object — was otherwise unreachable off-device. Production never assigns it.

## Device verification — not done

No device was attached (`adb devices` is empty). Outstanding, on a fresh
install:

- Pause mid-recording, wait past five minutes, resume: the recording must still
  be running and the counter must show only the recorded time.
- Pause from the notification and resume from the screen, and the reverse.
- Compare the final counter against the saved recording's duration.
