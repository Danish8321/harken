# ARC-013 — A fresh byte array is allocated for every audio chunk

- **Severity:** medium
- **Status:** open
- **Area:** `audio/AudioRecordCapture.kt`

## Problem

`captureLoop` does `onChunk(buffer.copyOf(bytesRead))` — a new 5,120-byte array
6.25 times a second, ~32 KB/s, ~345 MB of garbage over a three-hour session.
None of it survives: the consumer writes it to the WAV and computes an RMS.

The copy exists because `buffer` is reused, so it is defensive rather than
gratuitous. It is still allocation churn on the one path in the app that must
never stall, and GC pauses during capture are exactly the thing that produces
the `slowChunks` the service already instruments for.

## Fix

Pass `(buffer, bytesRead)` and document that the array is only valid for the
duration of the call — the two consumers (`WavWriter.write`, the RMS) both
already take an offset/length shape or trivially can.
