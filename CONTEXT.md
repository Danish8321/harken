# Context — Harken

Glossary for the Harken project. Terms only. No implementation details.

## User
The person using Harken. There is exactly one and it is implicit: no accounts, no login,
nothing to authenticate (ADR-0009). Separation between people is a property of the device
they hold, not of the software.

## Session
One captured recording and everything derived from it. Has a start time, an end time, a
Source, and a Recording. Gains a Transcript once transcribed, and may gain a Summary.
Exists from the moment capture starts.

## Recording
The captured audio of a Session, as a file. Created on the device and the only artifact
that cannot be recreated — a lost Recording is a lost Session. Capture never requires the
User to be online, whatever the Mode. Retained after Transcription rather than discarded,
so a Session can be re-transcribed by a better Provider or a better model later.

## Transcription
Turning a Recording into a Transcript. Happens after capture ends, never during it
(ADR-0007). Where it runs is decided by the Mode: on the device in Local Mode, remotely
in Cloud Mode. Started explicitly by the User rather than automatically when capture
stops, and at most one on-device Transcription runs at a time. Takes real time and
reports progress, so a Session is always in one of a known set of states rather than
merely "not done yet". May be run again over a retained Recording.

## Provider
The engine that performs Transcription or Summarization. Which Provider runs follows from
the Mode, not from a separate picker: Local Mode always uses the on-device Provider, Cloud
Mode uses the remote one. Every output states the Provider that produced it, so a
Transcript or Summary is never of unknown origin.

## Mode
Where a Session's Transcription and Summarization run. Two exist, and the User chooses
(ADR-0014):

- **Local Mode** — the default and the whole product on its own. Everything runs on the
  device; every core workflow completes with no network, no account and no configuration.
- **Cloud Mode** — opt-in, off by default, enabled by a Settings feature flag. Buys expert
  Transcription, expert Summarization, and Chat. Never automatic: the app never falls back
  from one Mode to the other on its own, in either direction.

## Upload
Moving a Session's data from the device to the backend. Exists only in Cloud Mode; in
Local Mode nothing is ever uploaded and a Recording never leaves the device.

## Transcript Segment
A timestamped piece of recognized text belonging to a Session. Ordered within its
Session by start offset.

## Transcript
The full stored text of a Session — its ordered Transcript Segments joined. The
durable artifact the User owns and runs Agents over. The product's hero.

## Agent
An AI worker that consumes a Session's content and produces a derived output. Runs on
demand, never automatically. Two exist:

- **Summarize** — one fixed instruction, no input from the User.
- **Chat** — the User supplies the instruction.

## Summary
The output of the Summarize Agent over a Transcript.

## Chat
The instruction-driven Agent. The User asks for something in their own words — pull out
the decisions, draft a follow-up, find what was said about a topic — and gets a result
grounded in that Session rather than the one fixed Summary. What separates Chat from
Summarize is who writes the instruction, not how long the answer is.

## Silence Timeout
The period without detected speech after which capture stops on its own. Bounds
battery and device storage, not cost. Enforced on the client, since capture happens
there. Configurable per Session.

## Session Cap
The maximum duration capture may run, chosen by the User before it starts. Independent
of Silence Timeout — it bounds capture in a room with constant background noise, which
Silence Timeout never ends. May be declined, in which case only Silence Timeout applies.

## Auto-stop
Capture ending because a Silence Timeout or Session Cap was reached, rather than
because the User stopped it. Always attributed: a Session states which of the three it
was, so an Auto-stop is never mistaken for a crash.

## Source
Where a Session's audio comes from, declared when capture starts rather than inferred
later. Today every Source is a microphone.

## Record screen
The Android client's capture tab (`RecordScreen`, route `record`). Formerly "Capture".
Holds the capture stage and the morphing record button.

## Library
The Android client's list tab (`LibraryScreen`, route `library`). Formerly "Recordings",
which sat confusingly next to the tab that records.

## Session sheet
The modal sheet showing one Session's transcript, summary and details (`SessionSheet`).
Formerly "Session detail screen".

## Capture stage
The dark ("ink") surface on the Record screen carrying the live waveform. Ink is
reserved for audio surfaces; see ADR-0010.

## Voice 1 / Voice 2
The output of `SpeakerHeuristic`, which flips a voice index on a gap of two seconds or
more. NOT diarization: Whisper base.en returns no speaker data, so the UI never says
"Speaker A" and never uses a name. See ADR-0010.

## Local store
The Room database on the device (`harken-local.db`) holding Sessions, Transcript
Segments, Summaries, titles and tags. It is the source of truth, not a cache of one:
nothing reconciles it against a server. Called the "local mirror" while it still
mirrored a backend.

## Live Update
Android 16's promoted ongoing notification. Harken uses two: recording (chronometer +
Stop) and transcribing (determinate progress). See ADR-0003 and ADR-0010.

---

## Deferred terms

Not part of the current model. Listed so they are not reintroduced by accident, and
because they will return if live captioning does (ADR-0007).

- **Caption** — live on-screen text as it forms during capture.
- **Partial Result** — an in-progress recognition, revised as more audio arrives.
- **Final Result** — a stabilized recognition the engine will not revise.
- **Owner** — the User a Session belonged to, established from an authenticated identity
  and never reassigned. Removed with authentication itself (ADR-0009). It returns only if
  Cloud Mode turns out to need accounts, which ADR-0014 does not assume.

All three are live-recognition concepts. Under record-then-transcribe there is no live
recognition: a Recording produces Transcript Segments directly, and nothing the User
sees is ever provisional.
