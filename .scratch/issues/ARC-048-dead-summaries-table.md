# ARC-048 — The `summaries` table has no readers or writers

- **Severity:** low
- **Status:** open
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
