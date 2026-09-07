# ARC-015 — The data layer still speaks the language of a backend that no longer exists

- **Severity:** medium
- **Status:** blocked — needs a device
- **Area:** `data/local/SessionDao.kt`, `data/local/LocalModels.kt`, `data/SessionRepository.kt`

## Problem

[ADR-0011](../../docs/adr/0011-on-device-transcription.md) removed the server.
The schema and DAO it was designed around are still here, and they are actively
misleading:

- **`pendingUploadPath`** is now the path of the recording's audio file. Nine
  call sites read it as "the WAV". Its name says it is a queue entry for an
  upload that cannot happen.
- **`isLocalOnly`** is true for every row that can exist. `MIGRATION_1_2` was
  written to add it.
- **`upsertMirrored` / `updateMirroredFields` / `setPendingUpload`** —
  "mirrored" meant "mirrored from the server". Nothing mirrors anything.

Naming is the cheapest documentation in the codebase and this is the one place
it lies. Anyone reading `SessionEntity` concludes the app syncs.

## Fix

Rename `pendingUploadPath` to `audioPath` and delete the sync-era DAO surface
and `isLocalOnly`. **The rename must be expressed as a rename in the
migration** — a generated drop-and-add destroys every user's audio path. Show
the migration before applying it (`.claude/scripts/schema.sh`).

## Blocked on

The rename must be expressed as a rename in the migration, and the evidence that it was
is `SessionDatabaseMigrationTest` — an instrumented test. ARC-023 built the gate that runs
it (`.claude/scripts/test-full.sh`), but no device is attached to this machine, so the
gate has only been observed failing correctly. Writing a migration over a column that
holds every user's audio path without being able to run the test that proves it preserves
them is exactly the case CLAUDE.md's "data is irreversible" rule exists for.

Unblocks the moment a phone is plugged in.

## Progress — the gate exists now

`.claude/scripts/schema.sh` written, 2026-09-07. It was the half of this ticket that
needed no device, and it was the more urgent half: CLAUDE.md named it as the only route
for a schema change and it did not exist, so every schema change so far went through no
route at all.

It applies nothing. It re-runs Room's export from the entities as they are written right
now, diffs the result against what is committed, prints it, and states the three things
that must be true before the change ships — that a rename is written as
`ALTER TABLE … RENAME COLUMN` rather than left to a generated drop-and-add, that the
version is bumped and the old schema file left untouched, and that `test-full.sh` passes
on a phone.

One check is machine-made rather than left to the reader: if the export modified a
schema file already committed for a shipped version, the entities no longer describe the
version they are numbered as, and Room will refuse to open every existing install. The
script says so by name.

Verified both ways — clean tree reports "the entities produce the schema that is
committed", and a probe rename of one column produced the column-level diff and the
version-bump warning. The probe was reverted; nothing in `app/schemas` changed.

The rename itself is still device-blocked, for the reason above.
