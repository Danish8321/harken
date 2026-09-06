# ARC-009 — Recording duration is measured with the wall clock

- **Severity:** high
- **Status:** open
- **Area:** `recording/RecordingState.kt`

## Problem

`RecordingState.elapsedMs()` and `markStopped()` both use
`System.currentTimeMillis()`, while `RecordingForegroundService` uses
`SystemClock.elapsedRealtime()` for its own timers. Two clocks, one recording.

`currentTimeMillis` is settable: an NTP correction, a timezone/DST rollover, or
the user changing the clock mid-capture moves it. The value it produces is
written to `durationSeconds` and is what the Library card and the seek bar are
scaled from. A backwards jump yields a negative duration; a forwards jump makes
a 4-minute recording claim it is an hour.

The recording's true length is not in doubt — it is the WAV's byte count, which
`TranscriptionCoordinator.wavDurationSeconds` and `RecordingRecovery` both
already derive. The stored duration is the only place a second, weaker source
is used.

## Fix

`SystemClock.elapsedRealtime()` for elapsed time everywhere, and derive the
persisted `durationSeconds` from the file length, so all three call sites agree.
