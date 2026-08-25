# ADR-0011: On-device transcription, backend optional

## Status
Accepted

## Context
Today, "install and run Harken" means: install the APK, then separately stand up
`Harken.Api` (a .NET server, run on a dev machine, home server, or cloud host) and type
its URL into a mandatory Onboarding step before the app is usable at all. There is no
"just install and go" path.

[ADR-0008](0008-local-whisper-first.md) chose Whisper.net (a .NET binding of whisper.cpp)
running *server-side* as the default transcription Provider, specifically to avoid a
per-call cloud cost. [ADR-0010](0010-azure-batch-transcription-provider.md) added Azure
Batch Transcription as an explicit second, opt-in Provider, selected per Session and
resolved by `TranscriptionBackgroundService` — still server-side, still requires a
reachable backend for every Session regardless of which Provider is chosen.

This ADR asks: can the free, default transcription path work with **zero backend**, and
keep the backend purely as an **optional upgrade** (cloud transcription via Azure,
AI-generated summaries)? Full architectural decisions were grilled interactively; the
alternatives considered below are what survived that process.

## Decision
Move the *default* transcription path fully on-device, native to the Android app.
Recordings transcribed this way never touch a network:

1. **Native whisper.cpp on-device**, via a maintained Android JNI binding (not vendoring
   whisper.cpp ourselves — reuse over inventing our own NDK build). Model file
   (ggml-tiny/base) is **not** bundled in the APK; it downloads once, on first run, from
   a GitHub Releases asset on this repo (free, versioned, no new hosting dependency).
2. **A local session store** (Room) mirrors the shape of the backend's `Session` /
   `TranscriptSegment` tables closely enough for the Library screen to render local and
   remote sessions the same way. A locally-transcribed recording gets a fully-formed
   local Session — Library, playback, and reading the transcript all work with no
   backend configured, ever.
3. **Backend stays optional**, configured the same way it is today (`AppSettings.baseUrl`
   in Settings), but Onboarding's mandatory "set a backend URL to continue" gate is
   removed. Onboarding becomes: a one-time, skippable model-download step, then an
   optional "connect a backend" step that explains what it unlocks (cloud transcription
   via Azure, AI summaries) rather than blocking first use.
4. **Summarization stays backend-only** (`SummarizeAgent`/`IChatClient`, unchanged) — it
   is out of scope for on-device in this slice ([ADR-0012](0012-full-standalone-local-summarization.md)
   covers that as Phase 2). The Summarize action is hidden/disabled on a session when no
   backend is configured, rather than shown and failing — a session with no backend
   reachable should never present a button that's guaranteed to error.
5. **Azure Batch Transcription** ([ADR-0010](0010-azure-batch-transcription-provider.md))
   is unaffected: still backend-mediated, still requires a configured `baseUrl` to be
   selectable at all (the provider picker already degrades unavailable-or-unreachable
   choices, per that ADR).

## Alternatives considered
- **Bundle the existing ASP.NET `Harken.Api` process inside the Android app** (e.g. via
  .NET-on-Android/MAUI) instead of a native rewrite. Rejected: ships a .NET runtime
  inside the APK, is a nonstandard app-lifecycle shape for Android, and reuses server
  code that was never designed to run inside a foreground app process.
- **Keep transcription server-side, just make backend setup friendlier** (LAN
  auto-discovery, gentler onboarding copy). Rejected: doesn't satisfy "installed and good
  to go" — a backend process is still mandatory before the app does anything.
- **Bundle the model inside the APK** instead of downloading it on first run. Rejected:
  adds 75MB+ to every install for a file most users only need once; a one-time download
  keeps the APK small at the cost of one setup step, which is an acceptable trade for
  "install and go."
- **Require backend for all Sessions** (audio still uploads, only the whisper compute
  moves) — considered as a smaller slice, but rejected because it doesn't make the app
  actually standalone: Library/history would still be empty without a reachable backend.

## Consequences
- New dependency: an Android whisper.cpp JNI binding (exact artifact chosen at
  implementation time; a maintained community AAR, not a vendored build).
- New local schema: a Room database mirroring `Session`/`TranscriptSegment` shapes,
  independent of `HarkenDbContext` — no shared migration story between the two stores in
  this slice (see ADR-0012 for whether/how they'd ever reconcile).
- Onboarding UX changes: no longer a hard gate; needs new copy explaining the
  optional backend connection and what each of cloud transcription / summaries requires.
- Cost: $0 to build (existing free libraries/hosting) and $0 to run for the on-device
  default path — unchanged from ADR-0008's cost rationale. Cloud options remain exactly
  as priced in ADR-0010.
- Summarization remains a capability gap for backend-less users until ADR-0012 (Phase 2)
  is implemented, if ever.

## Related
[ADR-0008](0008-local-whisper-first.md), [ADR-0010](0010-azure-batch-transcription-provider.md),
[ADR-0012](0012-full-standalone-local-summarization.md)
