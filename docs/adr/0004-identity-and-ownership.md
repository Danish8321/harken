# 4. Identity and per-user ownership

Date: 2026-08-15

## Status
Accepted

## Context
Harken was initially built as a single-user personal tool: no authentication, and no
concept of a user anywhere in the domain. Every Session and Transcript Segment lived
in one undivided pile, and both the SignalR hub and the summary endpoint were
reachable by anyone who could route to the host.

The actual scope is larger: family members first, then a gradual public release. That
makes the missing ownership model — not the missing login — the real defect. A shared
password would authenticate several people into one shared pile of transcripts.
Meeting and lecture audio is sensitive; leaking it between household members is a
privacy failure, and at public scale it is a breach.

Retrofitting ownership is cheapest now, while there is no real recorded data. Once
family members have weeks of transcripts, assigning an owner to existing rows means
guessing who recorded what.

## Decision
- **Identity:** ASP.NET Core Identity with local accounts (email + password), issuing
  **JWT bearer tokens**. Users are stored in the same database as the rest of the
  domain. One scheme serves every client — MAUI app, console, and the future browser
  extension — with no redirect flow.
- **Ownership:** every `Session` carries an owner (the user id from the token).
  Transcript Segments inherit ownership through their Session.
- **Isolation:** private per user. Every read and write path filters by the
  authenticated user's id; a user can never reach another user's Session, Transcript,
  or Summary. There is no sharing model in this decision — sharing, if wanted later,
  is an additive decision on top of private-by-default.
- Both the SignalR hub and all REST endpoints require authentication.

## Consequences
- Ownership is enforced at the data layer from the start, so the eventual public
  release does not require re-architecting isolation.
- We take on password-storage responsibility (hashing is handled by ASP.NET Core
  Identity, but reset flows, email confirmation, lockout, and rate limiting become
  ours to get right before any public exposure).
- Clients must acquire and refresh tokens, and store them securely — on Android that
  means `SecureStorage`, not `Preferences`.
- A schema change adds an owner to `Session` plus the Identity tables. Existing
  development data has no owner and is discarded rather than migrated.
- Anonymous access is gone: the console proof client also needs credentials.

## Alternatives considered
- **External sign-in (Google/Microsoft OAuth):** removes password-storage burden, but
  the redirect flow is awkward in a console client and a browser extension.
- **Managed provider (Entra External ID / Auth0):** the strongest path to public
  scale, but adds a cloud dependency and a large set of new concepts mid-project.
- **Shared secret now, real auth before family rollout:** fastest to ship, but the
  ownership model would be built twice, and the second build is the expensive one.
