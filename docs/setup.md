# Environment setup

The go-to doc for getting a machine ready to run Harken. The README covers *running*
the app once this is done; this covers the accounts, services, and secrets it depends
on, and how to prove each one works before blaming the app.

Harken needs three external things: the **.NET 10 SDK**, an **Azure Speech resource**
(real-time speech-to-text), and **Ollama** with a Gemma model (the summarize agent).
It needs no cloud database — storage is a local SQLite file (ADR-0005).

---

## 1. .NET 10 SDK

`global.json` pins **10.0.302** with `rollForward: latestFeature`, so a 10.0.3xx SDK
works and a 9.x does not.

```
dotnet --version
```

If that prints something older, install the .NET 10 SDK from
<https://dotnet.microsoft.com/download/dotnet/10.0>. On a machine with several SDKs,
`dotnet --list-sdks` shows what is actually available; `global.json` decides which is
used inside this repo.

For the Android client only:

```
dotnet workload install maui-android
```

Several GB. Skip it if you are only running the backend and console client.

---

## 2. Azure Speech resource

This is the only paid service, and the only one that needs an Azure account.

### Create it

**Portal:** <https://portal.azure.com> → *Create a resource* → search **Speech** →
*Create*.

| Field | What to pick |
| --- | --- |
| Subscription | any |
| Resource group | e.g. `harken-rg` (create new) |
| Region | one close to you — latency is per-audio-chunk and you will hear it |
| Name | e.g. `harken-speech` |
| Pricing tier | **F0 (free)** to start — see limits below |

**Or CLI**, if you have the Azure CLI installed and `az login` done:

```
az group create --name harken-rg --location westeurope
az cognitiveservices account create \
  --name harken-speech \
  --resource-group harken-rg \
  --kind SpeechServices \
  --sku F0 \
  --location westeurope \
  --yes
```

### Get the key and region

Portal: the resource → *Keys and Endpoint* → copy **KEY 1** and the **Location/Region**
value (the short form, e.g. `westeurope` — not the display name "West Europe").

CLI:

```
az cognitiveservices account keys list --name harken-speech --resource-group harken-rg
az cognitiveservices account show --name harken-speech --resource-group harken-rg --query location -o tsv
```

### Free tier limits

F0 allows roughly **5 audio hours per month** of standard speech-to-text and **one
concurrent request**. That one-concurrent-request limit matters: two people captioning
at the same time will fail on F0. Fine for proving the thing works, not fine for family
use — move to S0 (pay-as-you-go, billed per audio hour) before more than one person
relies on it. One Azure subscription allows only one F0 Speech resource.

### Recognition language

The transcriber does not set a language, so the Speech SDK default (**en-US**) applies.
If you need another language, that is a code change in
`src/Harken.Api/Speech/AzureSpeechTranscriber.cs` (`SpeechConfig.SpeechRecognitionLanguage`),
not a config setting — it has never been exercised.

### Verify the key before running Harken

Worth doing: it separates "my key is wrong" from "the app is broken".

```
curl -X POST \
  "https://<region>.api.cognitive.microsoft.com/sts/v1.0/issueToken" \
  -H "Ocp-Apim-Subscription-Key: <your-key>" \
  -H "Content-Length: 0"
```

A long JWT string back means the key and region are good. `401` means the key is wrong;
`404`/DNS failure usually means the region is wrong.

### Keys are secrets

Never commit one, never paste one into `appsettings.json` (its `AzureSpeech` values are
deliberately empty placeholders), never put one in a screenshot or an issue. If a key
leaks, rotate it: *Keys and Endpoint* → *Regenerate Key 1*, then update user-secrets.
Both keys are equivalent — regenerating one lets you roll over without downtime.

---

## 3. Ollama + Gemma

The summarize agent runs against a local model, so no cloud AI account is needed and
transcripts never leave the machine (ADR-0002 keeps `IChatClient` as the seam, so Azure
AI Foundry can replace this later without touching the agent).

Install from <https://ollama.com/download>, then:

```
ollama pull gemma3:4b
```

Verify it is serving and the model is present:

```
curl http://localhost:11434/api/tags
```

`gemma3:4b` should appear in the list. ~3 GB download; it runs on CPU but is markedly
faster with a GPU. A smaller box can use `gemma3:1b` — set `Ollama:Model` to match.

Ollama must be running whenever you use **Summarize**. Live captioning does not need it;
only the summary step does, and that step is what returns `502` if Ollama is unreachable.

---

## 4. Secrets

Three settings are required. The API **throws at startup** if any is missing — that is
deliberate, so a misconfiguration is loud at boot instead of silent until someone presses
Start.

```
dotnet user-secrets set "AzureSpeech:Key" "<key from step 2>" --project src/Harken.Api
dotnet user-secrets set "AzureSpeech:Region" "<region from step 2>" --project src/Harken.Api
dotnet user-secrets set "Jwt:Key" "<32+ byte random value>" --project src/Harken.Api
```

`Jwt:Key` signs login tokens. Anyone holding it can mint a valid token for any account,
so generate a fresh random value per machine and never reuse one from any document —
including this one, which is why no example value appears here.

Generate one:

```
# PowerShell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 } | ForEach-Object { [byte]$_ }))

# bash
openssl rand -base64 48
```

User-secrets live outside the repo (`%APPDATA%\Microsoft\UserSecrets\` on Windows), so
they cannot be committed by accident. Check what is set:

```
dotnet user-secrets list --project src/Harken.Api
```

`Jwt:Issuer` and `Jwt:Audience` default to `Harken` and only need setting if you want
something else.

---

## 5. Database

SQLite, one file, created by migrations — no server to install. If
`src/Harken.Api/harken.db` does not exist:

```
dotnet ef database update --project src/Harken.Api
```

If `dotnet ef` is missing: `dotnet tool install --global dotnet-ef`.

The file is gitignored. Deleting it and re-running the command gives a clean slate —
acceptable now precisely because there is no real recorded data yet (ADR-0004). Once
family members have transcripts, that stops being true.

---

## 6. Prove the environment works

In order, so a failure points at one thing:

1. **Build and test** — `bash .claude/scripts/check.sh` then
   `bash .claude/scripts/test-fast.sh`. These touch no external service; if they fail,
   the problem is the code or the SDK, not Azure.
2. **Backend boots** — `dotnet run --project src/Harken.Api`. Reaching "Now listening on"
   means all three required secrets were present. Then
   `curl http://localhost:5057/health` → `{"status":"ok"}`.
3. **Account** — register and log in per the README. A token back means Identity and
   `Jwt:Key` are working.
4. **Speech** — run the console client and speak. Live captions mean Azure Speech is
   wired correctly. This is the first step that costs money.
5. **Summary** — answer `y` at the summarize prompt. Text back means Ollama is reachable
   and the agent works.

Steps 4 and 5 have never been run in this project's history — they are the real
verification frontier. Everything before them is covered by automated tests.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup throws naming `Jwt:Key` or `AzureSpeech:*` | secret not set — `dotnet user-secrets list` |
| `401` from `/auth/login` | wrong email/password. The message is deliberately generic so it cannot be used to discover which accounts exist |
| Registration rejected | password rules: 12+ chars, upper, lower, digit, non-alphanumeric |
| `401` on every authenticated call | token expired (7-day lifetime) — log in again |
| Hub connects, no captions | Azure key/region — verify with the `issueToken` curl above |
| Captions work, `502` on summarize | Ollama not running or model not pulled — `curl http://localhost:11434/api/tags` |
| `404` on a session id you believe exists | it belongs to another account. By design: the API never confirms an id it does not own |
| Phone can't reach the backend | use the PC's LAN IP, not `localhost`; same Wi-Fi; Windows Firewall may need to allow the port |
| SQLite `ORDER BY` errors on new queries | SQLite cannot translate `ORDER BY` on `TimeSpan`/`DateTimeOffset` — materialize first, sort client-side (ADR-0005) |

## Cost

- **Azure Speech** — the only meter. F0 is free within its monthly audio-hour limit;
  S0 bills per audio hour of streaming.
- **Ollama** — free, local, no account.
- **SQLite** — a file.

Set a budget alert on the Azure subscription if you move off F0. Live captioning streams
continuously while a session is open, so cost tracks wall-clock recording time, not the
number of requests.
