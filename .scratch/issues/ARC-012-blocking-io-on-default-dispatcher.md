# ARC-012 — Blocking reads run on the CPU dispatcher

- **Severity:** medium
- **Status:** fixed
- **Area:** `audio/AudioRecordCapture.kt`, `speech/OnDeviceTranscriber.kt`

## Problem

`AudioRecordCapture` uses `CoroutineScope(Dispatchers.Default)` for a loop whose
body is `record.read()` — a call that blocks until the hardware fills the
buffer, in a loop that runs for up to three hours. `Dispatchers.Default` is
sized to the CPU count and is meant for work that *uses* a core, not work that
parks one. On a device where the pool is small, one permanently blocked thread
is a large fraction of it.

`OnDeviceTranscriber.transcribe` has the same shape from the other direction:
`withContext(Dispatchers.Default)` wrapping `RandomAccessFile` seeks and 64 KiB
reads interleaved with the native decode.

## Fix

`Dispatchers.IO` for the capture loop and for the WAV reads; keep
`Dispatchers.Default` for the decode itself, which genuinely is CPU work.

## Resolution

`AudioRecordCapture`'s default scope is `Dispatchers.IO`. The loop it runs spends
its life inside `record.read()`, which parks a thread rather than using a core,
and holding one of `Dispatchers.Default`'s core-count threads for up to three
hours is a large fraction of that pool on a small phone.

In `OnDeviceTranscriber`, the two blocking file phases — `scanWindowRms` over the
whole WAV, and the per-span `readSamples` — are wrapped in
`withContext(Dispatchers.IO)`. The decode itself stays on `Dispatchers.Default`,
which is what it is for: `nativeTranscribe` is the only part of that call that
genuinely uses a core.

The switches are per span, not per read block, so a decode of dozens of spans
pays dozens of context switches against seconds of native work each.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 150 unit tests, 0
  failures (139 before this change).

Not measured on a device. The costs here are arithmetic — passes over a chunk,
sorts per second, bytes allocated per second — and they are counted from the
code, not from a profile; what a device would add is how much of the audio
path's budget they were.
