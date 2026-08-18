# Plan — Slice 05: Remove authentication for MVP 1

**Goal:** Strip Identity/JWT/ownership from the backend and both clients. One implicit
user, LAN-open, no login anywhere. Clears the ground for slice 06 (mobile recording)
so it isn't building new endpoints on top of machinery about to be deleted.

**Driver:** [ADR-0009](../adr/0009-remove-auth-for-mvp1.md) (supersedes
[ADR-0004](../adr/0004-identity-and-ownership.md)).

**Not in scope:** re-adding auth later (explicitly deferred, not designed here),
anything in slice 06 (mobile recording itself).

---

## Task 1 — Drop ownership + Identity from the schema
- **Files:** `src/Harken.Api/Data/HarkenDbContext.cs`, `src/Harken.Core/Session.cs`
  (drop owner property), new EF migration.
- **Change:** Remove Identity's `DbSet`s/model config, remove `Session`'s owner FK.
  **Read the generated migration before applying** — this one is a genuine destructive
  drop (Identity tables + the FK column), which is correct here per ADR-0009 (no real
  recorded data yet), not a mistake to catch. Confirm it drops only Identity tables and
  the owner column, nothing else.
- **Verify:** migration quoted in review; `dotnet ef database update` succeeds against
  a fresh dev DB; `check.sh`.

## Task 2 — Strip auth from the API
- **Files:** `src/Harken.Api/Program.cs`, `src/Harken.Api/Auth/` (delete —
  `TokenService.cs`, `AuthContracts.cs`), `src/Harken.Core/Contracts/AuthContracts.cs`
  (delete), JWT/Identity package references in `Harken.Api.csproj` +
  `Directory.Packages.props`.
- **Change:** Remove `AddAuthentication`/`AddIdentity`/JWT bearer setup,
  `RequireAuthorization()` from every endpoint, `/auth/register` and `/auth/login`
  routes. Every query drops its owner filter — `GET /sessions/{id}` on any existing id
  now returns the session; there is no unowned case to hide behind `404` anymore.
- **Verify:** `check.sh`; every endpoint reachable with no `Authorization` header;
  `dotnet list package` shows the JWT/Identity packages gone.

## Task 3 — Strip auth from the console client
- **Files:** `src/Harken.Console/Program.cs`, `src/Harken.Core/Client/TokenStore.cs`
  (delete), `src/Harken.Core/Client/ISecretStore.cs` (delete if console-only —
  confirm mobile's `SecureStorageSecretStore` has no other use first).
  `tests/Harken.Core.UnitTests/TokenStoreTests.cs` (delete).
- **Change:** Drop the login prompt, the `Authorization` header on every call, and the
  re-login-on-401 path in `RecordAndUploadAsync` (no longer reachable — nothing 401s).
  App goes straight to the R/L/Q menu on start.
- **Verify:** `check.sh`; manual run — console starts with no credential prompt,
  record/upload/list all work.

## Task 4 — Strip auth from the mobile client
- **Files:** `src/Harken.Mobile/Pages/LoginPage.xaml(.cs)` (delete),
  `src/Harken.Mobile/Services/AuthService.cs` (delete),
  `src/Harken.Mobile/Services/SecureStorageSecretStore.cs` (delete if nothing else
  uses `ISecretStore` after Task 3), `src/Harken.Mobile/MauiProgram.cs`,
  `AppShell.xaml(.cs)`.
- **Change:** App opens directly on the Sessions/Capture tab, no Login page in the
  shell. Every HTTP call drops the bearer header.
- **Verify:** `dotnet build -f net10.0-android`; manual — app opens with no login
  screen, Sessions/Summarize work against the now-open API.

## Task 5 — Integration tests
- **Files:** `tests/Harken.Api.IntegrationTests/` — every test asserting `401` or
  ownership-hiding (`404` for another user's id) gets rewritten or deleted, since
  there's no "another user" left. `CustomWebApplicationFactory.cs` — drop any
  auth-header setup helper.
- **Change:** Rewrite the surviving assertions to what's actually true now (any id
  reachable, no header required). **State the resulting test-count drop explicitly** —
  per slice 01's own precedent, a drop here is expected, not a regression to hide.
- **Verify:** `test-fast.sh` green; test count before/after recorded in this doc's
  Status section.

## Task 6 — Docs
- **Files:** `README.md`, `docs/setup.md` (drop the `Jwt:Key` required-secret section
  and the register/login curl examples), `docs/plans/slice-03-identity-and-ownership.md`
  (add a superseded-by note, same pattern as slice-02's superseded notes).
- **Change:** README's auth-required framing, the "404 not 403" ownership line, and the
  register/login walkthrough all come out. Replace with: no login, LAN-open, one
  implicit user.
- **Verify:** fresh-eyes read; no doc still claims a login step exists.

---

## Exit criteria
`check.sh` and `test-fast.sh` green. Backend serves every endpoint with no
`Authorization` header. Neither client shows a login prompt. `dotnet ef database
update` on a fresh DB has no Identity tables and no `Session.UserId`. No doc claims
authentication exists in MVP 1.

## Status

**Done. Merged to `master`** (folded into the same branch history as
[slice 06](slice-06-mobile-recording.md), which was built directly on top of this work
and merged fast-forward on 2026-08-18). All six tasks landed: Identity/JWT dropped from
schema and API, both clients strip auth, integration tests rewritten for the no-owner
world, docs updated. `check.sh` and `test-fast.sh` are green on `master` with no
`Authorization` header anywhere in the stack.
