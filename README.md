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
> a 4–5 second clip transcribed in 3–5 seconds, roughly real-time. That ratio is what
> gates slice 05 (mobile) — see `docs/plans/slice-04-record-then-transcribe.md`, "Carried,
> unresolved".

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
| `POST /sessions` | upload a WAV recording (`audio` file, `source` field); starts transcription in the background |
| `GET /sessions` | sessions, newest first, metadata only |
| `GET /sessions/{id}` | one session + its ordered transcript segments + `TranscriptionStatus` (poll this) |
| `POST /sessions/{id}/summary` | generate (or re-read) the stored summary |

```
curl http://localhost:5057/sessions
```

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

## Mobile (Android) — Slice 02

See `docs/plans/slice-02-mobile-android.md` for scope, `docs/adr/0003-mobile-foreground-service.md`
for why recording runs as a foreground service.

### Prerequisites

- `dotnet workload install maui-android` (pulls Android SDK/build tools — several GB).
- A device to run on: a physical Android phone with **USB debugging enabled**
  (Settings → About phone → tap Build number ×7 → Developer options → USB debugging),
  or an emulator via Android Studio's Device Manager / Visual Studio's
  ".NET Multi-platform App UI development" workload.
- Backend running and reachable on the same Wi-Fi/LAN as the phone (see Run, above) —
  note your PC's LAN IP (`ipconfig`), not `localhost`.

### Configure

Open the app → **Settings** tab → enter the backend base URL as
`http://<your-pc-LAN-IP>:5057` (port from `launchSettings.json`) → Save.

### Run

```
dotnet build src/Harken.Mobile -t:Run -f net10.0-android
```

or deploy from Visual Studio with the phone selected as the target device.

On first launch, grant the microphone permission prompt (and notification permission
on Android 13+) — both are required once recording returns in slice 05.

### Use

No sign-in step (ADR-0009) — the app opens straight on **Sessions**. Refresh → pick a
session → Summarize (needs Ollama running on
the backend host, same as the console flow). On-device recording and upload land in
slice 05; ADR-0007 keeps all transcription on the backend, so the phone never runs a
model itself.

### Note on the persistent notification

While recording, you'll see an ongoing "Harken — recording…" notification. This is
intentional (ADR-0003) — required by Android to keep the microphone alive with the
screen locked, and a deliberate signal that the app isn't recording silently in the
background.
