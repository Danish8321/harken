# Context — Harken

Glossary for the Harken project. Terms only. No implementation details.

## User
A person with an account in Harken. Owns Sessions. Users are isolated from one
another: a User can never read another User's Sessions, Transcripts, or Summaries.

## Owner
The User a Session belongs to — established when the Session is created, from the
authenticated identity, and never reassigned. Transcript Segments inherit ownership
through their Session.

## Session
One captured recording and everything derived from it. Belongs to exactly one Owner.
Has a start time, an end time, a Source, and a Recording. Gains a Transcript once
transcribed, and may gain a Summary. A Session exists on the client from the moment
capture starts, before it has an Owner.

## Recording
The captured audio of a Session, as a file. Created on the client, held there until
Uploaded, and the only artifact that cannot be recreated — a lost Recording is a lost
Session. Capture never requires the User to be authenticated or online. Retained after
Transcription rather than discarded, so a Session can be re-transcribed by a different
Provider or a better model.

## Upload
Moving a Recording from the client to the backend. The first point at which a Session
acquires an Owner, and therefore the point where authentication is required. May be
retried; a Recording that has been Uploaded is not deleted from the client until the
backend confirms it holds it.

## Transcription
Turning a Recording into a Transcript. Happens on the backend after Upload, never on
the client and never during capture. Takes real time and reports progress, so a Session
is always in one of a known set of states rather than merely "not done yet". May be run
again over a retained Recording.

## Provider
The engine that performs Transcription. Interchangeable: local Whisper, or a cloud
service. Which Providers exist is declared by the backend; the User selects among
those, and can never select one the backend has no credentials for.

## Transcript Segment
A timestamped piece of recognized text belonging to a Session. Ordered within its
Session by start offset.

## Transcript
The full stored text of a Session — its ordered Transcript Segments joined. The
durable artifact the User owns and runs Agents over. The product's hero.

## Agent
An AI worker that consumes a Transcript and produces a derived output. Phase 1 has
one Agent: Summarize. Runs on demand against a stored Transcript.

## Summary
The output of the Summarize Agent over a Transcript.

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
Where a Session's audio comes from — declared by the client when capture starts, not
assumed by the backend. Mic (console, mobile) or system/tab audio (browser extension).

## Record screen
The Android client's capture tab (`RecordScreen`, route `record`). Formerly "Capture".
Holds the capture stage and the morphing record button.

## Library
The Android client's list tab (`LibraryScreen`, route `library`). Formerly "Recordings",
which sat confusingly next to the tab that records.

## Session sheet
The modal sheet showing one session's player, summary and transcript (`SessionSheet`).
Formerly "Session detail screen".

## Capture stage
The dark ("ink") surface on the Record screen carrying the live waveform. Ink is
reserved for audio surfaces; see ADR-0010.

## Voice 1 / Voice 2
The output of `SpeakerHeuristic`, which flips a voice index on a gap of two seconds or
more. NOT diarization: Whisper base.en returns no speaker data, so the UI never says
"Speaker A" and never uses a name. See ADR-0010.

## Local mirror
The client-side Room database (`harken-local.db`) holding sessions, segments and
summaries, plus local-only titles and tags the backend has no field for.

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

All three are live-recognition concepts. Under record-then-transcribe there is no live
recognition: a Recording produces Transcript Segments directly, and nothing the User
sees is ever provisional.
