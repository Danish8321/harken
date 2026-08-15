# 7. Record then transcribe, not live captioning

Date: 2026-08-15

## Status
Accepted. Supersedes the streaming parts of [ADR-0001](0001-backend-centric-streaming.md)
and, for the MVP, all of [ADR-0006](0006-session-limits-and-cost-control.md).

## Context
Harken was built around live captioning: a client streams audio chunks to the backend
over SignalR, the backend holds an open recognizer, and Partial and Final Results flow
back as the user speaks. That works — slices 01 to 03 ship it and the tests pass — but it
was chosen before cost was ever discussed, and cost is the binding constraint on this
project. It is a personal-use app and a vehicle for learning AI integration, not a
product with a budget.

Live recognition is the expensive shape. The meter is wall-clock time a recognizer stays
open, so a silent hour costs the same as an hour of dense speech, and the only stop
signal is a user remembering to press Stop — ADR-0003 deliberately removed the other one.
ADR-0006 exists entirely to bound the damage that shape causes: two timers, a sync
contract, three new hub messages, and per-Session server-side scheduling. That is a
significant amount of machinery whose only purpose is to stop an architecture from
running up a bill.

Live is also not what the product is for. The hero artifact is the stored Transcript and
what Agents do with it. Watching text appear during a lecture you are already listening
to is the least valuable thing Harken does.

A separate fact makes the alternative cheap: **batch transcription costs roughly $0.18
per audio hour against ~$1.00 for real-time** — about a fifth — and a local Whisper model
on hardware already owned costs nothing at all. Neither is available to a streaming
design.

## Decision
**The MVP records first and transcribes afterwards. No live captioning.**

A Recording is captured to a file on the client, uploaded when the client has network and
credentials, and transcribed by the backend as a job. The user sees the Transcript when
it is ready, not as it forms.

**Clients capture; they do not infer.** The phone is a capture device. All transcription
and all Agent work happens on the backend. Verified while making this decision:
Whisper.net publishes no Android runtime, and there is no maintained on-device Gemma
binding for .NET on Android, so on-device inference would mean writing native bindings —
a large amount of work in the least interesting part of the problem. Beyond the bindings,
useful Whisper models do not fit comfortably on a phone: `tiny` and `base` run near
realtime but are markedly less accurate, which defeats the point of choosing local
Whisper for quality. The phone would also sustain minutes of inference per lecture, and
be slower doing it than the GPU already sitting on the desk.

**Recording never requires authentication; uploading does.** Capture must work with a
dead network, an expired token, and no backend reachable at all — a lecture does not wait
for a login screen. Auth is enforced at upload, which is the first moment a Recording
becomes someone's data.

**The live path is removed, not left dormant.** Streaming audio over the hub, Partial
Results, and the live caption UI come out of the codebase. They remain in git history and
return when live captioning is designed properly rather than inherited.

## Consequences
- **Cost drops to zero for the MVP** and to roughly a fifth of real-time when Azure comes
  back in MVP 2. The entire cost-control apparatus of ADR-0006 becomes unnecessary as a
  cost control.
- **ADR-0006's two limits survive, with a different justification.** Silence Timeout and
  Session Cap now bound *battery and storage on the phone*, not spend. A forgotten
  recording no longer costs money — it costs a flat battery and a large file. Worth
  keeping, no longer urgent, and no longer needing server-side enforcement, because the
  server is not holding anything open. This moves them from the server to the client and
  makes them much smaller work.
- **Offline capture works**, which live captioning could never do. This is a genuine
  feature win, not just a cost dodge: a basement lecture hall with no signal is a normal
  situation.
- **Latency becomes minutes instead of milliseconds.** A one-hour recording takes real
  time to upload and real time to transcribe. Acceptable, because nobody is waiting on it
  during the lecture, but it means the app needs honest progress reporting where it
  previously had a live stream to show.
- **New failure modes replace old ones.** Partial uploads, interrupted transfers, a job
  that fails halfway, and duplicate uploads after a retry all have to be handled. In
  exchange, the stateful-recognizer resource leak that ADR-0001 flagged as a real risk
  disappears entirely.
- **Storage moves to the phone, and becomes the standing cost.** Audio sits on the device
  until uploaded, and is retained on the backend afterwards so a Session can be
  re-transcribed by a better model or a different Provider. Backend disk therefore grows
  with every hour ever recorded. Encoding format is the lever: ~115 MB/hour as WAV against
  ~10 MB/hour as Opus. The console slice records WAV, since Whisper wants it natively and
  a PC does not care; the phone slice has to decide properly.
- **Working, tested code is deleted.** Hub audio streaming and the caption UI go. This is
  deliberate: dormant code that no path exercises rots and misleads.
- **The Speech provider seam survives intact.** `ISpeechTranscriber` was already the
  contract between the hub and Azure; the record-then-transcribe successor keeps that
  shape, which is what lets MVP 2 add Azure without touching anything above it
  ([ADR-0008](0008-local-whisper-first.md)).

## Alternatives considered
- **Keep live captioning and just add the limits (ADR-0006 as written).** Bounds the cost
  but does not remove it, and spends a whole slice on machinery that exists only to
  protect against an architecture chosen by accident. Rejected once cost became the
  stated primary constraint.
- **Live captioning with local Whisper, to get live for free.** Whisper is not a streaming
  model: fixed 30-second windows, no native concept of a partial, repeated redundant
  decoding of overlapping audio, an external voice-activity detector needed to find
  boundaries, hallucination on truncated input, and a reconciliation scheme
  (LocalAgreement-2 or similar) to stop the display flickering between revisions. It also
  contends for the same 4 GB of VRAM as the summarizer. The hardest quadrant available,
  for the least valuable feature.
- **Both modes, configurable from day one.** Doubles the surface area of the first thing
  built and means neither path gets proven properly. Live returns as a deliberate later
  decision instead.
