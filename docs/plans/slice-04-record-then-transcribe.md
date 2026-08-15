# Plan — Slice 04: Record then transcribe, console end to end

**Goal:** A User records audio with the console client, uploads it, the backend
transcribes it with local Whisper, and the User reads the Transcript and summarizes it.
No live captioning anywhere in the path.

**Driver:** [ADR-0007](../adr/0007-record-then-transcribe.md) (record then transcribe;
clients capture, never infer) and [ADR-0008](../adr/0008-local-whisper-first.md) (local
Whisper is the only MVP 1 Provider, behind a seam).

**Why console first:** the pipeline's real unknown is whether local Whisper is accurate
and fast enough on a 3050. The console can answer that with a fraction of the work of the
phone client, and if the answer is bad the mobile work would have been built on sand.
ADR-0008 makes measuring the transcription-time-to-audio-time ratio a gate on slice 05.

**Not in scope:** the Android client (slice 05), Azure as a second Provider (MVP 2), live
captioning (deferred), multi-language, Silence Timeout and Session Cap (client-side, and
they belong with the phone where battery actually matters).

**Replaces:** [`slice-04-session-limits.md`](slice-04-session-limits.md).

---

## Decisions (agreed 2026-08-15)

1. **Audio encoding: WAV, 16 kHz mono 16-bit PCM.** What Whisper wants natively, so no
   decode step and no encoder dependency. ~115 MB/hour, which is irrelevant on a PC and
   becomes a real question in slice 05 where audio sits on a phone and crosses Wi-Fi.
   Opus is deferred to there, deliberately, so the console slice has one less unknown.
2. **The backend keeps the Recording after transcribing.** Audio is the one artifact that
   cannot be recreated, and re-transcribing with a larger model — or with Azure in MVP 2 —
   is a thing we will plausibly want on recordings that already exist. Costs disk, which
   is cheap and visible. Revisit when disk actually hurts; deleting later is easy, having
   deleted is not.
3. **The client polls for completion.** SignalR is already in the project, but push means
   holding a connection open across a job that takes minutes — exactly the stateful
   long-lived thing ADR-0007 removed. Polling `GET /sessions/{id}` reuses an endpoint that
   already exists and already enforces ownership, and a client that dies mid-job loses
   nothing. Duller and correct.

---

## Task 1 — Remove the live captioning path
- **Files:** `src/Harken.Api/Hubs/CaptionHub.cs` (delete),
  `src/Harken.Core/Contracts/ICaptionClient.cs`, `CaptionUpdate` (delete),
  `Program.cs` (drop `MapHub`), `src/Harken.Console/Program.cs` (drop streaming),
  `tests/Harken.Api.IntegrationTests/` (delete hub tests and doubles).
- **Change:** Delete the streaming path outright — hub, contracts, client code, tests. Per
  ADR-0007 it is removed rather than left dormant; git history keeps it.
- **Note:** `AzureSpeechTranscriber` and `ISpeechTranscriber` stay for now; Task 3 reshapes
  the seam. Deleting the hub must not delete the Azure work.
- **Verify:** `check.sh` green with no references to the removed types; `test-fast.sh`
  green with the hub tests gone and every remaining test still passing. A drop in test
  count is expected and must be stated, not glossed over.

## Task 2 — Recording in the domain
- **Files:** `src/Harken.Core/Session.cs`, `src/Harken.Core/Recording.cs` (new),
  `TranscriptionStatus.cs` (new), `HarkenDbContext`, new EF migration.
- **Change:** A Session gains a Recording (stored file path, byte length, duration,
  content type, uploaded-at) and a transcription status (`Pending`, `Running`,
  `Succeeded`, `Failed`) with a failure reason. **Read the generated migration before
  applying** — new columns must be additive and nullable so no existing row needs a value,
  and any rename must be expressed as a rename.
- **Verify:** the migration quoted in review with zero destructive operations;
  `dotnet ef database update` succeeds; `check.sh`.

## Task 3 — The Provider seam for file transcription
- **Files:** `src/Harken.Api/Speech/ITranscriptionProvider.cs` (new),
  `ISpeechTranscriber.cs` (delete — streaming shape),
  `AzureSpeechTranscriber.cs` (park or delete; MVP 2 rewrites it for batch).
- **Change:** One method taking an audio file and returning ordered segments with
  offsets, plus a name and an availability flag. Language is set explicitly rather than
  inherited from any SDK default — the one surviving decision from ADR-0006.
- **Verify:** `check.sh`; a unit test against a fake provider proving segment ordering and
  that a provider reporting itself unavailable is never selected.

## Task 4 — Whisper provider
- **Files:** `src/Harken.Api/Speech/WhisperTranscriptionProvider.cs` (new),
  `Harken.Api.csproj` (Whisper.net + `Whisper.net.Runtime.Cuda.Windows`),
  `appsettings.json` (model path, language).
- **Change:** Implement the seam over Whisper.net. Model path and language from config, so
  swapping `base` for `medium` is a setting and a restart. Report unavailable when the
  model file is missing — a clear "not configured" beats a crash on first use.
- **New dependency:** Whisper.net 1.9.1 + CUDA runtime. Agreed in ADR-0008; flagged here
  because a new dependency needs an explicit yes.
- **Verify:** `check.sh`; a test proving unavailability when the model path does not exist.
  Real transcription is **not build-provable** — it is the manual step, and its
  time-to-audio-length ratio gets recorded in the slice notes.

## Task 5 — Upload endpoint
- **Files:** `src/Harken.Api/Program.cs`, storage helper.
- **Change:** `POST /sessions` creates a Session from the authenticated identity and
  accepts the audio file. Owner comes from the token, never the request body. Enforce a
  max upload size and an allowed content type. Store outside the repo and outside
  wwwroot. Accept WAV only (decision 1) — reject anything else rather than handing an
  unexpected format to Whisper. The Recording is **kept after transcription** (decision 2),
  so nothing in this slice deletes audio.
- **Security:** `.RequireAuthorization()`. Reject a filename supplied by the client as a
  path — generate the stored name server-side, or a crafted name escapes the storage
  directory. Uploading is the only authenticated step; recording never is (ADR-0007).
- **Verify:** integration tests — unauthenticated upload is `401`; an uploaded Session is
  owned by the caller; an oversized upload is rejected with nothing persisted; a hostile
  filename cannot write outside the storage root.

## Task 6 — Transcription job
- **Files:** `src/Harken.Api/Transcription/` (new), `Program.cs`.
- **Change:** After upload, transcribe in the background: status `Running`, segments
  persisted, status `Succeeded`, or `Failed` with a reason. `GET /sessions/{id}` reports
  status; the client polls it (decision 3) — no push, no connection held open across a job
  that takes minutes. Serialize jobs — one Whisper run at a time, because 4 GB of VRAM
  does not hold two.
- **Verify:** integration tests with a fake provider — status reaches `Succeeded` and
  segments land in order; a throwing provider yields `Failed` with a reason and no partial
  Transcript; a second User polling that id still gets `404`, not a status.

## Task 7 — Console client end to end
- **Files:** `src/Harken.Console/Program.cs`.
- **Change:** Record from the mic to a WAV file, show elapsed time, stop on a keypress,
  upload, poll until done, print the Transcript, offer Summarize. Recording must not
  require a valid token — only the upload step does, so an expired token means "log in and
  retry the upload", never a lost recording.
- **Verify:** `check.sh`; manual run is the real proof and is the first execution of
  Whisper in this project's history. Record the audio length, the transcription time, and
  a subjective accuracy note.

## Task 8 — Docs
- **Files:** `README.md`, `docs/setup.md` (already rewritten), slice notes.
- **Change:** README describes record-then-transcribe, not live captions. Record the
  measured transcription ratio and the model used.
- **Verify:** fresh-eyes read; no doc claims Harken shows live captions.

---

## Exit criteria
`check.sh` and `test-fast.sh` green. A real recording made on the console is transcribed
by local Whisper and summarized by Gemma, with the time ratio written down. No live
captioning code remains. Ownership isolation still holds on every new endpoint —
cross-user access returns `404`, never `403`.

## Carried, unresolved
- Whisper's real speed and accuracy on this hardware are unmeasured until Task 7 runs.
  **This is a gate on slice 05**, not a curiosity: if a one-hour recording takes an hour to
  transcribe, the design needs revisiting before any phone work.
- Duplicate uploads after a retry are not handled. Harmless with one user on a LAN,
  necessary before the phone client, where a flaky connection makes retries routine.
- Silence Timeout and Session Cap are unimplemented. They land in slice 05 with the phone,
  where battery and device storage make them matter.
