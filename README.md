# Harken

An Android recorder that turns what was said into text you can read, on the phone
itself. Record, then transcribe — no account, no upload, no per-minute cost, and no
server to stand up before the app is useful.

Harken has two Modes ([ADR-0014](docs/adr/0014-local-first-with-optional-cloud-mode.md)):

- **Local Mode** — the default, and the whole product on its own. Recording,
  transcription and (once [ADR-0012](docs/adr/0012-full-standalone-local-summarization.md)
  ships) summarization all run on the phone. Works in airplane mode, start to finish.
- **Cloud Mode** — an opt-in upgrade for better transcription, expert summaries and chat
  over a recording's findings. Off by default, behind a Settings flag. **Not built yet.**
  In Cloud Mode the recording itself is uploaded; Local Mode sends nothing anywhere.

See `CONTEXT.md` for the glossary, `docs/adr/` for the decisions behind the shape of this
thing, and `docs/plans/roadmap-2026-08-30-two-modes.md` for what is being built next and
in what order.

## What works today

| | State |
| --- | --- |
| Record on Android (foreground service, screen locked, silence and duration caps) | Shipped |
| On-device transcription (whisper.cpp, `ggml-base.en.bin`, arm64-v8a) | Shipped |
| Library, rename, tags, copy/share transcript, delete | Shipped |
| On-device summarization | Decided ([ADR-0012](docs/adr/0012-full-standalone-local-summarization.md), [ADR-0016](docs/adr/0016-on-device-inference-runtime.md)), not built |
| Cloud Mode: cloud transcription, expert summary, chat | Decided ([ADR-0014](docs/adr/0014-local-first-with-optional-cloud-mode.md)), not built |
| Audio playback | Not built — a recording is read, not replayed |

The app has **no backend URL setting** and makes no network call other than the one-time
model download. The .NET solution in this repo is not a product surface: it is the
offline evaluation harness the on-device models get measured against (ADR-0014).

> **Record-then-transcribe, not live captions.** The live captioning path was deleted in
> [ADR-0007](docs/adr/0007-record-then-transcribe.md). Transcription is an explicit
> action taken later from the Library, one recording at a time, never automatically on
> stop.

## Target device

[ADR-0015](docs/adr/0015-target-device-floor.md) sets the floor at Nothing Phone
(2)-class hardware: an SD 8+ Gen 1 calibre prime core, 8 GB RAM, Android 13. Lower-end
phones are not a target. Timing, memory and thermal numbers taken on anything below the
floor do not describe the product — the Exynos 850 test device in this project's notes is
a lower-bound canary, not the reference.

`abiFilters` ships **arm64-v8a only**, so the app does not install on an x86_64 emulator.
A physical arm64 device is required to run it.

---

## Android app

### Prerequisites

- Android SDK (API 36) + JDK 17. Point `src/Harken.Android/local.properties`
  (gitignored) at your SDK, e.g. `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.
- Android NDK + CMake — whisper.cpp is vendored as source and built into
  `libharken_whisper_jni.so` as part of the Gradle build
  ([ADR-0011](docs/adr/0011-on-device-transcription.md)).
- A physical arm64 Android phone with **USB debugging** enabled (Settings → About phone →
  tap Build number ×7 → Developer options → USB debugging).

No backend, no LAN setup, no firewall rule.

### Run

```
cd src/Harken.Android
./gradlew installDebug
```

then launch it from the app drawer, or open `src/Harken.Android` in Android Studio and
hit Run.

### First launch

A two-step onboarding: what the app is, then the one-time speech-model download
(~140 MB, fetched from a GitHub release asset — the model is deliberately not bundled in
the APK). The download is skippable and can be run later from **Settings**; a partial or
truncated download is refused rather than promoted to a real model, so a dropped
connection costs a retry, not a silently broken install.

Permissions are requested at the point of first **Record**, not at launch — a prompt
means something to someone who just tapped Record and nothing to someone who just opened
the app. Denying the microphone is handled: the app says what is blocked and where to
grant it. Notification permission (Android 13+) is asked for too but never blocks
recording.

### Use

Three tabs: **Record**, **Library**, **Settings**.

- **Record → Stop.** Audio is captured to a WAV file in the app's private storage and
  saved as a session. Nothing is uploaded.
- **Library.** Each recording carries a **Transcribe** button. Transcription runs on the
  phone, one recording at a time app-wide, and only when asked
  ([ADR-0011](docs/adr/0011-on-device-transcription.md)). A transcription interrupted by
  the process dying is settled on next launch and offers **Retry** rather than showing a
  spinner forever.
- **Session detail.** Read the transcript, rename the recording, tag it, copy or share
  the text, or delete it — which removes the row, its segments and the WAV file.
  Speakers are labelled "Voice 1" / "Voice 2" from a gap heuristic, never a name: base.en
  returns no speaker information at all, and the label says so honestly (ADR-0010).

### Recording limits and storage cost

| | Value | Why |
| --- | --- | --- |
| Format | 16 kHz / 16-bit / mono WAV | What Whisper wants natively. No encoder dependency. |
| Storage | **~115 MB per hour** | The cost of uncompressed WAV. Opus would be ~10 MB/hour; revisit when device storage actually hurts. |
| Silence timeout | **5 minutes** | Below an amplitude threshold for that long ends the recording. |
| Session cap | **3 hours** | Hard bound on any one recording. |

Both limits end the recording and save it, so a forgotten session becomes a finished
recording in the Library rather than running until the battery goes. ADR-0007 moved these
from the server to the client, where they bound battery and storage rather than spend.

### The recording notification

While recording you'll see an ongoing "Harken — Recording…" notification carrying a live
elapsed counter and a **Stop** button. Per
[ADR-0003](docs/adr/0003-mobile-foreground-service.md) this is not decoration: with the
screen locked it is the only surface you can see or act on, which is the whole scenario
the foreground service exists for. Android also requires it to keep the microphone alive
in the background, and it is a deliberate signal that the app isn't recording silently.

---

## The .NET solution — an evaluation harness, not a product

`Harken.Api`, `Harken.Core` and `Harken.Console` are how transcription and summarization
quality get measured off-device: a known-good pipeline to compare an on-device model
against, running the same audio through server-side Whisper and a local Gemma model via
Ollama. ADR-0014 retired it as something a user is ever asked to install.

It is not required to build, install or use the Android app, and the app cannot talk to
it — there is no client left.

### Prerequisites

- .NET 10 SDK (10.0.302 — pinned in `global.json`).
- A Whisper GGML model file (e.g. `ggml-base.en.bin` from
  https://huggingface.co/ggerganov/whisper.cpp/tree/main), with its path set via
  `Whisper:ModelPath`. Without it the API starts fine but every transcription fails with
  "Whisper model not found".
- **Ollama** running locally, with a Gemma model pulled, for the summarize path:
  ```
  ollama pull gemma3:4b
  ```

`docs/setup.md` covers the model, Ollama and secrets in full.

```
dotnet user-secrets set "Whisper:ModelPath" "<path to ggml-base.en.bin>" --project src/Harken.Api
```

Ollama endpoint/model default to `http://localhost:11434` / `gemma3:4b`
(`src/Harken.Api/appsettings.json`, section `Ollama`), overridable there or via
`OLLAMA_ENDPOINT` / `OLLAMA_MODEL_NAME`.

### Run

```
dotnet run --project src/Harken.Api        # note the port, default http://localhost:5057
dotnet run --project src/Harken.Console    # R record · L list and summarize · Q quit
```

Every endpoint is anonymous — there is one implicit user, no accounts and no login
([ADR-0009](docs/adr/0009-remove-auth-for-mvp1.md), superseding ADR-0004).

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | liveness — `{"status":"ok"}` |
| `POST /sessions` | upload a WAV recording (`audio` file, `source` field, optional `recordingId`); starts transcription in the background |
| `GET /sessions` | sessions, newest first, metadata only |
| `GET /sessions/{id}` | one session + its ordered transcript segments + `TranscriptionStatus` (poll this) |
| `POST /sessions/{id}/summary` | generate (or re-read) the stored summary |

`recordingId` is optional and makes upload idempotent: re-sending a recording after a
dropped connection returns **200** with the session that already exists rather than
**201** and a duplicate. A malformed value is a 400.

When Cloud Mode is built it gets a versioned `/v1` contract from its first release
(ADR-0014); the routes above are the harness's, and are not that contract.

### Fresh database

If `src/Harken.Api/harken.db` doesn't exist yet:

```
dotnet ef database update --project src/Harken.Api
```

---

## Verification gates

- `.claude/scripts/check.sh` — full solution build plus `assembleDebug`,
  warnings-as-errors.
- `.claude/scripts/test-fast.sh` — .NET tests and Android JVM unit tests.

Instrumented Android tests (`src/Harken.Android/app/src/androidTest`) need a connected
device and are deliberately outside the fast gate — run them with
`./gradlew connectedDebugAndroidTest`.
