# ARC-010 — Every audio chunk's RMS is computed three times

- **Severity:** medium
- **Status:** fixed
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

## Resolution

The level of a chunk is now read once, where the chunk arrives, by the one
function that knows how: `audio/Pcm16.rms(pcm, offset, length)`.
`RecordingForegroundService.writeChunk` computes it and hands the number to all
three readers — the meter (`Pcm16.normalized`), the noise floor, and the silence
verdict.

`SilenceDetector.add(pcm, offset, length)` became `add(chunkRms, bytes)`, which
removed both of its own passes: it no longer computes an RMS to feed the floor
and then computes it again to decide whether the chunk was silent. The private
`chunkRms` in `SilenceDetector` and the private `pcm16Rms` in
`RecordingForegroundService` are both deleted — the two implementations of the
same number are gone with them.

Three passes over 2,560 samples per chunk became one: ~32,000 sample reads a
second saved on the capture path.

### Deviation from the fix as written

The ticket said the new signature "makes it testable without synthesising PCM".
The tests still synthesise PCM, deliberately: they feed it through `Pcm16.rms`
via one `internal` extension in `test/audio/PcmChunks.kt`. Asserting against
hand-picked levels instead would have stopped exercising the sample arithmetic
that the real-audio test depends on.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 150 unit tests, 0
  failures (139 before this change).

Not measured on a device. The costs here are arithmetic — passes over a chunk,
sorts per second, bytes allocated per second — and they are counted from the
code, not from a profile; what a device would add is how much of the audio
path's budget they were.
