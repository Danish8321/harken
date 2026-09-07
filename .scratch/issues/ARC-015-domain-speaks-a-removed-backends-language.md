# ARC-015 — The data layer still speaks the language of a backend that no longer exists

- **Severity:** medium
- **Status:** done
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

## Written, 2026-09-07 — branch `arc-015-audio-path-rename`

`pendingUploadPath` is `audioPath`. `isLocalOnly`, `source`, `hasSummary` and `syncedAt`
are gone — the last three because `SessionRepository` already promised in a comment that
they "drop out with the rename migration (ARC-015)", and the rebuild that the rename needs
drops them at no extra cost. Database version 3, `MIGRATION_2_3`, schema `3.json` exported.

The migration is a table rebuild, not four `ALTER TABLE`s, and not by preference: minSdk
is 26 and neither statement exists there. SQLite gained `RENAME COLUMN` in 3.25 (API 30)
and `DROP COLUMN` in 3.35 (API 34); API 26 ships 3.18. So the rebuild *is* the rename —
every surviving column is named on both sides of the copy and `pendingUploadPath` is read
into `audioPath` by name, which is the create-copy-drop-rename fallback `schema.sh` names.

`SessionDatabaseMigrationTest` gained two tests. The 2→3 one is the one that matters: it
writes a row with a real audio path and a row with a null one, migrates, and asserts the
path *value* arrives rather than that a column of that name exists — a generated
drop-and-add passes the second check and fails the first, and the path is the only pointer
to a recording's WAV. The 1→3 one runs the chain a never-updated phone will actually run.

Gates at this tree: `check.sh` OK, `test-fast.sh` OK (152 tests, 0 failures, 22 files),
`schema.sh` shows version 3 and warns about no already-shipped schema.

### Proven, 2026-09-07

`test-full.sh` OK on an SM-E625F running Android 13. Nine instrumented tests, no failures,
no skips — the first time that gate has ever actually run rather than correctly refusing
to. All three migration cases passed, including
`migration2To3CarriesEveryRecordingsAudioPathIntoTheRenamedColumn`, which is the one that
would have caught a drop-and-add: it asserts the audio path *value* arrives at version 3,
not that a column of that name exists.

### Found while doing this

Two bugs in `schema.sh` itself, both hit on its first real use — fixed on master in
`4adb34b`, not here. It reported "Nothing" about a schema it had never re-exported: `git
diff` cannot see a new untracked `N.json`, and KSP was `UP-TO-DATE` because
`room.schemaLocation` sits outside its declared outputs.

### Noted, not done

The `summaries` table and `SummaryRow` are equally dead — ADR-0011 removed summaries and
nothing reads either. `hasSummary` dropped here; the table it referred to did not, because
this ticket does not name it and dropping a table is its own decision. Worth a ticket.

