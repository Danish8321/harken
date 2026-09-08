# ARC-048 — The `summaries` table has no readers or writers

- **Severity:** low
- **Status:** fixed
- **Area:** `data/local/LocalModels.kt`, `data/local/SessionDao.kt`

## Problem

`SummaryRow` is declared as an `@Entity` and listed in the `@Database`'s
`entities = [...]` (`LocalModels.kt:50-55`), but `SessionDao` has zero
queries against the `summaries` table — no insert, select, or join
anywhere (`SessionDao.kt:259` is the nearest DAO code, with nothing
touching this table before or after it). ARC-040 already removed the dead
sync-era columns and DAO methods from `sessions`, but this is a distinct
table it left behind. It's pure schema weight and tells a future reader
summaries are a supported feature when nothing produces or consumes one.

## Fix

Drop the `summaries` table via a real migration through
`.claude/scripts/schema.sh` and delete the now-unused `SummaryRow` entity —
or, if summaries are a planned feature, say so next to the entity instead
of leaving it silent.

## Found by

Fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Deleted `SummaryRow`, dropped it from `@Database.entities`, bumped the version to 4, and
added `MIGRATION_3_4` (`DROP TABLE IF EXISTS summaries` — no minSdk gymnastics needed,
unlike `MIGRATION_2_3`'s rename, since `DROP TABLE` predates API 26 by a wide margin).
Registered it in `addMigrations`.

`bash .claude/scripts/schema.sh` reviewed: the new `4.json` differs from `3.json` only by
the `summaries` entity disappearing; `sessions` and `segments` are byte-for-byte the same.
New test `migration3To4DropsSummariesTableAndKeepsSessionsAndSegments` inserts a row into
each of the three v3 tables, runs the migration, and asserts `summaries` is gone from
`sqlite_master` while the `sessions` and `segments` rows survive.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK (device `AIN065 - 16`, fresh
install; all 4 `SessionDatabaseMigrationTest` cases passed, including the new one).
