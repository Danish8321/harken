# ARC-013 — A fresh byte array is allocated for every audio chunk

- **Severity:** medium
- **Status:** fixed
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

## Resolution

`AudioRecordCapture.onChunk` is now `(chunk: ByteArray, length: Int) -> Unit` and
hands over the capture buffer itself. The `buffer.copyOf(bytesRead)` is gone:
~32 KB/s of garbage, ~345 MB over a three-hour session, none of which survived
the call.

Both consumers already took an offset/length shape — `WavWriter.write` and the
RMS — so nothing downstream had to change beyond reading `length` instead of
`chunk.size`. The contract that the array is only valid for the duration of the
call is documented on the parameter and on `writeChunk`, because it is the kind
of thing a later reader would otherwise "fix" by keeping a reference.

`Pcm16Test.only the window it is given is read` guards the half of this that can
silently corrupt a reading: a level taken over the whole array rather than the
filled part is measuring the previous chunk's tail.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 150 unit tests, 0
  failures (139 before this change).

Not measured on a device. The costs here are arithmetic — passes over a chunk,
sorts per second, bytes allocated per second — and they are counted from the
code, not from a profile; what a device would add is how much of the audio
path's budget they were.
