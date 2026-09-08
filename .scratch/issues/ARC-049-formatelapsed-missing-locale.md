# ARC-049 — `RecordScreen.formatElapsed` can render non-Latin digits

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/RecordScreen.kt`

## Problem

`formatElapsed(totalSeconds: Int)` (`RecordScreen.kt:739-744`):

```kotlin
return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
```

`String.format` with no `Locale` resolves `%d` against `Locale.getDefault()`. On a device
set to Arabic, Persian, Bengali etc. this can render native digit glyphs instead of ASCII
(e.g. `٠١٢٣`). ARC-029 already fixed this exact bug class in
`LiveUpdateNotification.formatElapsed` and `TranscriptText.timestamp` (both `Locale.ROOT`)
but missed this third, near-identical function. It drives the 36sp elapsed-time readout
shown for the whole length of every recording (`LiveMeter`), and `SessionSheet.kt:547`
reuses it for transcript-row timestamps too.

## Fix

`"%d:%02d:%02d".format(Locale.ROOT, h, m, s)` (and the `m`/`s` branch), matching the other
two formatters ARC-029 already fixed.

## Found by

Third fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Added `Locale.ROOT` to both branches of `formatElapsed`, matching
`LiveUpdateNotification.formatElapsed` and `TranscriptText.timestamp`.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK.
