# ARC-029 — `String.format` without a Locale

- **Severity:** low
- **Status:** open
- **Area:** `speech/OnDeviceTranscriber.kt`

## Problem

`realtimeFactor` is formatted with `String.format("%.2f", ...)` and no
`Locale`, so it takes the device default. On a device set to a
decimal-comma locale the telemetry emits `realtimeFactor=1,84`, which breaks
the `key=value` parsing every analysis of the logs has assumed.

## Fix

`String.format(Locale.ROOT, ...)` for anything that goes into telemetry or a
file; `Locale.getDefault()` only for text a person reads.
