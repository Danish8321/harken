# Plan — Slice 03: Identity, ownership, and API gaps

**Goal:** Close the architectural gaps found reviewing slices 01–02. Harken becomes a
multi-user system with real authentication and per-user data isolation, plus the read
paths and client-declared audio source the current contract is missing.

**Driver:** ADR-0004 (identity and ownership). Scope is family-first, public later —
so isolation is built in now, while there is no real recorded data to migrate.

**Not in scope:** sharing between users, email confirmation/password reset flows,
rate limiting, cloud deployment, browser extension. Public-release hardening is its
own slice — see "Before any public exposure" at the bottom.

---

## Task 1 — Identity schema + JWT issuance
- **Files:** `src/Harken.Api/Data/HarkenDbContext.cs` (inherit
  `IdentityDbContext<IdentityUser>`), `src/Harken.Api/Auth/` (token service,
  options), `Program.cs`, `Directory.Packages.props`.
- **Change:** Add `Microsoft.AspNetCore.Identity.EntityFrameworkCore` and
  `Microsoft.AspNetCore.Authentication.JwtBearer`. Configure Identity with local
  accounts. Add JWT signing config (key/issuer/audience) read from user-secrets,
  **failing fast at startup if absent**. Endpoints: `POST /auth/register`,
  `POST /auth/login` returning a JWT.
- **Verify:** `check.sh`; integration test registers a user, logs in, receives a
  token that parses and carries a user id claim.

## Task 2 — Session ownership (schema change)
- **Files:** `src/Harken.Core/Session.cs` (add `OwnerId`), `HarkenDbContext.cs`,
  new EF migration.
- **Change:** `Session.OwnerId` (string, FK to the Identity user), required, indexed.
  **Read the generated migration before applying** — existing dev rows have no owner,
  so the local `harken.db` is deleted and recreated rather than migrated (no real data
  exists; this is stated explicitly in ADR-0004).
- **Verify:** migration reviewed and quoted in the task's review; `dotnet ef database
  update` succeeds; `sqlite3 .tables` shows Identity tables + `Sessions.OwnerId`.

## Task 3 — Authorize the hub and endpoints, enforce isolation
- **Files:** `src/Harken.Api/Hubs/CaptionHub.cs`, `Program.cs`,
  `src/Harken.Api/Agents/SummarizeAgent.cs`.
- **Change:** `[Authorize]` on `CaptionHub` and all REST endpoints. Hub sets
  `Session.OwnerId` from `Context.User`. `SummarizeAgent.SummarizeAsync` takes the
  caller's user id and filters by it — a Session belonging to another user must be
  indistinguishable from one that does not exist (**404, never 403**, so the endpoint
  cannot be used to enumerate other users' session ids).
- **Verify:** integration tests — (a) unauthenticated hub connect and summary POST are
  rejected; (b) user A cannot summarize user B's session and receives 404.
- **DONE.** `[Authorize]` on `CaptionHub`, `RequireAuthorization()` on the summary
  endpoint. Hub throws `HubException` when the user id claim is absent rather than
  creating an ownerless Session. `SummarizeAgent` filters by `OwnerId` and returns
  `null` for both missing and not-yours. `access_token` query-string fallback is
  scoped to `/hub` only. `test-fast.sh` 6/6, including
  `SummaryForAnotherUsersSessionReturnsNotFoundNotForbidden`.

## Task 4 — Read endpoints for stored data
- **Files:** `Program.cs`, `src/Harken.Core/Contracts/` (list/detail DTOs).
- **Change:** `GET /sessions` (caller's sessions, newest first, summary metadata only)
  and `GET /sessions/{id}` (session + ordered transcript segments). Both filtered by
  owner, both 404 on another user's id. Persist generated summaries so they can be
  re-read rather than regenerated on every request.
- **Verify:** integration tests — a user sees only their own sessions in the list; a
  stored transcript round-trips; another user's id returns 404.
- **DONE.** `GET /sessions` and `GET /sessions/{id:guid}`, both `RequireAuthorization()`
  and filtered by `OwnerId`; not-yours and not-found both return 404. New
  `StoredSummary` entity (unique index on `SessionId`, cascade FK) persists generated
  summaries. Migration `20260815101416_StoredSummaries` read in full before applying:
  `Up` is `CreateTable` + `CreateIndex` only, the single `DropTable` is in `Down`.
  `check.sh` OK; `test-fast.sh` 12/12.
- **Found while implementing:** SQLite's ORDER BY limitation is not just `TimeSpan` —
  `OrderByDescending(s => s.StartedAt)` failed the same way on `DateTimeOffset`. Both
  orderings now materialize first and sort client-side, each with a comment saying why.

## Task 5 — Client-declared audio source
- **Files:** `src/Harken.Core/Contracts/ICaptionClient.cs` / hub contract,
  `CaptionHub.StreamAudio`, `Harken.Console`, `Harken.Mobile`.
- **Change:** `StreamAudio` takes an `AudioSource` parameter instead of hardcoding
  `Microphone`; clients pass their own (console/mobile = `Microphone`, extension will
  pass `SystemAudio`). Validate the incoming value.
- **Verify:** `check.sh`; integration test asserts a session created with
  `SystemAudio` persists as `SystemAudio`.
- **DONE.** `StreamAudio(IAsyncEnumerable<byte[]>, AudioSource, CancellationToken)`.
  `Enum.IsDefined` rejects undefined values with `HubException` *before* any DB work —
  enums cross the wire as ints, so an out-of-range value is a client-supplied value
  like any other. Console and mobile pass `Microphone`. `check.sh` OK;
  `test-fast.sh` 14/14, including one asserting a rejected value persists no Session.

## Task 6 — Client auth: console and mobile
- **Files:** `src/Harken.Console/Program.cs`, `src/Harken.Mobile/Services/`,
  `Pages/` (login page), `AppSettings`.
- **Change:** Console prompts for email/password, logs in, attaches the bearer token
  to both the hub connection and HTTP calls. Mobile gets a login page; token stored in
  **`SecureStorage`, not `Preferences`** (Preferences is not encrypted). Handle 401 by
  returning the user to login.
- **Verify:** `check.sh`; unit test on the token-storage wrapper. Real login flow is
  device-verified, not build-verified — flag as pending.
- **DONE.** Console prompts with echo suppressed (`ReadKey(intercept: true)`), logs in,
  and passes the token via `HttpConnectionOptions.AccessTokenProvider` for the hub plus
  a bearer header for HTTP — a raw header can't reach a WebSocket, and the server only
  honours `access_token` on `/hub`. Mobile gets `LoginPage`; `TokenStore` lives in
  `Harken.Core.Client` over an `ISecretStore` seam so it is testable without MAUI, with
  `SecureStorageSecretStore` (SecureStorage, never Preferences) as the Android backing.
  Any 401 clears the token and returns to login. `LoginRequest`/`TokenResponse` moved
  from `Harken.Api.Auth` to `Harken.Core.Contracts` — two clients now speak them, and a
  hand-copied DTO would be a duplicated contract. `check.sh` OK; `test-fast.sh` 14 + 6.
- **Pending device verification:** the real login → capture → summarize flow on Android
  is not build-verifiable; only compilation and the token-store unit tests are.

## Task 7 — Fail-fast config + correctness fixes
- **Files:** `Program.cs`, `src/Harken.Api/Hubs/CaptionHub.cs`.
- **Change:**
  - Throw at startup if Azure Speech key/region or JWT signing key is missing, instead
    of failing opaquely mid-session when the user presses Start.
  - `CaptionHub.OnFinal`: `SaveChangesAsync(ct)` → `SaveChangesAsync(CancellationToken.None)`.
    On cancellation the final recognized segments currently fail to persist — the tail
    of the transcript is lost exactly when stopping.
  - Replace the `MapGet("/", () => "Hello World!")` template placeholder with a real
    unauthenticated health endpoint (`GET /health`), and repoint the smoke test at it.
- **Verify:** `check.sh` + `test-fast.sh`; a test asserts startup throws when required
  config is absent.
- **DONE.** `AzureSpeech:Key`/`Region` guard mirrors the existing `Jwt:Key` one.
  `OnFinal` and the session-end save use `CancellationToken.None`; the session-*create*
  save still honours `ct`, correctly — a cancelled start needs no Session row.
  `GET /health` is `AllowAnonymous`; smoke test repointed. `check.sh` OK;
  `test-fast.sh` 6 + 17 (three new startup-guard theory cases).
- **Carried, not fixed — `CaptionHub.OnFinal` is still `async void`.** Out of this
  task's scope: it is subscribed to a `void`-returning event, so fixing it means either
  changing `ISpeechTranscriber`'s delegate and every raise site, or having `StreamAudio`
  track and await in-flight handlers before disposing the transcriber. Risk is narrowed
  (the body catches all exceptions, so it cannot crash the process, and scope-per-save
  rules out a DbContext race) but a final segment's write can still be in flight when
  `StreamAudio` returns — a lost tail write on host shutdown. Needs its own task.

## Task 8 — Update docs
- **Files:** `README.md`, `docs/adr/0002-ichatclient-provider-seam.md`,
  `docs/plans/slice-01-console-proof.md`, `slice-02-mobile-android.md`.
- **Change:** README covers register/login, token config, and the new endpoints.
  Correct ADR-0002's stale `CreateAIAgent` reference to the actual `AsAIAgent` API.
  Note in slices 01/02 that their "no auth" state was superseded here.
- **Verify:** fresh-eyes read; no stale instructions remain.
- **DONE.** README now documents register/login, all three required secrets (placeholders
  only), and every endpoint including which need a bearer token and the `/hub`-only
  `access_token` rule. ADR-0002 corrected to `AsAIAgent`. Slices 01/02 carry a supersede
  banner; their task history is left as recorded. `check.sh` OK.
- **Worth noting:** the README's setup steps omitted `Jwt:Key` entirely — following them
  verbatim after Task 1 would have crashed at startup. Fail-fast turned a silent
  misconfiguration into a loud one, and the docs gap only surfaced because of it.

---

## Slice 03 result
All 8 tasks done. `check.sh` OK; `test-fast.sh` 6 unit + 17 integration, 0 failed —
re-run independently, not taken from task reports. Exit criteria met: unauthenticated
access rejected; user A cannot see or summarize user B's data (404, not 403); sessions
list and transcript read round-trip; audio source is client-declared.

**Not proven by any of this:** nothing has run against live Azure Speech, a real Ollama,
or a physical Android device. Slice 03 is build- and test-verified only.

**Carried out of this slice — since fixed.** `CaptionHub.OnFinal` is no longer
`async void`. The event still returns `void`, so no interface change was needed: the
handler now enqueues `PersistFinalAsync(text)` into a `ConcurrentQueue<Task>`, and
`StreamAudio` drains that queue after `StopAsync` (and again in `finally`) before
disposing the transcriber. `TranscriptTailTests` covers it with a transcriber that emits
its final result from inside `StopAsync`, matching the real Speech SDK timing.

The first version of that test passed with the fix reverted, so it proved nothing — the
DB write alone is too fast to lose. It now asserts on the handler's slowest step (the
caption push to the client), and was confirmed to fail with the drain removed and pass
with it restored. A regression test that has never been seen to fail is not evidence.

---

## Exit criteria
`check.sh` and `test-fast.sh` green. Integration tests prove: unauthenticated access
is rejected; user A cannot see or summarize user B's data (404, not 403); sessions
list and transcript read round-trip; audio source is client-declared.

## Before any public exposure (explicitly NOT in this slice)
ADR-0004 takes on password-storage responsibility. Before Harken is reachable from
outside a trusted network, these are required, not optional:
- HTTPS enforced; no plaintext token transport.
- Email confirmation, password reset, and account lockout.
- Rate limiting on `/auth/*` to blunt credential stuffing.
- Audio and transcripts encrypted at rest, and a data-retention/deletion story.
- A dependency and secrets audit.
Family-on-LAN is a meaningfully lower bar than public internet — do not treat
finishing this slice as clearance for the latter.
