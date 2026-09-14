# ADR-0011: On-device transcription, backend optional

## Status
Accepted. Decisions 3–5 were overtaken by later work — see
[What actually shipped](#what-actually-shipped-2026-09-14), below. The decision text is as
written on 2026-08-25 and is not corrected in place.

## Context
Today, "install and run Harken" means: install the APK, then separately stand up
`Harken.Api` (a .NET server, run on a dev machine, home server, or cloud host) and type
its URL into a mandatory Onboarding step before the app is usable at all. There is no
"just install and go" path.

[ADR-0008](0008-local-whisper-first.md) chose Whisper.net (a .NET binding of whisper.cpp)
running *server-side* as the default transcription Provider, specifically to avoid a
per-call cloud cost. ADR-0010 added Azure Batch Transcription as an explicit second,
opt-in Provider, selected per Session and resolved by `TranscriptionBackgroundService` —
still server-side, still requires a reachable backend for every Session regardless of
which Provider is chosen.

This ADR asks: can the free, default transcription path work with **zero backend**, and
keep the backend purely as an **optional upgrade** (cloud transcription via Azure,
AI-generated summaries)? Full architectural decisions were grilled interactively; the
alternatives considered below are what survived that process.

## Decision
Move the *default* transcription path fully on-device, native to the Android app.
Recordings transcribed this way never touch a network:

1. **Native whisper.cpp on-device**, vendored as source and built via our own NDK/CMake
   JNI layer, not a third-party prebuilt binding. The two Maven-published community
   options found (`ffmpegkit-maintained/whisper`, `GiviMAD/whisper-jni`) are either an
   unverified fork with a messy provenance or JNA-based for desktop JVM, not Android —
   neither is trustworthy enough to add as a dependency. Vendoring is more setup work but
   zero third-party trust risk, and mirrors how the backend already depends directly on
   whisper.cpp (via Whisper.net, ADR-0008) rather than an unvetted middle layer. Model
   file (ggml-tiny/base) is **not** bundled in the APK; it downloads once, on first run,
   from a GitHub Releases asset on this repo (free, versioned, no new hosting dependency).
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
5. **Azure Batch Transcription** (ADR-0010) is unaffected: still backend-mediated, still
   requires a configured `baseUrl` to be selectable at all (the provider picker already
   degrades unavailable-or-unreachable choices, per that ADR).

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
- New build surface: NDK/CMake toolchain in the Android module, vendored whisper.cpp
  source, and a hand-written JNI layer — no new third-party dependency, but real
  native-build maintenance burden (arch ABIs, toolchain upgrades) that didn't exist
  before in this app.
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

## What actually shipped (2026-09-14)

Decisions 1 and 2 hold: whisper.cpp is vendored and built through our own JNI layer, the
model downloads from a GitHub Releases asset, and Room is the only session store. Decisions
3, 4 and 5 did not survive contact with the slices that followed. Recorded here rather than
corrected above, because none of them was reversed by a decision — each was overtaken.

**3. Onboarding.** There is no connect step, skippable or otherwise. `OnboardingScreen` is
two steps — what the app is, then the model download — and `AppSettings.baseUrl` is gone
along with the rest of the client's backend seam. The mandatory gate this decision set out
to remove is removed; the optional step it was to be replaced by was never built, because
[ADR-0015](0015-retire-the-dotnet-tier.md) deleted the server there would have been to
connect to.

**4. Summarization.** No Summarize action exists in the app, hidden or otherwise, so there
is nothing for "hidden when no backend is configured" to describe.
[ADR-0012](0012-full-standalone-local-summarization.md) is still Proposed — deferred, so
summaries remain a capability gap, as that ADR's own status says.

**5. Azure Batch Transcription.** No provider picker was ever built.
`AppSettings.transcriptionProvider` was written by nothing, and a grep of
`src/Harken.Android/app/src/main` now finds no occurrence of `transcriptionProvider`,
`Azure`, `baseUrl` or `Summarize` at all. Azure transcription is not "unaffected" — it is
unreachable, and has been since this slice shipped.

**ADR-0010 was never written.** This ADR cites it three times as an accepted decision and
`docs/plans/slice-09-on-device-transcription.md` cites it once more, but no such file has
existed in this repository's history. The links to it were removed on 2026-09-14; the
citations themselves are left as written.

One more thing changed that this ADR did not speak to: transcription is not automatic.
`6300f16` made it an explicit action in the Library, so a recording is captured, stored, and
transcribed only when the user asks (ADR-0007's record-then-transcribe shape, applied to the
on-device path).

## Related
[ADR-0008](0008-local-whisper-first.md), ADR-0010 (never written),
[ADR-0012](0012-full-standalone-local-summarization.md)
