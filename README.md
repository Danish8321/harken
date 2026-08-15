# Harken

Record → transcript → AI summary. Multi-user: every session belongs to the
account that recorded it, and all endpoints except `GET /health` require a bearer
token (ADR-0004, `docs/plans/slice-03-identity-and-ownership.md`).
See `docs/plans/` for slice scope, `CONTEXT.md` for glossary, `docs/adr/` for the
decisions behind the shape of this thing.

> **Recording is temporarily missing.** The live captioning path was deleted in
> [ADR-0007](docs/adr/0007-record-then-transcribe.md) Task 1, and record-then-transcribe
> is being built in its place ([slice 04](docs/plans/slice-04-record-then-transcribe.md)).
> Right now the clients can sign in, read sessions, and summarize them — but nothing can
> create a new session until Task 7 lands. Transcription will run on local Whisper
> ([ADR-0008](docs/adr/0008-local-whisper-first.md)): no Azure, no cloud account, no cost.

## Prerequisites

**Setting up a machine from scratch? Start at [`docs/setup.md`](docs/setup.md)** — it
covers the Whisper model, Ollama, generating and storing the secrets, and proving each
piece works before you run anything. This section is the short list; that doc is the
go-to.

- .NET 10 SDK (10.0.302 — pinned in `global.json`).
- **Ollama** running locally, with a Gemma model pulled:
  ```
  ollama pull gemma3:4b
  ```
  Phase 1 summarize agent talks to Ollama, not Azure — see ADR-0002.
- A working microphone.

## Configure secrets

Never commit real keys. One setting is **required** — the API throws at startup and
refuses to serve without it, rather than failing opaquely later. Set it via user-secrets
on the API project (already `user-secrets init`'d):

```
dotnet user-secrets set "Jwt:Key" "<32+ byte random value>" --project src/Harken.Api
```

There are no cloud credentials to configure: MVP 1 transcribes with a local Whisper model
and summarizes with a local Gemma model (ADR-0008).

`Jwt:Key` signs the login tokens. Generate a fresh random value per machine — never
reuse a value from any doc, and never commit one. `Jwt:Issuer`/`Jwt:Audience` default
to `Harken` and only need setting if you want something else.

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

Create an account (once per user — there is no seeded account):

```
curl -X POST http://localhost:5057/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"<your-password>"}'
```

Passwords must be at least 12 characters with upper, lower, digit, and non-alphanumeric.
A rejected registration comes back as a validation problem listing what failed.

Terminal 2 — console client:

```
dotnet run --project src/Harken.Console
```

It prompts for email and password (password echo suppressed), logs in, lists the
sessions you own, and offers to summarize one. Recording arrives with slice 04 Task 7.

## API

All endpoints except `GET /health` require `Authorization: Bearer <token>`.
Another user's session id returns **404, not 403** — the API never confirms that an
id it doesn't own exists.

| Endpoint | Auth | Purpose |
| --- | --- | --- |
| `GET /health` | anonymous | liveness — `{"status":"ok"}` |
| `POST /auth/register` | anonymous | create an account (`email`, `password`) |
| `POST /auth/login` | anonymous | returns a JWT for the bearer header |
| `GET /sessions` | bearer | caller's sessions, newest first, metadata only |
| `GET /sessions/{id}` | bearer | one session + its ordered transcript segments |
| `POST /sessions/{id}/summary` | bearer | generate (or re-read) the stored summary |

Get a token:

```
curl -X POST http://localhost:5057/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"<your-password>"}'
```

then `curl http://localhost:5057/sessions -H "Authorization: Bearer <token>"`.

Every endpoint takes the token in the `Authorization` header. The `access_token`
query-string exception existed only for the SignalR hub and was removed with it —
tokens in query strings leak into logs and referrers, so nothing accepts one now.

A 401 from any endpoint means the token expired — log in again.

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

Sign in first: the app opens on a **Login** page and won't reach the backend without
a token. Use the account you registered above (register via `curl` — the app has no
sign-up screen). The token is kept in Android `SecureStorage`; a 401 clears it and
returns you to Login.

Then: **Sessions** tab → Refresh → pick a session → Summarize (needs Ollama running on
the backend host, same as the console flow). On-device recording and upload land in
slice 05; ADR-0007 keeps all transcription on the backend, so the phone never runs a
model itself.

### Note on the persistent notification

While recording, you'll see an ongoing "Harken — recording…" notification. This is
intentional (ADR-0003) — required by Android to keep the microphone alive with the
screen locked, and a deliberate signal that the app isn't recording silently in the
background.
