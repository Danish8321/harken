# Environment setup

The go-to doc for getting a machine ready to run Harken. The README covers *running* the
app once this is done; this covers what it depends on and how to prove each piece works
before blaming the app.

**MVP 1 needs no cloud account of any kind.** Transcription runs on a local Whisper model
and summaries on a local Gemma model, both on your own hardware
([ADR-0007](adr/0007-record-then-transcribe.md),
[ADR-0008](adr/0008-local-whisper-first.md)). Storage is a local SQLite file
([ADR-0005](adr/0005-sqlite-for-family-scope.md)).

So there are three required pieces — the **.NET 10 SDK**, a **Whisper model**, and
**Ollama with Gemma** — and one optional one, **Azure Speech**, which is MVP 2 and can be
skipped entirely today.

---

## 1. .NET 10 SDK

`global.json` pins **10.0.302** with `rollForward: latestFeature`, so a 10.0.3xx SDK
works and a 9.x does not.

```
dotnet --version
```

If that prints something older, install the .NET 10 SDK from
<https://dotnet.microsoft.com/download/dotnet/10.0>. On a machine with several SDKs,
`dotnet --list-sdks` shows what is available; `global.json` decides which is used inside
this repo.

For the Android client (`src/Harken.Android`, native Kotlin + Jetpack Compose — chosen
over MAUI for direct, bridge-free access to `AudioRecord` and the foreground service):
Android SDK (API 36, or use Android Studio's SDK Manager to install it) and JDK 17.
Point `src/Harken.Android/local.properties` (gitignored) at your SDK, e.g.
`sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`. The Gradle wrapper
(`gradlew`/`gradlew.bat`) needs no separate Gradle install.

Skip it if you are only running the backend and console client — which is the right way
to start (ADR-0008: the console proves the transcription pipeline before any mobile
work).

---

## 2. Whisper model

Transcription uses [Whisper.net](https://www.nuget.org/packages/Whisper.net) over
whisper.cpp. The NuGet packages come with the build; the **model file does not** — it is
~1.5 GB, versioned outside git, and downloaded once.

### Which model

| Model | Size | Notes |
| --- | --- | --- |
| `base` | ~150 MB | fast, noticeably less accurate — fine for a first smoke test |
| `small` | ~500 MB | reasonable balance |
| `medium` | ~1.5 GB | the accuracy worth having; ~2.5 GB VRAM in use |

Start with `base` to prove the pipeline runs at all, then move up. The model path is
configuration, not a code change, so switching is an app setting and a restart.

### Download

Models are published as GGML files by the whisper.cpp project on Hugging Face
(`ggerganov/whisper.cpp`). Whisper.net can also download on first use. Put the file
somewhere outside the repo and point config at it — do not commit it.

### GPU

Both `Whisper.net.Runtime` (CPU) and `Whisper.net.Runtime.Cuda.Windows` are referenced.
The CUDA package is necessary but **not sufficient** for GPU use — it needs the NVIDIA
CUDA toolkit actually installed and discoverable; without it, Whisper.net has no native
library to fall back to unless the CPU package is also present, and fails outright with
"Native Library not found in default paths" (confirmed by a real run on this project's
dev machine, an RTX 3050 without the CUDA toolkit installed — it ran on CPU instead, no
extra setup). Published runtimes cover Windows, Linux, macOS, Metal, CoreML, CUDA,
Vulkan, OpenVINO and Wasm — **there is no Android runtime**, which is one reason the
phone never transcribes (ADR-0007).

### VRAM contention

On a 4 GB card, Gemma 3:4b (~3 GB resident) and Whisper `medium` (~2.5 GB) do not both
fit. Transcription and summarization run at different times, so a short keep-alive lets
Gemma unload between uses:

```
setx OLLAMA_KEEP_ALIVE 30s
```

If that proves fragile, drop to a smaller Whisper model rather than fighting it.

---

## 3. Ollama + Gemma

The Summarize agent runs against a local model, so no cloud AI account is needed and
transcripts never leave the machine ([ADR-0002](adr/0002-ichatclient-provider-seam.md)
keeps `IChatClient` as the seam, so a hosted model can replace this later without
touching the agent).

Install from <https://ollama.com/download>, then:

```
ollama pull gemma3:4b
```

Verify it is serving and the model is present:

```
curl http://localhost:11434/api/tags
```

`gemma3:4b` should appear. ~3 GB download. A smaller box can use `gemma3:1b` — set
`Ollama:Model` to match.

Ollama must be running whenever you use **Summarize**. Transcription does not need it.

---

## 4. Azure Speech — MVP 2 only, skip for now

Not required. The backend declares which transcription Providers are available, and with
no Azure credentials configured it simply offers local Whisper (ADR-0008). Come back here
when you want the second provider.

### Create it

**Portal:** <https://portal.azure.com> → *Create a resource* → search **Speech** →
*Create*.

| Field | What to pick |
| --- | --- |
| Subscription | any — a Visual Studio Enterprise subscription's $150/month credit covers this comfortably |
| Resource group | e.g. `harken-rg` (create new) |
| Region | one close to you |
| Name | e.g. `harken-speech` |
| Pricing tier | **S0** — batch transcription is not available on F0 |

**Or CLI**, with the Azure CLI installed and `az login` done:

```
az group create --name harken-rg --location westeurope
az cognitiveservices account create \
  --name harken-speech \
  --resource-group harken-rg \
  --kind SpeechServices \
  --sku S0 \
  --location westeurope \
  --yes
```

### Get the key and region

Portal: the resource → *Keys and Endpoint* → copy **KEY 1** and the **Location/Region**
short form (e.g. `westeurope`, not "West Europe").

```
az cognitiveservices account keys list --name harken-speech --resource-group harken-rg
az cognitiveservices account show --name harken-speech --resource-group harken-rg --query location -o tsv
```

### Verify the key before running Harken

Separates "my key is wrong" from "the app is broken":

```
curl -X POST \
  "https://<region>.api.cognitive.microsoft.com/sts/v1.0/issueToken" \
  -H "Ocp-Apim-Subscription-Key: <your-key>" \
  -H "Content-Length: 0"
```

A long JWT back means key and region are good. `401` means the key is wrong; `404` or a
DNS failure usually means the region is wrong.

### Cost and budget alert

Batch transcription bills ~$0.18 per audio hour of submitted content — see
[`cost-model.md`](cost-model.md). Set a budget alert (Cost Management → Budgets) the day
you first submit anything.

### Keys are secrets

Never commit one, never paste one into `appsettings.json` (its `AzureSpeech` values are
deliberately empty placeholders), never put one in a screenshot or an issue. If a key
leaks, rotate it: *Keys and Endpoint* → *Regenerate Key 1*, then update user-secrets. Both
keys are equivalent, so regenerating one lets you roll over without downtime.

---

## 5. Secrets

No secrets are required to run MVP 1 — there is no signing key, and no cloud
credentials (ADR-0009, ADR-0008).

Azure keys are only needed for MVP 2:

```
dotnet user-secrets set "AzureSpeech:Key" "<key from step 4>" --project src/Harken.Api
dotnet user-secrets set "AzureSpeech:Region" "<region from step 4>" --project src/Harken.Api
```

User-secrets live outside the repo (`%APPDATA%\Microsoft\UserSecrets\` on Windows), so
they cannot be committed by accident. Check what is set:

```
dotnet user-secrets list --project src/Harken.Api
```

---

## 6. Database

SQLite, one file, created by migrations — no server to install. If
`src/Harken.Api/harken.db` does not exist:

```
dotnet ef database update --project src/Harken.Api
```

If `dotnet ef` is missing: `dotnet tool install --global dotnet-ef`.

The file is gitignored. Deleting it and re-running the command gives a clean slate —
acceptable now precisely because there is no real recorded data yet. Once anyone has
transcripts they care about, that stops being true.

---

## 7. Disk space

Record-then-transcribe means audio lives on disk on both sides. Budget for it:

- ~1.5 GB for a `medium` Whisper model, once.
- ~3 GB for Gemma 3:4b, once.
- Per recorded hour: ~115 MB as WAV (what both the console and the phone record), or
  ~10 MB as Opus. Slice 06 deliberately kept WAV on the phone too: Whisper wants it
  natively, the backend accepts nothing else, and an encoder is a dependency and a failure
  mode the slice did not need. Phone recordings are deleted locally once the upload
  succeeds, so the device cost is per-recording, not cumulative — but a 3-hour recording
  is ~345 MB on the phone until it lands.

**Recordings are kept after transcription**, so backend disk grows with every hour ever
recorded — not with a backlog. That is deliberate: audio cannot be recreated, and
re-transcribing with a better model needs it. Roughly 23 GB per 200 recorded hours as WAV.
There is no automatic cleanup, so this is worth glancing at occasionally rather than
discovering.

---

## 8. Prove the environment works

In order, so a failure points at one thing:

1. **Build and test** — `bash .claude/scripts/check.sh` then
   `bash .claude/scripts/test-fast.sh`. These touch no external service; a failure here is
   the code or the SDK. Both scripts also build `src/Harken.Android` (`gradlew
   assembleDebug`) and run its JVM unit tests (`gradlew testDebugUnitTest`) — a Gradle
   failure here is the native client, not the backend.
2. **Backend boots** — `dotnet run --project src/Harken.Api`. Then
   `curl http://localhost:5057/health` → `{"status":"ok"}`.
3. **Transcription** — run the console client, record a short clip, and let it transcribe.
   Text back means Whisper, the model file, and the runtime are all wired correctly. Note
   how long it takes relative to the clip length: that ratio is the number ADR-0008 says
   must be measured before building the phone client.
4. **Summary** — summarize the transcript. Text back means Ollama is reachable and the
   agent works.
5. **Phone end-to-end** — with the API bound to `0.0.0.0` (see Troubleshooting), record on
   the Android app, lock the screen mid-recording, stop from the notification, and read the
   transcript back. Then check the failure path: stop the backend, record, and confirm the
   app keeps the file and names its path.

Steps 3, 4 and 5 have never been run in this project's history — they are the real
verification frontier. Everything before them is covered by automated tests.

Steps 4 and 5 have since been run successfully over a USB `adb reverse tcp:5057 tcp:5057`
tunnel — see [`onboarding.md`](onboarding.md)'s Option B note. Full phone flow confirmed:
record, lock screen, stop from notification, upload, transcript, summarize, and the
offline-recovery path (stop the backend mid-recording, confirm the app keeps the file).

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Transcription fails immediately | model file missing or path wrong — check the configured path exists |
| Transcription very slow | running on CPU. Confirm the CUDA runtime package is referenced and the GPU is visible |
| Transcription starts then dies | out of VRAM — Gemma still resident. Shorten `OLLAMA_KEEP_ALIVE` or use a smaller model |
| Transcript is nonsense or repeats a phrase | Whisper hallucinating on silence or noise. Try a larger model; check the audio is actually 16 kHz mono |
| `502` on summarize | Ollama not installed, not running, or model not pulled — `curl http://localhost:11434/api/tags` (connection refused means not installed/not running; empty/missing model in the list means pull it) |
| `404` on a session id | the id does not exist — sessions are never deleted, so double-check it |
| Upload fails from the phone | use the PC's LAN IP, not `localhost`; same Wi-Fi; Windows Firewall may need to allow the port. The recording is kept on the device and its path shown — retry is safe, `recordingId` makes it idempotent |
| Phone can't reach PC over Wi-Fi at all (LAN IP `/health` also fails from the phone's browser) | router AP/client isolation, common on shared/ISP-default routers. Use a USB reverse tunnel instead — `adb reverse tcp:5057 tcp:5057`, then enter `http://localhost:5057` on the phone. See [`onboarding.md`](onboarding.md) §5b |
| Phone cannot reach the backend at all | `launchSettings.json` binds `localhost` only. Start it as `dotnet run --project src/Harken.Api --urls http://0.0.0.0:5057` |
| Recording stops on its own | by design — 5 minutes of silence or the 3-hour Session Cap. Both upload what was captured |
| No Stop button on the notification | notification permission denied (Android 13+). Recording still works; grant it in Settings → Apps → Harken → Notifications |
| SQLite `ORDER BY` errors on new queries | SQLite cannot translate `ORDER BY` on `TimeSpan`/`DateTimeOffset` — materialize first, sort client-side (ADR-0005) |

## Cost

**MVP 1 costs nothing to run.** See [`cost-model.md`](cost-model.md) for MVP 2 and the
storage arithmetic that replaces the old per-audio-hour meter.
