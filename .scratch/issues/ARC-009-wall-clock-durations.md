# ARC-009 — Recording duration is measured with the wall clock

- **Severity:** high
- **Status:** closed
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

## Resolution

One clock and one length.

- `RecordingState` times the live counter with `SystemClock.elapsedRealtime()`,
  the same clock `RecordingForegroundService` already used for its own timers.
- The persisted duration no longer comes from a clock at all. `WavFormat` gained
  `BytesPerSecond` and `durationSeconds(File)`, and the recorder, the recovery
  pass and the transcriber all call it — three copies of the same arithmetic
  collapsed into one, which is what made it possible for one of them to disagree.

## Evidence

`check.sh` OK, `test-fast.sh` OK. New `WavDurationTest` covers the byte count,
rounding down within a second, a header-only file, a truncated file (which used
to give a negative length) and a missing file. `RecordingStateTest` now asserts
the duration is the one the recorder passes in rather than one this object timed.

## Device verification

Nothing Phone 2, fresh install. A capture that ran 25,235 ms by the service's own
telemetry (`recording_stopped elapsedMs=25235 bytes=803840`) is listed as
**0m 25s** — 803,840 bytes less the 44-byte header is 25.12 seconds of 16 kHz
mono 16-bit PCM, so the Library is now reading the file rather than a clock.
