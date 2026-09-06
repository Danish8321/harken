# ARC-010 — Every audio chunk's RMS is computed three times

- **Severity:** medium
- **Status:** open
- **Area:** `recording/RecordingForegroundService.kt`, `audio/SilenceDetector.kt`

## Problem

For each 5,120-byte chunk, at 6.25 chunks per second, for up to three hours:

1. `RecordingForegroundService.writeChunk` calls `pcm16Rms(chunk)` for the
   amplitude meter.
2. `SilenceDetector.add` calls `chunkRms(chunk)` to feed `noiseFloor.observe`.
3. `SilenceDetector.add` calls it again inside `isSilent(chunk)`.

Three full passes over 2,560 samples where one would do — the same number, from
the same bytes, computed three ways in two files. Straight DRY violation with a
measurable cost: ~48,000 redundant sample reads per second on the audio path.

## Fix

Compute the RMS once at the point the chunk arrives and pass the value down.
`SilenceDetector.add(chunk)` becomes `add(rms, bytes)`, which also makes it
testable without synthesising PCM.
