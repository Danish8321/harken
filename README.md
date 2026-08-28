# Harken

Record → transcript → AI summary. MVP 1 is single-user and unauthenticated: one
implicit user, no accounts, no login, every endpoint open on the LAN
([ADR-0009](docs/adr/0009-remove-auth-for-mvp1.md), which supersedes ADR-0004).
See `docs/plans/` for slice scope, `CONTEXT.md` for glossary, `docs/adr/` for the
decisions behind the shape of this thing.

> **Record-then-transcribe, not live captions.** The live captioning path was deleted in
> [ADR-0007](docs/adr/0007-record-then-transcribe.md); Harken now records to a file,
> uploads it, and transcribes it in the background — there is no word-by-word caption
> stream. Transcription runs on local Whisper ([ADR-0008](docs/adr/0008-local-whisper-first.md)):
> no Azure, no cloud account, no cost. Measured on this project's dev machine (RTX 3050
> 4 GB, CPU fallback — no CUDA toolkit installed) with the `ggml-base.en.bin` model:
> a 4–5 second clip transcribed in 3–5 seconds, roughly real-time. Accuracy on longer or
> quieter audio is still unmeasured — see `docs/plans/slice-04-record-then-transcribe.md`,
> "Carried, unresolved".

## What is this

Harken turns spoken audio — a meeting, a lecture, a voice memo, a conversation — into
searchable text and a short AI summary, with zero cloud dependency and zero cost per
recording. Point a phone or a mic at a conversation, hit record, and later read what was
said instead of re-listening to it.

- **Capture** from either client: the Android app (phone as a portable recorder — a
  foreground service keeps recording through a locked screen) or the console app (PC mic).
- **Transcribe** locally via Whisper (`ggml-base.en.bin`), running on this machine — no
  audio ever leaves the LAN, no per-minute billing, no API key.
- **Summarize** on request via a local Gemma model through Ollama — same story, no cloud
  account.
- **Review** transcripts and summaries from either client, whenever transcription finishes
  — recording and playback of results are decoupled; you don't wait around for a summary.

It is intentionally single-user for MVP 1: one implicit user, no accounts, no auth, meant
for one person's own recordings on their own LAN (ADR-0009). The design bet is that most
of the friction in "I recorded something, now what" is turnaround time and cost, not
accuracy at scale — so the whole pipeline is built to run entirely on hardware someone
already owns.

## Prerequisites

**Setting up a machine from scratch? Start at [`docs/setup.md`](docs/setup.md)** — it
covers the Whisper model, Ollama, generating and storing the secrets, and proving each
piece works before you run anything. This section is the short list; that doc is the
go-to.

- .NET 10 SDK (10.0.302 — pinned in `global.json`).
- A Whisper GGML model file (e.g. `ggml-base.en.bin` from
  https://huggingface.co/ggerganov/whisper.cpp/tree/main), and its path set via
  `Whisper:ModelPath` (see Configure secrets, below). Without it the API starts fine but
  every transcription fails with "Whisper model not found".
- **Ollama** running locally, with a Gemma model pulled:
  ```
  ollama pull gemma3:4b
  ```
  Phase 1 summarize agent talks to Ollama, not Azure — see ADR-0002. Summarize is
  optional — recording and transcription work without it.
- A working microphone.

## Configure secrets

Never commit real keys. There are no required secrets — MVP 1 has no cloud
credentials and no signing key (ADR-0009): everything runs on local Whisper and a
local Gemma model (ADR-0008).

```
dotnet user-secrets set "Whisper:ModelPath" "<path to ggml-base.en.bin>" --project src/Harken.Api
```

Ollama endpoint/model default to `http://localhost:11434` / `gemma3:4b`
(`src/Harken.Api/appsettings.json`, section `Ollama`) — override there or via
`OLLAMA_ENDPOINT`/`OLLAMA_MODEL_NAME`-style config if your setup differs.

## Run

Terminal 1 — backend (applies no migrations automatically; already applied during
build — see below if starting fresh):

```
dotnet run --project src/Harken.Api
```

Note the port printed (see `src/Harken.Api/Properties/launchSettings.json`,
currently `http://localhost:5057`).

Terminal 2 — console client:

```
dotnet run --project src/Harken.Console
```

No sign-in step — offers straight away:

- **R** — record from the mic (ENTER to stop), upload, poll until transcribed, print the
  transcript, offer to summarize.
- **L** — list sessions and summarize one.
- **Q** — quit.

## API

Every endpoint is anonymous (ADR-0009) — MVP 1 has one implicit user, so there is
nothing to authenticate.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | liveness — `{"status":"ok"}` |
| `POST /sessions` | upload a WAV recording (`audio` file, `source` field, optional `recordingId`); starts transcription in the background |
| `GET /sessions` | sessions, newest first, metadata only |
| `GET /sessions/{id}` | one session + its ordered transcript segments + `TranscriptionStatus` (poll this) |
| `POST /sessions/{id}/summary` | generate (or re-read) the stored summary |

```
curl http://localhost:5057/sessions
```

`recordingId` is optional and makes upload idempotent: a client that generates one at
record-start can re-send a recording after a dropped connection and get **200** with the
session that already exists, rather than **201** and a duplicate. Clients that omit it
(the console) are unaffected. A malformed value is a 400.

## Fresh database

If `src/Harken.Api/harken.db` doesn't exist yet:

```
dotnet ef database update --project src/Harken.Api
```

## Verification gates

- `.claude/scripts/check.sh` — full solution build, warnings-as-errors.
- `.claude/scripts/test-fast.sh` — automated tests (excludes anything tagged
  Manual/E2E — live Azure/Ollama calls aren't run automatically).

---

## Mobile (Android)

A fully standalone capture-and-transcribe device: record → on-device transcript, no
server involved (ADR-0011). Native Kotlin + Jetpack Compose (`src/Harken.Android`) —
chosen over an earlier MAUI client for direct, bridge-free access to `AudioRecord` and
the foreground service.
See `docs/adr/0003-mobile-foreground-service.md` for why recording runs as a foreground
service.

### Prerequisites

- Android SDK (API 36) + JDK 17. Point `src/Harken.Android/local.properties`
  (gitignored) at your SDK, e.g. `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.
- A device to run on: a physical Android phone with **USB debugging enabled**
  (Settings → About phone → tap Build number ×7 → Developer options → USB debugging),
  or an Android Studio emulator.
- No backend needed — the Android app is fully standalone (ADR-0011). The backend
  project is only for the console client (see above).

### Configure

First launch runs a 3-step onboarding wizard: permissions, a recording explainer, and a
one-time on-device Whisper model download. Nothing else to configure — there is no
backend URL, no account, no connect step. The model can be re-downloaded later from
**Settings** if it's ever cleared.

### Run

```
cd src/Harken.Android
./gradlew installDebug
```

then launch it from the phone's app drawer, or open `src/Harken.Android` in Android
Studio and hit Run.

Permissions are requested at the point of first **Record**, not at launch — a prompt means
something to someone who just tapped Record and nothing to someone who just opened the app.
Denying the microphone is handled: the app says what is blocked and where to grant it,
rather than failing silently. Notification permission (Android 13+) is asked for too but
never blocks recording.

### Use

No sign-in step (ADR-0009) — the app opens straight on **Record**, one of three tabs
reachable from a bottom navigation bar: **Record**, **Recordings**, **Settings**.

- **Record → Stop.** Audio is captured to a WAV file in the app's private storage and
  saved locally as "Recorded"; nothing ever leaves the device. Transcription is a
  separate, explicit **Transcribe** action the user taps from the Library, run entirely
  on-device via whisper.cpp (ADR-0011).
- **Session detail screen** (opened by tapping a row in Recordings): shows the
  transcript once on-device transcription finishes. There is no Summarize action —
  summarization is out of scope for the Android app until ADR-0012 ships on-device
  summarization; there is no cloud fallback.
- **Recordings list**: **Delete** hides a recording from the list but keeps the row and
  audio file (soft delete). **Delete permanently** asks for confirmation, then removes
  the row and the WAV file from disk for good.

### Recording limits and storage cost

| | Value | Why |
| --- | --- | --- |
| Format | 16 kHz / 16-bit / mono WAV | What Whisper wants natively. No encoder dependency. |
| Storage | **~115 MB per hour** | The cost of uncompressed WAV. Opus would be ~10 MB/hour; revisit when device storage actually hurts. |
| Silence Timeout | **5 minutes** | Below an amplitude threshold for that long ends the recording. |
| Session Cap | **3 hours** | Hard bound on any one recording. |

Both limits end the recording and save it locally, same as a manual Stop — a forgotten
recording ends up in the Library, not lost. These bound battery and storage on the device
itself; there's no server involved to bound instead.

### The recording notification

While recording you'll see an ongoing "Harken — Recording…" notification carrying a live
elapsed counter and a **Stop** button. Per ADR-0003 this is not decoration: with the screen
locked it is the only surface you can see or act on, which is the whole scenario the
foreground service exists for. Android also requires it to keep the microphone alive in the
background, and it is a deliberate signal that the app isn't recording silently.
