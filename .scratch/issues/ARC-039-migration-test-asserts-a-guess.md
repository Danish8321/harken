# ARC-039 — The migration test asserts against a v1 schema typed out by hand

- **Severity:** medium
- **Status:** done
- **Area:** `data/local/SessionDao.kt`, `app/build.gradle.kts`, `app/schemas/`,
  `androidTest/.../SessionDatabaseMigrationTest.kt`

## Problem

`HarkenDatabase` was declared `exportSchema = false`, so no record existed of
what any shipped version of the database actually looked like. The migration
test worked around that by building v1 itself:

```kotlin
// exportSchema is false for this database (no schema-bundle scaffolding exists
// yet), so rather than androidx.room.testing.MigrationTestHelper (which needs
// an exported schema JSON), this builds a real v1-shaped "sessions" table by
// hand
db.execSQL("CREATE TABLE IF NOT EXISTS sessions (...)")
```

A test that types out the old schema tests the author's memory of it. It agrees
with the migration by construction and can disagree with the phone: if v1 as
shipped had a different column, a different nullability or a different default,
this test passes and the upgrade crashes on a user's device. It is the one
tier where being wrong destroys data, and it was the tier with no source of
truth.

Two further consequences:

- Nothing checked the *result* of the migration against the v2 entities. A
  migration can run cleanly and still leave a table Room refuses to open.
- ARC-015 (the rename migration) cannot be written safely without this. A
  rename must be expressed as a rename, and reviewing that requires knowing
  exactly what the previous version was.

## The fix

1. `exportSchema = true`, with `room.schemaLocation` pointed at
   `app/schemas` through KSP, so every build writes the shape to JSON.
2. Reconstructed `1.json` by temporarily reverting `SessionRow` to its v1 shape
   (drop `isLocalOnly`, `version = 1`) and letting Room export it, rather than
   writing the JSON by hand — the same reason the test should not write SQL by
   hand. Verified against the v1 `CREATE TABLE` the old test contained: same 13
   columns, same order, same types.
3. Wired `app/schemas` into the `androidTest` assets so `MigrationTestHelper`
   can read it on the device.
4. Rewrote the test to `helper.createDatabase(name, 1)` +
   `runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)`, deleting the
   hand-written `CREATE TABLE`. `validateDroppedTables = true`, so a migration
   that loses a table fails here.
5. Added `assembleDebugAndroidTest` to `check.sh`. The instrumented tests need a
   device to run, but nothing was even *compiling* them, so a refactor could
   break them invisibly. Assembling the test APK needs no device.

## Evidence

`check.sh` and `test-fast.sh` both green. The test itself is compiled but has
never been run: `testDebugUnitTest` does not run instrumented tests, and no
device is attached. Running it is part of the outstanding `test-full.sh` work,
tracked with the rest of the device verification.

## Follow-on

The committed `1.json` and `2.json` are now the record of the shipped shapes.
Every future schema change must land its JSON in the same commit, which is what
`schema.sh` (ARC-015) will enforce.
