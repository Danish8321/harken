# ARC-057 — `segments.sessionId` has no index, so opening a recording scans every transcript on the phone

- **Severity:** medium
- **Status:** fixed
- **Area:** `data/local/LocalModels.kt`, `data/local/SessionDao.kt`

## Problem

Neither table declares an index. The exported schema says so at every version:

```
$ python -c "... json.load(open('schemas/.../4.json')) ..."
sessions indices: None
segments indices: None
```

`sessions` does not need one — every query against it is either the whole table or a
primary-key lookup. `segments` does. Its only index is the primary key on `id`, and
nothing looks a segment up by its own id. What every query filters on is `sessionId`:

| query | when it runs |
|---|---|
| `observeSegments(id)` | every time a session sheet opens, and again on every transcript change |
| `segmentsOnce(id)` | once per session inside `exportItems` |
| `clearSegments(id)` | on every transcription completion, and on every delete |

Without an index on `sessionId`, SQLite has no way to find a session's rows except to read
the whole `segments` table and test each one. That table is the transcripts — the largest
thing in the database and the only one that grows without bound.

The cost is invisible today and not later. A hundred recordings of a few minutes each is on
the order of ten thousand segment rows; every sheet open reads all of them to show one
recording's hundred. `SessionRepository.exportItems` is worse by a factor of N — it calls
`segmentsOnce` once per session inside a `map` over `allSessions()`, so exporting a
hundred-recording library is a hundred full scans of that table, which is also why its
"walks the whole library once" doc comment is not true.

Not a hypothetical read of the schema. `EXPLAIN QUERY PLAN` on 'AIN065', against the
database Room builds from these entities, before anything was changed:

```
reading one session's segments ... SQLite planned: SCAN segments | USE TEMP B-TREE FOR ORDER BY
clearing one session's segments ... SQLite planned: SCAN segments
```

Not a data-loss bug and not user-visible yet, which is why it is medium and not high: the
phone this was found on holds one recording. It is the kind of thing that is free to fix
now and a migration over real user data later.

## The fix

An index, added the same way every other schema change here is: bumped `@Database` version,
a hand-written additive migration, `schema.sh` to review the exported shape, and
`SessionDatabaseMigrationTest` to prove it against a real SQLite.

```kotlin
@Entity(tableName = "segments", indices = [Index("sessionId")])
```

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_sessionId` ON `segments` (`sessionId`)")
    }
}
```

The index name is not free-form: Room derives `index_<table>_<column>` from the annotation
and `runMigrationsAndValidate` compares it, so the migration must write that exact name or
the validation fails on a database that is otherwise correct.

`CREATE INDEX` is safe on a populated table — it reads every row once and writes a new
b-tree, touching no user data. It cannot be the drop-and-add that `schema.sh`'s first note
warns about, because there is no column involved.

A covering index on `(sessionId, offsetSeconds)` was considered and rejected. Every one of
those three queries either orders by `offsetSeconds` or deletes, so the second column would
let the two reads skip their sort — but a session's segments are a few hundred rows once
they are found, sorting them is nothing next to the scan being removed, and the wider index
costs write time on every transcription. The single column buys the whole difference.

FTS is not this ticket. `searchSegments` scans `segments` by design — a `LIKE '%term%'`
cannot use an index on any column — and `SessionDao` already records why that trade was
taken and what evidence would reverse it.

## Evidence

`SegmentIndexTest` is the gate, and it asserts SQLite's own plan rather than a duration — a
timing on a test database would only prove the test database is small. It failed before the
change with the two `SCAN segments` lines quoted above, and passes after.

`schema.sh`: one new `5.json`, `4.json` untouched, which is the shape the script's own note
3 requires. Room's exported `createSql` for the index is character-for-character the
statement `MIGRATION_4_5` executes.

`bash .claude/scripts/test-full.sh` on 'AIN065': **15 tests, 0 failures** (11 before —
`SegmentIndexTest`'s 3 and the new migration test). `check.sh` OK, `test-fast.sh` OK.

Falsified by emptying `MIGRATION_4_5` to `SELECT 1`: exactly
`migration4To5IndexesSegmentsBySessionIdAndKeepsEveryTranscriptLine` and
`migration1To5RunsTheWholeChainAndKeepsTheRow` fail, and nothing else (15 tests, 2
failures). `SegmentIndexTest` still passes there, correctly — it builds from the entities,
so it proves the index exists on a fresh install and the migration tests prove it arrives on
an upgrade. Neither substitutes for the other.

The shipped database on the phone, pulled with its WAL and read with sqlite3:

```
user_version 5
index: index_segments_sessionId
EXPLAIN QUERY PLAN SELECT * FROM segments WHERE sessionId=? ORDER BY offsetSeconds ASC
  -> SEARCH segments USING INDEX index_segments_sessionId (sessionId=?)
```

Read that for exactly what it is: a **fresh** version-5 database, not an upgraded one. The
app's uid moved from `u0_a390` to `u0_a398` between the two checks, because
`connectedDebugAndroidTest` uninstalls the APKs when it finishes and `installDebug` then put
a new install down. So this confirms the index is there on a first install and that SQLite
uses it on the real device's own SQLite build — and it says nothing about the migration
running over existing rows, which is `SessionDatabaseMigrationTest`'s job and is why that
test asserts the surviving segment as well as the index name.

`migration1To3RunsTheWholeChainAndKeepsTheRow` was renamed to `…1To5…` and now runs to the
current version. It had stopped at 3 while the database was at 4, so the test named for the
upgrade a never-updated phone performs was not performing it.

Also corrected: `exportItems`' doc said it "walks the whole library once". It is one query
plus one per session. The N+1 stands — with the index each of those is a cheap keyed lookup
— but the comment now says what the code does.

