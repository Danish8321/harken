# Plan — Slice 01: Console Proof

**Goal:** Prove the full pipeline end to end in one C# codebase:
real mic audio → streamed to backend → Azure Speech live captions → transcript
saved to SQLite → Summarize agent (local Ollama/Gemma) runs on the stored transcript.

**Not in scope:** mobile, browser extension, custom prompts, auth, multi-user, UI polish.

> **Superseded in part by slice 03.** This slice's "no authentication, no ownership"
> state is no longer how Harken works. Per ADR-0004, every session now has an owner and
> every endpoint except `GET /health` requires a bearer token; the console client logs
> in before connecting. See `docs/plans/slice-03-identity-and-ownership.md` and the
> README for the current setup. The task history below is left as recorded.

**Stack:** .NET 10 (JIT), ASP.NET Core + SignalR, Azure.CognitiveServices.Speech,
EF Core + SQLite, Microsoft Agent Framework over `IChatClient` (Ollama phase 1),
NAudio console mic capture.

**Projects:** `Harken.Core`, `Harken.Api`, `Harken.Console`.

---

## Task 1 — Solution + project scaffold
- **Files:** `Harken.sln`, `src/Harken.Core/Harken.Core.csproj`,
  `src/Harken.Api/Harken.Api.csproj`, `src/Harken.Console/Harken.Console.csproj`,
  `.gitignore`, `global.json` (pin .NET 10 SDK).
- **Change:** Create solution; Core = classlib, Api = web (`Microsoft.NET.Sdk.Web`),
  Console = console exe. Api → Core, Console → Core references. No AOT settings.
- **Verify:** `dotnet build Harken.sln` succeeds; `dotnet --version` matches global.json.

## Task 2 — Core domain + contracts
- **Files:** `src/Harken.Core/Session.cs`, `TranscriptSegment.cs`,
  `Contracts/CaptionUpdate.cs` (text, isFinal, offset), `Contracts/SessionSummary.cs`,
  `Contracts/ICaptionHub.cs` (client-callback interface).
- **Change:** Plain domain types matching `CONTEXT.md`. `Session` owns ordered
  `TranscriptSegment` list, has StartedAt/EndedAt/Source. Records for DTOs.
- **Verify:** `dotnet build`; types compile; no EF/SignalR/Azure references leak into Core.

## Task 3 — Persistence (EF Core + SQLite)
- **Files:** `src/Harken.Api/Data/HarkenDbContext.cs`,
  `src/Harken.Api/Data/` migration, Api csproj packages.
- **Change:** DbContext with `Sessions`, `TranscriptSegments`. Configure relationship,
  timestamps. Add design-time factory. Generate initial migration.
- **Verify:** Read generated migration — confirm it CREATEs both tables (no destructive
  op, this is initial). `dotnet ef database update` produces `harken.db` with tables.

## Task 4 — Azure Speech streaming wrapper
- **Files:** `src/Harken.Api/Speech/SpeechTranscriber.cs`,
  `src/Harken.Api/Speech/ISpeechTranscriber.cs`, Api csproj (Speech SDK pkg).
- **Change:** Wrap `SpeechRecognizer` with a `PushAudioInputStream` (16kHz/16-bit/mono).
  Expose: push audio chunk, events for partial + final results, start/stop. One instance
  per Session; dispose on stop (guard the leak from ADR-0001).
- **Verify:** Unit/integration test or a temp harness feeds a known WAV chunk stream and
  asserts at least one final result event fires. `dotnet build` + test run.

## Task 5 — SignalR caption hub
- **Files:** `src/Harken.Api/Hubs/CaptionHub.cs`, `src/Harken.Api/Program.cs`.
- **Change:** Hub method accepts `IAsyncEnumerable<byte[]>` audio stream. On connect:
  create Session + SpeechTranscriber. Feed chunks in; on partial result push
  `CaptionUpdate(isFinal:false)` to caller; on final, persist a `TranscriptSegment` AND
  push `CaptionUpdate(isFinal:true)`. On stream end/disconnect: stop recognizer, set
  Session.EndedAt, dispose. Map hub in Program.cs.
- **Verify:** `dotnet build`. Manual: console (Task 7) connects, speaks, sees captions,
  and DB shows a Session with ≥1 TranscriptSegment after stop.

## Task 6 — Summarize agent (Ollama seam)
- **Files:** `src/Harken.Api/Agents/SummarizeAgent.cs`,
  `src/Harken.Api/Program.cs` (keyed `IChatClient` → Ollama), a summarize endpoint
  `POST /sessions/{id}/summary`.
- **Change:** Register keyed `IChatClient` bound to Ollama (`OLLAMA_ENDPOINT`,
  `OLLAMA_MODEL_NAME=gemma3:4b`). `CreateAIAgent(instructions, name)`. Endpoint loads a
  Session's Transcript, runs the agent, returns `SessionSummary`. Persist optional.
- **Verify:** With Ollama running + Gemma pulled, `POST` a stored session id returns a
  non-empty summary. `dotnet build` + one real call.

## Task 7 — Console client (mic → hub → captions → summarize)
- **Files:** `src/Harken.Console/Program.cs`, NAudio pkg.
- **Change:** Capture mic via NAudio (`WaveInEvent`, 16kHz/16-bit/mono). Stream PCM
  chunks to CaptionHub via `IAsyncEnumerable`. Render incoming captions (rewrite line on
  partial, newline on final). Key to stop. After stop, prompt "summarize? y" → call the
  summary endpoint, print result. **Streams real mic chunks — must not read a file.**
- **Verify:** Run backend + console, speak a sentence, watch live captions, stop, get a
  printed summary. Confirm DB persisted the session.

## Task 8 — Config + secrets + README
- **Files:** `src/Harken.Api/appsettings.json`, user-secrets, `README.md`.
- **Change:** Azure Speech key/region + Ollama endpoint/model via user-secrets/env, never
  committed. README: prerequisites (Ollama + `ollama pull gemma3:4b`, Azure Speech
  resource), run steps.
- **Verify:** Fresh clone following README reaches a working end-to-end run. No secret in
  any committed file.

---

## Exit criteria
All 8 tasks build + pass their verify step. One recorded end-to-end run: speak →
live captions → stop → session in SQLite with segments → summary printed. Each task
checked off with what proved it.

---

## Status

- [x] **T0** (added mid-plan) — `.claude/scripts/check.sh` + `test-fast.sh`.
      Proof: `check.sh` green with `-warnaserror`.
- [x] **T1** — solution scaffold. Proof: `dotnet build` 0/0, `dotnet --version` → 10.0.302.
- [x] **T2** — Core domain + contracts. Proof: `check.sh` green, grep confirms Core has
      zero EF/AspNetCore/Azure references.
- [x] **T3** — EF Core + SQLite. Proof: generated migration read before applying (pure
      CREATE, no drop), `dotnet ef database update` ran, `sqlite3 .tables` shows
      `Sessions` + `TranscriptSegments`.
- [x] **T4** — Azure Speech streaming wrapper. Proof: `check.sh` green; cleanup ordering
      reviewed (idempotent `DisposeAsync`, events unsubscribed first, recognizer→
      audioConfig→pushStream dispose order). Live STT deferred — no Azure key in this
      environment.
- [x] **T5** — SignalR caption hub. Proof: `check.sh` green; reviewed full
      `StreamAudio` — each event handler uses its own DbContext scope (no shared
      context across threads), transcriber disposed in `finally`, `EndedAt` persisted
      on completion. Noted residual: `OnFinal` is `async void`, so the very last
      segment save can race the method return — acceptable for a proof, revisit if
      segments ever go missing.
- [x] **T6** — Summarize agent (Ollama seam). Proof: `check.sh` green; reviewed
      `SummarizeAgent.cs` — transcript loads ordered by Offset, empty-transcript guard,
      keyed `IChatClient` → `AsAIAgent` (API name differs from docs' `CreateAIAgent`,
      noted inline in the file; seam intent unaffected). Live call deferred — Ollama
      not installed in this environment.
- [x] **T7** — Console client. Proof: `check.sh` green (whole solution, confirms the
      `SessionStarted` addition to `ICaptionClient`/`CaptionHub` didn't break anything);
      grep confirms only `WaveInEvent` mic capture, no file/synthetic audio source.
- [x] **T8** — Config + secrets + README. Proof: `dotnet user-secrets init` run;
      `appsettings.json` has only empty placeholders (grep confirms 2× `""`); README
      written with prereqs/configure/run/fresh-DB steps; `check.sh` green.

## Still open before this slice is genuinely "done" (not yet verified)

1. **Live run on your machine**: Azure Speech key + Ollama with `gemma3:4b` pulled —
   nobody has run the actual pipeline end to end yet. Everything above is build- and
   code-review-verified, not execution-verified against real services.
2. `test-fast.sh` currently has zero test projects — it's a no-op pass, not real
   coverage. No automated tests exist for the Speech wrapper or the hub.
