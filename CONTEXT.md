# Context — Harken

Glossary for the Harken project. Terms only. No implementation details.

## Session
One captured recording and everything derived from it. Has a start time, an end time, a
Recording, and gains a Transcript once transcribed. A Session exists from the moment
capture starts.

## Recording
The captured audio of a Session, as a file on the device. The only artifact that cannot be
recreated — a lost Recording is a lost Session. Retained after Transcription rather than
discarded, so a Session can be re-transcribed by a better model.

## Transcription
Turning a Recording into a Transcript. Happens on the device after capture ends, never
during it. Takes real time and reports progress, so a Session is always in one of a known
set of states rather than merely "not done yet". May be run again over a retained
Recording.

## Transcript Segment
A timestamped piece of recognized text belonging to a Session. Ordered within its Session
by start offset.

## Transcript
The full stored text of a Session — its ordered Transcript Segments joined. The durable
artifact the user owns. The product's hero.

## Silence Timeout
The period without detected speech after which capture stops on its own. Bounds battery
and device storage. Measured against a noise floor estimated from the recording itself,
not a fixed amplitude, so a quiet room and a loud one both work.

## Session Cap
The maximum duration capture may run. Independent of Silence Timeout — it bounds capture
in a room with constant background noise, which Silence Timeout never ends.

## Auto-stop
Capture ending because a Silence Timeout or Session Cap was reached, rather than because
the user stopped it. Always attributed: a Session states which it was, so an Auto-stop is
never mistaken for a crash.

## Export
Writing every Recording and its Transcript to a folder the user picks. Because nothing is
uploaded anywhere, an Export is the only copy of this data that is not on the phone, and
therefore the only backup.

## Record screen
The capture tab (`RecordScreen`, route `record`). Holds the capture stage and the morphing
record button.

## Library
The list tab (`LibraryScreen`, route `library`). Searchable by title, tag, or transcript
text.

## Session sheet
The modal sheet showing one session's player and transcript (`SessionSheet`).

## Capture stage
The dark ("ink") surface on the Record screen carrying the live waveform. Ink is reserved
for audio surfaces; see ADR-0010.

## Voice 1 / Voice 2
The output of `SpeakerHeuristic`, which flips a voice index on a gap of two seconds or
more. NOT diarization: Whisper base.en returns no speaker data, so the UI never says
"Speaker A" and never uses a name. See ADR-0010.

## Local mirror
The Room database (`harken-local.db`) holding sessions, segments, titles and tags. Named
"mirror" when there was a server to mirror; it is now the only copy.

## Live Update
Android 16's promoted ongoing notification. Harken uses two: recording (chronometer +
Stop) and transcribing (determinate progress). See ADR-0003 and ADR-0010.

---

## Retired terms

Not part of the current model. Listed so they are not reintroduced by accident, and
because each was a real concept the code still carries traces of.

- **User**, **Owner** — an account model. Removed by
  [ADR-0009](docs/adr/0009-remove-auth-for-mvp1.md): one person, one phone, nothing to
  authenticate and nothing to isolate from.
- **Upload** — moving a Recording to a backend. There is no backend
  ([ADR-0011](docs/adr/0011-on-device-transcription.md),
  [ADR-0015](docs/adr/0015-retire-the-dotnet-tier.md)). The `pendingUploadPath` column
  still carries the name and now simply holds the audio path.
- **Provider** — an interchangeable transcription engine chosen by the user. There is one
  engine, on the device (ADR-0011).
- **Source** — where a Session's audio came from, declared by a client. There is one
  client and one microphone.
- **Agent**, **Summary** — an AI worker over a Transcript, and its output. Never shipped
  on the phone; the `summaries` table and the `hasSummary` column are the leftovers.

## Deferred terms

Live-recognition concepts. They will return if live captioning does
([ADR-0007](docs/adr/0007-record-then-transcribe.md)).

- **Caption** — live on-screen text as it forms during capture.
- **Partial Result** — an in-progress recognition, revised as more audio arrives.
- **Final Result** — a stabilized recognition the engine will not revise.

Under record-then-transcribe there is no live recognition: a Recording produces Transcript
Segments directly, and nothing the user sees is ever provisional.
