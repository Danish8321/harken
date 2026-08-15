# Harken

Live caption → transcript → AI summary. Slice 01: backend + throwaway console proof.
See `docs/plans/slice-01-console-proof.md` for scope, `CONTEXT.md` for glossary,
`docs/adr/` for the two decisions behind the shape of this thing.

## Prerequisites

- .NET 10 SDK (10.0.302 — pinned in `global.json`).
- **Azure Speech resource** (any region) — key + region. Real-time streaming STT.
- **Ollama** running locally, with a Gemma model pulled:
  ```
  ollama pull gemma3:4b
  ```
  Phase 1 summarize agent talks to Ollama, not Azure — see ADR-0002.
- A working microphone.

## Configure secrets

Never commit real keys. Set the Azure Speech key/region via user-secrets on the API
project (already `user-secrets init`'d):

```
dotnet user-secrets set "AzureSpeech:Key" "<your-key>" --project src/Harken.Api
dotnet user-secrets set "AzureSpeech:Region" "<your-region>" --project src/Harken.Api
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

Speak. Watch live captions. Press ENTER to stop. Answer `y` to summarize.

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
on Android 13+) — both are required for captioning to work.

### Use

**Capture** tab → Start → speak → live captions appear → Stop → Summarize (needs
Ollama running on the backend host, same as the console flow).

### Note on the persistent notification

While recording, you'll see an ongoing "Harken — recording…" notification. This is
intentional (ADR-0003) — required by Android to keep the microphone alive with the
screen locked, and a deliberate signal that the app isn't recording silently in the
background.
