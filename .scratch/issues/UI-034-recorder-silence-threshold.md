# UI-034 — The recorder's auto-stop still uses a fixed silence threshold

- **Severity:** medium
- **Status:** open
- **Area:** `audio/SilenceDetector.kt`, `recording/` foreground service

## Problem

`SpeechSpans` now reads its silence threshold off the recording
(`db6f4e8`), because a fixed level cannot serve both a headset mix and a
phone on a table — a real meeting (AMI ES2002a) has a median window RMS
of 126 against `SilenceDetector.DefaultAmplitudeThreshold = 500`.

`SilenceDetector` still uses that fixed 500, and it drives the
five-minute auto-stop that ends a forgotten recording. On a quiet
recording — the same class of audio that read as 90% silence to the
transcriber — every pause counts toward that timeout. On AMI ES2002a the
longest run of "silence" at threshold 500 is 210 s against a 300 s
timeout: it would not have stopped, but the margin is 90 seconds on a
meeting with four people talking.

A capture that stops itself mid-meeting loses audio that cannot be
recovered, which makes this worse than the transcription bug it mirrors,
even though it is rarer.

The two also no longer agree, and `SpeechSpans`'s own doc comment used to
promise they would: "what the recorder calls silence and what the
transcriber skips cannot drift apart."

## Why it was deferred

Scoped out deliberately when the transcriber fix was agreed. The
transcriber sees the whole file and can take a percentile of it; a
streaming detector cannot. It needs its own estimator — a running floor
that adapts within the first seconds of a recording and then tracks
slowly — and its own tests, which is a slice rather than a constant.

## Suggested shape (not yet designed)

- Estimate the noise floor from the first N seconds of the capture, then
  track it with a slow-moving minimum so a room that gets quieter does
  not start counting speech as silence.
- Clamp it exactly as `SpeechSpans.amplitudeThreshold` does, and for the
  same two reasons (an all-speech recording, a hissing room).
- Consider whether the auto-stop needs the same threshold at all, or
  whether "no sound at all for five minutes" wants a lower one than "this
  second is worth decoding".
