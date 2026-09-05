# UI-034 — The recorder's auto-stop still uses a fixed silence threshold

- **Severity:** medium
- **Status:** closed
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

## What measurement changed about the problem above

The margin quoted in the problem statement is wrong, and wrong in the
direction that matters. "The longest run of silence at 500 is 210 s against
a 300 s timeout" reads the runs off the audio directly; the shipped detector
does not do that. It carries an accumulator that an audible chunk *decays*
by `AudibleDecayFactor = 10` rather than resetting, so the run keeps climbing
whenever more than 90.9% of chunks read silent — and at threshold 500, 97% of
AMI ES2002a reads silent.

Replaying the real accumulator over ES2002a (`accsim.py`):

| rule | longest quiet run over the 21-minute meeting | auto-stop |
|---|---|---|
| shipped, fixed 500 | 300 s reached at **617 s** | stops mid-meeting |
| floor-relative | 9.1 s | never |

So the defect is not a thin margin. The shipped recorder stops a real
four-person meeting halfway through and discards nothing — the audio to that
point is kept — but everything after 617 s never reaches the microphone.

## Why a level cannot decide this

Measured chunk RMS, 160 ms chunks:

| audio | p10 | p50 | p90 | p90/p10 |
|---|---|---|---|---|
| AMI ES2002a (4 people, headset mix) | 4 | 64 | 309 | 77 |
| `amb.wav` (empty room, phone on desk) | 134 | 289 | 595 | 4.4 |

The empty room is the **louder** signal. No threshold separates them, in
either direction: high enough to call the room silent also calls the meeting
silent, and low enough to hear the meeting also hears the room. What separates
them is structure — 77 against 4.4 — so speech has to be measured as a peak
above the recording's own floor rather than against any constant.

## Resolution

`1c65199`. `NoiseFloor` keeps the last 60 seconds of chunk levels, byte-driven
like the detector that owns it, and reports
`speechAt = clamp(p10 * 12, 60, 1000)`. A chunk below that is silence. The
decay and the session cap are unchanged.

Constants and why those values: window 60 s (30/60/120/300 all pass on both
recordings; 60 is the middle), factor 12 (8–20 pass; 12 is the middle),
ceiling 1000 (the tightest value that still lets an empty room time out; at
500 it never does), floor 60 (`SpeechSpans.MinAmplitudeThreshold`, shared).

`recording_stopped` now carries `noiseFloor`, `speechAt` and `peakSilentMs`.
"It stopped in the middle of my meeting" and "it recorded an empty room for
three hours" are the same event with `peakSilentMs` at opposite ends.

The C# `Harken.Core.Audio.SilenceDetector` and its 244-line test file are
deleted. Recording is on-device only (ADR-0011) and the port had no callers.

### Evidence

- `test-fast.sh` OK. `SilenceDetectorTest` 12 tests (the 7 that predate this
  change, unmodified, plus 5), `SilenceDetectorRealAudioTest` 2 tests, all
  passing. .NET side 14 + 32 passing after the deletion.
- `check.sh` OK, including `assembleRelease`.
- The real-audio test asserts against 90 seconds of ES2002a committed to
  `app/src/test/resources` (CC BY 4.0). Under the old rule that excerpt
  accumulates 65.3 s of quiet; under the new one, 9.1 s.

### Device verification

Nothing Phone 2 (AIN065), Android 16, build B4.1-260818-1726, 7,444,948 kB RAM.
Debug build, fresh install before each run (uninstall + install, no reused app
state). Screen held awake, `screen_off_timeout` raised for the duration and put
back to 120000 afterwards.

| session | audio | elapsed | reason | noiseFloor | speechAt | peakSilentMs |
|---|---|---|---|---|---|---|
| `94cd85f1` | empty room | 312 s | SilenceTimeout | 231 | 1000 | 300160 |
| `6793b0ce` | empty room | 303 s | SilenceTimeout | 282 | 1000 | 300160 |
| `24e58934` | meeting playing | 484 s | None (stopped by hand) | 255 | 1000 | 16160 |

An empty room stops itself at the timeout and not before. A four-person meeting
runs 484 seconds — 184 past the timeout — and its longest quiet run is 16.2
seconds, a margin of 284 seconds rather than the 5 the old rule left.

The meeting had speech in every 60-second bucket of the capture (27–57% of
chunks above the threshold, p50 668, p90 2293). Replaying the shipped rule
offline over that same recording gives a peak quiet run of 16.2 s, which is the
`peakSilentMs` the device reported to the millisecond — the simulator and the
phone agree.

Jank over the 484-second recording: 89 janky frames of 57,693 (0.15%), p50 6 ms,
p90 7 ms, p95 8 ms. Cold start on a fresh install 1615 ms, second launch 1484 ms
(debug build).

#### What the rig cost

Three runs were thrown away before these. VLC reports `state:started` on a live
`AudioTrack` at 14/16 volume and still puts nothing into the room, so three
recordings that looked like the rule failing were the room being genuinely
empty. The first of them was diagnosed only by pulling the WAV off the phone and
reading its level timeline. Playing the corpus from the desktop instead is
under direct control and worked first time.

Two defects came out of that, fixed in `fd22816`: `recording_stopped` fired
twice for one stop, and the three new fields raced to zero between the two
events — so the run that needed explaining reported `noiseFloor=0 speechAt=0
peakSilentMs=0`. The telemetry that existed to answer "why did it stop" was
mute in the first case anyone asked.

## Status: closed
