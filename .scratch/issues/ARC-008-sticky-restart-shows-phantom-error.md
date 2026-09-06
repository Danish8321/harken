# ARC-008 — A sticky service restart tells the user a recording they never started failed

- **Severity:** high
- **Status:** open
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
