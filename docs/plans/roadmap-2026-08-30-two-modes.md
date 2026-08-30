# Roadmap — from on-device-only to two Modes

Written 2026-08-30, out of a grilling session against the shipped state of `master`
(`ab01efa`). It sequences the work implied by
[ADR-0014](../adr/0014-local-first-with-optional-cloud-mode.md) (Local Mode by default,
Cloud Mode opt-in), [ADR-0015](../adr/0015-target-device-floor.md) (device floor) and
[ADR-0016](../adr/0016-on-device-inference-runtime.md) (one native runtime).

## How to read this

Eight slices, strictly sequential — each depends on the one before. Every slice is
separately reviewable and ends at a gate that can fail. A slice is not done because it
compiles; it is done when its gate produces evidence.

Mapping onto the milestone language used in review: slice 12 is M1 (feasibility), slices
13–15 are M2 (working prototype, now with a summary), slice 16 is M3 (reliable core),
slices 17–18 are M4/M5 (intelligence and production).

## Findings register

Everything found during the session, and where it gets closed. Each has a file behind it.

| # | Finding | Evidence | Closed by |
|---|---------|----------|-----------|
| F1 | Summary is dead code on Android — the table, the query and the card exist; nothing ever writes a summary | `SessionDao.kt:149` `replaceSummary` has no caller; `SessionSheet.kt:182` renders a value that is always null | Slice 15 |
| F2 | Unexplained native SIGSEGV in `ggml_vec_dot_f16`, root cause unknown | `.scratch/bug-ggml-sigsegv-vec-dot-f16.md` | Deprioritised by ADR-0015; retest in 12 and 13 |
| F3 | Every performance and quality measurement in the repo was taken on out-of-scope hardware | Bug file; README's timing note | Slice 12 |
| F4 | ADR-0011 cites `0010-azure-batch-transcription-provider.md`, which does not exist; two different documents are called ADR-0010 | `docs/adr/` has no 0010; `src/Harken.Android/docs/adr/0010-expressive-redesign.md` | Slice 11 |
| F5 | Settings copy still describes uploading recordings to a backend | `strings.xml:118` | Slice 11 |
| F6 | README describes a two-client, backend-required product that no longer exists | `README.md` | Slice 11 |
| F7 | `CONTEXT.md` residue: `User`, `Owner` and `Session` ownership language survives from the pre-ADR-0009 auth model | `CONTEXT.md` | Slice 11 |
| F8 | Untracked MediaPipe spike on the runtime ADR-0016 rejected, plus an unrequested dependency and a licence-gated model that has to be hand-pushed over adb. It compiles and builds cleanly — the objection is the runtime and the download story, not the code | `LocalSummarizer.kt`, `DebugSummarySpikeScreen.kt`, `build.gradle.kts`, `AppNav.kt`, `SettingsScreen.kt` | Slice 11 |
| F9 | Integration tests fail intermittently: `Dispose` calls the process-wide `SqliteConnection.ClearAllPools()`, disposing pooled handles belonging to other parallel factories | `CustomWebApplicationFactory.cs`; `ObjectDisposedException: 'SQLitePCL.sqlite3'` | Slice 11 |
| F10 | A crash mid-inference leaves sessions stuck in "Transcribing" forever — no recovery on next launch | Bug file, "secondary gap" | Slice 11 |
| F11 | `minSdk 26` is far below the device floor and forces untested compatibility branches | `app/build.gradle.kts` vs ADR-0015 | Slice 11 |
| F12 | The emulator cannot run this app at all — arm64-only ABI means no x86_64 native library | `abiFilters += "arm64-v8a"` | Accepted constraint, documented in ADR-0015 |
| F13 | Model hosting cannot carry a summarizer: GitHub release assets cap at 2 GB, and the current Whisper URL is unverified | `ModelDownloadManager.kt:145` | Slice 15 |
| F14 | No `openapi.json` is checked in and no client is generated from it, though the repo's own rule requires generated client types | `Program.cs:66` serves `MapOpenApi()` at runtime only | Slice 17 |
| F15 | No `Privacy.md` and no Play Data Safety declaration, while Cloud Mode uploads recorded audio | — | Slice 17, release gate |
| F16 | No evaluation set and no reference-output generator, so no model change can be judged | — | Slice 16 |
| F17 | Nothing records which engine produced a Transcript or Summary, and four engines are coming | `LocalModels.kt` has no provider column | Slice 16 |
| F18 | `Harken.Console`'s role is undeclared since it stopped being a user client | ADR-0014 | Slice 16 |
| F19 | `.scratch/slice-09-followups.md` lists work that is already done (explicit model-download step, Settings re-download) | `OnboardingScreen.kt:212`, `SettingsScreen.kt:93` | Slice 11 |
| F20 | "Chat over findings" had no domain definition | `CONTEXT.md` | Resolved 2026-08-30 — `Agent` widened, `Chat` added |
| F21 | Uncommitted spike work written during the design session (mtimes 2026-08-29 21:24–21:29), never committed or pushed, touching `AppNav.kt` and `SettingsScreen.kt` too | `git status`, file mtimes | Slice 11 |
| F22 | Download validation was lost in the squash merge: `downloadTo` never checks bytes written against `Content-Length`, so a dropped connection renames a truncated file into place as a valid model | `ModelDownloadManager.kt:114-140` vs commit `1972818` | Slice 11 |
| F23 | Three commits stranded on `origin/feat/on-device-transcription`, including the slice-09 manual-check evidence. The branch predates the design merge — it still carries `network/HarkenApi.kt` and lacks `ProtoColors`/`strings.xml`, so merging it would revert the UI work and resurrect the deleted HTTP client | `git diff master origin/feat/on-device-transcription` | Slice 11 |

---

## Slice 11 — Truth pass

**Goal.** Make the documentation, the copy and the tests stop contradicting the code, and
repair the gate, before any native work starts. Nothing here is hard; all of it is
load-bearing, because every later slice is judged against docs that are currently wrong.

**Scope.**

- Rewrite `README.md` for the two-Mode product: Local Mode is the install-and-go default,
  Cloud Mode is opt-in and not yet built, and the Ollama/Whisper setup steps are harness
  setup rather than user setup.
- Fix `strings.xml:118` and sweep the rest of the copy for backend and upload language.
- Resolve the ADR-0010 collision: either write the missing Azure Batch ADR from ADR-0011's
  description of it, or renumber and correct the dangling reference. Decide which — do not
  leave two ADR-0010s.
- Finish `CONTEXT.md`: reconcile `User`, `Owner` and `Session` with the single-user,
  no-auth reality of ADR-0009.
- Fix `CustomWebApplicationFactory.Dispose` — clear this factory's own pool rather than
  the process-wide `ClearAllPools()`.
- Crash recovery: on app start, any session left in a running transcription state is
  marked Failed with a reason, so it can be retried instead of sticking.
- Delete the MediaPipe spike and revert the dependency — ADR-0016 chose another runtime.
- Decide `minSdk`: raise it to match the floor, or record why not.
- Re-apply the lost download validation: compare bytes written against `Content-Length`
  and fail rather than promoting a truncated file to a real model (F22). There is no
  published checksum to verify against, so length is the strongest check available.
- Salvage and retire `origin/feat/on-device-transcription` (F23): cherry-pick the two
  docs commits carrying the slice-09 manual-check evidence, confirm nothing else on it is
  wanted, then delete the branch. Do not merge it — it predates the design merge and would
  revert the UI work and restore the deleted HTTP client.
- Close out `.scratch/slice-09-followups.md`, which is stale precisely because its closure
  lives on that unmerged branch.

**Gate.** `check.sh` green. `test-fast.sh` green ten runs in a row — F9 is order-dependent,
so one green run proves nothing. A reader who knows nothing about the project can read
`README.md` and `CONTEXT.md` and describe the product correctly.

**Not in this slice.** Any native or model work.

---

## Slice 12 — M1 evidence on the reference device

**Goal.** Replace every void measurement with real ones taken on the Nothing Phone (2).
This is the gate the original plan demanded and the project shipped past.

**Scope.**

- A repeatable measurement procedure — fixed audio inputs, recorded conditions, written
  down, so a later run is comparable rather than anecdotal.
- Measure `ggml-base.en.bin` over short, ten-minute and thirty-minute recordings: model
  load time, real-time factor, peak RSS, sustained CPU, thermal throttle onset, battery
  delta, and whether transcription survives a screen-off, backgrounded thirty-minute run.
- Repeat for `small.en`. The floor moved; base.en was chosen for hardware the product no
  longer targets, and `small.en` may now be affordable.
- Attempt the F2 SIGSEGV repro on the reference device, stressed and looped, so the bug
  stops being "unknown on hardware we do not support".

**Gate.** A benchmark table with numbers in it, committed to the repo, and a recommendation
on which Whisper model ships. If base.en cannot hold real-time on a thirty-minute
recording, everything downstream changes and it is better to know now.

**Not in this slice.** Any code change beyond what measurement requires.

---

## Slice 13 — One ggml, one native library

**Goal.** Make room for a second model without creating a second ggml. This is the only
genuinely hard slice, and it ships alone so that a transcription regression here is
unambiguous.

**Scope.**

- Choose the llama.cpp release, then bump vendored whisper.cpp to the release whose ggml
  matches it. Reconcile the JNI bridge and the CMake source list with the newer ggml
  layout.
- Build one `ggml` static target that both `whisper` and `llama` link against, inside one
  JNI shared library.
- Do not call the LLM yet. Link it, prove it initializes, leave it unused.

**Gate.** `check.sh` green, and the slice 12 benchmarks re-run on the reference device with
results within noise of the pre-bump numbers. Transcription regressing is the whole risk of
this slice, so the gate is a measurement, not a compile.

**Not in this slice.** Summarization, model download changes, UI.

---

## Slice 14 — Summarizer benchmark harness

**Goal.** Decide the local model from numbers rather than from a table on the internet.

**Scope.**

- A debug-only harness — reachable only in debug builds, never from real navigation — that
  runs a chosen GGUF over a stored transcript and reports tokens per second, time to first
  token, peak RSS and total latency.
- Benchmark Qwen3-1.7B and Qwen3-4B at Q4_K_M on the reference device, over real
  transcripts of a few minutes and of thirty minutes.
- Judge output quality by hand against your own transcripts: correctness, completeness,
  invented facts, and whether the format holds.
- Measure what a long transcript does to the KV cache. That, not the weights, is what will
  run the device out of memory.

**Gate.** A committed comparison with numbers and quality notes, and a decision on model
and quantization with the reasoning recorded.

**Not in this slice.** Wiring into any user-facing flow.

---

## Slice 15 — Local summarization, wired end to end

**Goal.** The product's headline promise becomes true offline. This is the vertical slice:
persistence, orchestration, model delivery and UI move together.

**Scope.**

- Move model hosting to a project-owned Hugging Face repository, for both Whisper and the
  LLM, and update `ModelDownloadManager`. The Whisper URL is unverified today and the LLM
  cannot live on GitHub Releases at all.
- Model delivery for a multi-gigabyte file: resumable, cancellable, honest about size
  before it starts, and safe to interrupt.
- A summarization job inside `TranscriptionCoordinator`'s one-at-a-time discipline, writing
  through `replaceSummary`, with its own states and failure reasons.
- Long transcripts must not run out of memory. If slice 14 shows a thirty-minute transcript
  does not fit, chunk-then-synthesize belongs in this slice, not deferred.
- Surface it: a real Summarize action, progress, failure with a retry, and the summary
  rendered in the card that is already waiting for it.

**Gate.** Airplane mode, on the reference device: record thirty minutes, transcribe,
summarize, read the result. No network, no account, no configuration. Kill the app
mid-summarization and confirm the session recovers rather than sticking.

---

## Slice 16 — Attribution and evaluation

**Goal.** Make model changes measurable before there are four engines to confuse.

**Scope.**

- Record the producing engine on every Transcript and Summary — Mode, provider, model name
  and quantization — through a real Room migration, never a destructive fallback. Surface
  it where a user would reasonably ask "what made this?".
- A fixed evaluation set of recordings covering meeting, interview, lecture, noisy and
  multi-speaker material.
- `Harken.Console` becomes the evaluation runner: generate reference transcripts and
  reference summaries on the dev machine, and score on-device output against them.
- Declare the backend's role in the README so its presence stops being ambiguous.

**Gate.** Re-running the suite after a model swap produces a comparison, and a deliberately
worse model is visibly worse in the results.

---

## Slice 17 — The Cloud Mode seam

**Goal.** Build the seam and the contract with nothing behind it yet. Flag off is the
shipped default, and the app behaves exactly as it does today.

**Scope.**

- Version the API at `/v1`, check `openapi.json` into the repo as a build artifact, and
  generate the Android client types from it. Hand-written types mirroring the contract are
  a bug, by the repo's own rule.
- The Settings feature flag, off by default, with copy that says plainly what turning it on
  means for data leaving the device.
- Enforce flag-off silence: no probe, no health check, no telemetry. This deserves a test,
  not a promise.
- `Privacy.md`: per Mode, what leaves the device, what is retained remotely, for how long,
  and what deletion actually removes.

**Gate.** Flag off, on a network monitor, with the app exercised fully: zero outbound
requests other than model download. The contract regenerates cleanly and the client
compiles from it.

**Release gate.** Decided 2026-08-30: in Cloud Mode the Recording itself is uploaded
(ADR-0014). That makes `Privacy.md` and a Play Data Safety declaration prerequisites for
*shipping* this slice, not for building it — transmitting recorded audio is declarable
data collection and a privacy policy URL is required at submission.

---

## Slice 18 — Cloud transcription, summary and Chat

**Goal.** Deliver what Cloud Mode is for.

**Scope.**

- Azure AI Foundry behind the existing `IChatClient` seam (ADR-0002).
- Cloud transcription and cloud summarization as alternative providers, attributed per
  slice 16, with no silent fallback in either direction (ADR-0014).
- Chat over a Session's findings.

**Chat, defined** (2026-08-30, now in `CONTEXT.md`): the instruction-driven Agent. The User
writes the instruction — pull out the decisions, draft a follow-up, find what was said
about a topic — and gets a result grounded in that Session. `Agent` was widened to cover
both it and Summarize; what separates them is who writes the instruction.

---

## Open questions

None of these block slices 11 to 16. They are listed so they are answered before the slice
that needs them, rather than during it.

1. **Is Chat Cloud-only, or does Local Mode get it too?** Cloud Mode is where "better
   results" live, but an instruction box over the local model is a small step once slice 15
   has an LLM loaded — and it would keep the Local-Mode-is-the-whole-product rule intact
   for a capability, not just a feature. It costs multi-turn state, a conversation table,
   and KV cache management on device. Decide before slice 15 finalises its scope.
2. **Is Chat grounded in the Transcript, or in the audio itself?** Cloud Mode uploads the
   Recording, so a multimodal model could read the audio directly and hear things a
   transcript loses — tone, overlap, who trailed off. That is a different product from
   "an LLM over text", and it changes what the backend stores and for how long. Decide
   before slice 18.
3. **`minSdk`** — raise to match the ADR-0015 floor, or record why not. Slice 11 decides.

---

## Deferred, deliberately

Not scheduled, listed so they are not reintroduced by accident: VAD-based chunking of audio
before transcription; speaker diarization; source attribution from a summary claim back to
a transcript timestamp and audio position; GPU backends (Vulkan, OpenCL, NPU); multi-ABI
builds; semantic search; cross-session knowledge; sync; collaboration.

Source attribution is the one worth revisiting early once Chat exists — a claim you can tap
back to the audio that supports it is what makes a summary trustworthy rather than merely
convenient.
