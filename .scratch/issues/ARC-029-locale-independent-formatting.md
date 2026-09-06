# ARC-029 — `String.format` without a Locale

- **Severity:** low
- **Status:** fixed
- **Area:** `speech/OnDeviceTranscriber.kt`

## Problem

`realtimeFactor` is formatted with `String.format("%.2f", ...)` and no
`Locale`, so it takes the device default. On a device set to a
decimal-comma locale the telemetry emits `realtimeFactor=1,84`, which breaks
the `key=value` parsing every analysis of the logs has assumed.

## Fix

`String.format(Locale.ROOT, ...)` for anything that goes into telemetry or a
file; `Locale.getDefault()` only for text a person reads.

## Resolution

`OnDeviceTranscriber.realtimeFactor` formats with `Locale.ROOT`. It is the only
`String.format` without a locale that reached telemetry; the two elsewhere
(`LiveUpdateNotification.formatElapsed`, `TranscriptText.timestamp`) already
passed `Locale.ROOT`, and the one deliberate `Locale.getDefault()` —
`TranscriptText`'s human-readable date — is text a person reads and stays as it
is.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 150 unit tests, 0
  failures (139 before this change).

Not measured on a device. The costs here are arithmetic — passes over a chunk,
sorts per second, bytes allocated per second — and they are counted from the
code, not from a profile; what a device would add is how much of the audio
path's budget they were.
