# ADR-0011: On-device transcription, backend optional

## Status
Accepted, superseded in part — see **Update (2026-08-28)** below. The Android app now
has no backend dependency at all: decisions 3-5 described a transitional "backend
optional" shape that was never wired to a provider picker in practice (dead code — see
`.scratch/review-slice-09-findings.md` S6/SP1) and has since been deleted outright.
Decisions 1-2 (native on-device whisper.cpp, local Room store) stand unchanged as the
Android app's *only* transcription path.

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
3. ~~**Backend stays optional**, configured the same way it is today
   (`AppSettings.baseUrl` in Settings)...~~ **Superseded.** No provider picker was ever
   built to make Azure selectable, which left the entire upload/backend path (Settings'
   base-URL field, Onboarding's connect step, `HarkenApi`/`NetworkModule`, the
   upload-on-stop flow) permanently unreachable dead code. Rather than build the picker
   this ADR assumed, the Android app now has no backend concept at all: no base-URL
   field anywhere, Onboarding is model-download-only, every recording is local-only.
4. ~~**Summarization stays backend-only**...~~ **Superseded.** With no backend
   reachable from the Android app, the Summarize button/action is deleted, not hidden —
   there is nothing left for it to call. On-device summarization is tracked separately
   in [ADR-0012](0012-full-standalone-local-summarization.md), now scoped as the app's
   *only* summarization path rather than a cloud alternative.
5. ~~**Azure Batch Transcription**...**is unaffected**...~~ **Superseded.** Azure Batch
   Transcription is unreachable from the Android app (it never had a picker to select
   it) and is now formally out of scope for this client — it remains a valid
   backend/console-app path per [ADR-0010](0010-azure-batch-transcription-provider.md),
   just not one the Android app can reach.

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
- Onboarding UX changes: no longer a hard gate — Onboarding is permissions +
  model-download only, no connect step of any kind.
- Cost: $0 to build (existing free libraries/hosting) and $0 to run for the on-device
  default path — unchanged from ADR-0008's cost rationale. The backend/Azure cost
  story in ADR-0010 is unaffected but no longer reachable from this client.
- Summarization is a capability gap for the Android app until ADR-0012 ships on-device
  summarization — there is no cloud fallback any more, per the update above.

## Update (2026-08-28)
Decisions 3-5 (backend-optional shape) are deleted, not just deprioritized: the Android
app's Settings screen, Onboarding, `CaptureViewModel`, and `SessionRepository` no longer
reference a backend URL, `HarkenApi`, `NetworkModule`, or any upload path — all deleted
outright, along with the Summarize button and the backend-polling half of the session
detail sheet. Reason: the provider picker decisions 3-5 assumed was never built, so this
code was unreachable dead weight from the day it shipped (flagged as review findings
S6/SP1 in `.scratch/review-slice-09-findings.md`) rather than a working optional path.
See `.scratch/plan-remove-backend-android.md` for the removal task list.

## Related
[ADR-0008](0008-local-whisper-first.md), [ADR-0010](0010-azure-batch-transcription-provider.md),
[ADR-0012](0012-full-standalone-local-summarization.md)
