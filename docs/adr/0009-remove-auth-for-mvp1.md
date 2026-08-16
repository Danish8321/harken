# 9. Remove authentication and ownership for MVP 1

Date: 2026-08-16

## Status
Accepted. Supersedes [ADR-0004](0004-identity-and-ownership.md).

## Context
ADR-0004 retrofitted ASP.NET Core Identity, JWT bearer auth, and per-user ownership
ahead of the stated "family, then wider" direction. That direction hasn't moved: MVP 1
is still one person's console and phone, on one LAN, with no second account ever
created. The auth machinery has been carried since slice 03 — login pages on both
clients, `SecureStorage` token handling, `[Authorize]`/`RequireAuthorization()` on
every endpoint, the "404 not 403" ownership-hiding behavior — without a single real
multi-user scenario to justify it.

Slice 06 (mobile recording) is next, and every new endpoint it touches would otherwise
inherit this machinery for a use case — a second person's data — that doesn't exist
yet. Carrying unused isolation code is the same shape of mistake ADR-0007 already made
once with live captioning: building for a future that isn't the current problem.

## Decision
**MVP 1 has no authentication and no ownership model.** One implicit user. The backend
is reachable by anything on the LAN that can route to the host — the same trust
boundary the README already assumes for the phone's base-URL setup.

Removed: ASP.NET Core Identity (and its tables), JWT issuance/validation, login pages
on console and mobile, `SecureStorage` token handling, `RequireAuthorization()` on
every endpoint. `GET /sessions/{id}` on any id returns the session — there is no
"unowned" case left to hide behind a 404.

**Auth returns once MVP 1 proves out** — explicitly deferred, not abandoned. ADR-0004's
reasoning about ownership being cheapest to retrofit before real data exists still
holds; it just applies to whenever auth actually comes back; not now.

## Consequences
- Deletes a real amount of working, tested code (Identity setup, JWT middleware, both
  login pages, ownership-filtered queries) — same trade ADR-0007 made deleting live
  captioning: dormant-until-needed machinery rots and misleads more than its absence
  costs.
- Any recording made against the API is visible to anything on the LAN. Acceptable for
  a personal/single-household tool on a home network; a real problem the moment a
  second household or any untrusted network is in play — which is exactly why auth
  returns before then, not after.
- Migration drops the `UserId` FK on `Session` and the Identity tables outright, not
  nullable-and-unused. There is no real recorded data yet (same condition ADR-0004
  itself relied on), so a clean drop is honest instead of leaving dead schema.
- Every existing integration test asserting 401/404-for-unowned needs rewriting or
  deleting — a real drop in test count, to be stated plainly, not glossed over (per
  slice 01's own precedent for hub-test removal).
- Simplifies slice 06: mobile recording ships with no re-login-on-401 flow to build at
  all.

## Alternatives considered
- **Keep auth, just don't build a second account.** Zero code deleted, but keeps
  every future change paying the tax of threading a token through, for isolation
  nobody is using. Rejected — the same reasoning ADR-0007 used against live captioning.
- **Fold this removal into slice 06** rather than its own slice. Rejected on sequencing
  grounds — auth touches every endpoint and both clients; mixing it with new
  on-device-recording work makes both harder to review and harder to revert
  independently.
