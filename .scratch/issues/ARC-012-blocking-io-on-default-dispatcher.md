# ARC-012 — Blocking reads run on the CPU dispatcher

- **Severity:** medium
- **Status:** open
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
