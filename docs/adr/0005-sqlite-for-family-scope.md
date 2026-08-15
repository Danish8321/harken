# 5. SQLite for family scope

Date: 2026-08-15

## Status
Accepted

## Context
SQLite was never chosen. It appeared in the slice-01 plan as part of an assumed stack
("EF Core + SQLite") back when Harken was a single-user tool writing a file next to the
executable, and nothing was weighed against it. The scope has since moved — ADR-0004
made Harken multi-user, family first and public later — so the assumption is now
load-bearing without ever having been examined.

Three things have surfaced since:

- **Concurrent writers are now real.** Live captioning writes a Transcript Segment every
  few seconds for every active Session. SQLite serializes writes; two family members
  recording simultaneously contend on a single write lock.
- **The database is dictating query shape.** Three separate places now materialize a
  result set and sort it in memory because SQLite cannot translate `ORDER BY` on the
  column type: `TimeSpan` in `SummarizeAgent`, `DateTimeOffset` in `GET /sessions`, and
  `Offset` in `GET /sessions/{id}`. Each is cheap at current row counts and each is the
  wrong shape as transcripts accumulate.
- **There is no encryption at rest.** ADR-0004's public-exposure list requires it for
  audio and transcripts. Plain SQLite offers none; SQLCipher is a separate dependency.

## Decision
Keep SQLite for family scope. Record it as a deliberate choice rather than an
assumption, with the costs above accepted knowingly.

Revisit — and expect to move to PostgreSQL — when any of these becomes true:

- Harken is reachable from outside a trusted network (public release, or any cloud
  deploy), since a single file on a single host stops being deployable.
- More than roughly two people record concurrently on a regular basis.
- Encryption at rest is required.
- A query needs real server-side ordering or pagination over transcript segments — that
  is, the in-memory sorts stop being cheap.

The provider swap is deliberately kept cheap while it still is: all data access goes
through EF Core with no raw SQL and no SQLite-specific types, so the change is a
provider and a regenerated migration set, not a rewrite. That is only true today. It
stops being true once there are real family transcripts to migrate, which is precisely
why the triggers above are written down rather than left to judgement.

## Consequences
- Setup stays zero-install and offline: no server to run, one file to back up, which
  suits both the learning-project half and family-on-LAN.
- The three in-memory sorts stay, and each carries a comment saying why. New code must
  not assume `ORDER BY` translates — check the column type against the provider first.
  A fourth instance is a signal the trigger has been hit, not a fourth workaround.
- Write concurrency is a known ceiling, not a bug to be debugged when it appears.
- The public-release path now has a named database task instead of an implicit one.

## Alternatives considered
- **PostgreSQL now:** the eventual destination, and it removes the ORDER BY tax and the
  write ceiling immediately. Rejected for now: it adds a server to run and back up for
  a household that does not yet need it, and the migration is genuinely cheap later
  because the EF Core seam is clean. Reconsider at the first trigger, not before.
- **SQLite + SQLCipher:** solves encryption at rest without leaving SQLite, but leaves
  the concurrency ceiling and the single-host constraint untouched. Worth it only if
  encryption is needed while still family-scoped.
- **A document store:** transcripts look document-shaped, but Sessions, ownership, and
  Identity are relational, and the summaries are derived data. Not worth a second
  storage model.
