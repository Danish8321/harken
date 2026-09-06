# ARC-008 — A sticky service restart tells the user a recording they never started failed

- **Severity:** high
- **Status:** closed
- **Area:** `recording/RecordingForegroundService.kt`

## Problem

`onStartCommand` returns `START_STICKY`. After the process is killed, Android
recreates the service and delivers a **null** intent. The extras parse fails,
and the service publishes `RecordingState.errors` — the user sees
*"Couldn't start recording — missing session details"* for a recording they
did not ask for, minutes or hours after the fact, with the real recording
already recovered silently by `RecordingRecovery`.

`START_STICKY` is also the wrong contract here. Restarting a microphone capture
without the user's knowledge is worse than not restarting it: the audio between
the kill and the restart is gone either way, and the resulting file is a second
recording of a moment nobody chose to record.

## Fix

Return `START_NOT_STICKY`, and treat a null intent as a stop rather than an
error. Recovery of the interrupted file already exists and is the right
mechanism.

## Resolution

`onStartCommand` returns `START_NOT_STICKY` on every path, and a start with no
recording in it stops the service instead of publishing an error:

```kotlin
Log.w(TAG, "Started with no recording; stopping")
stopSelf(startId)
return START_NOT_STICKY
```

Restarting a microphone capture the user did not ask for is worse than not
restarting it — the audio between the kill and the restart is gone either way,
and what comes back is a second recording of a moment nobody chose to record.
`RecordingRecovery` already adopts the interrupted file, which is the right
mechanism and the one that keeps the audio.

## Evidence

`check.sh` OK, `test-fast.sh` OK.

## Device verification

Nothing Phone 2, fresh install. Started a recording, backgrounded the app, then
`run-as com.harken.android.debug kill -9 <pid>` mid-capture. Fifteen seconds
later `pidof` was empty and `dumpsys activity services` listed no service — no
sticky restart, and no error notification for a recording the user never
started. Relaunching recovered the audio:
`RecordingRecovery: Recovered 77a48224-… (12s, header repaired=true)`, and the
Library showed it as a 0m 12s recording.
