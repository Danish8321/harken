# ARC-047 — Deleting a recording leaves its transcript in the database forever

- **Severity:** high
- **Status:** fixed
- **Area:** `data/local/SessionDao.kt`, `data/SessionRepository.kt`

## Problem

`SessionDao.deleteSession` only runs `DELETE FROM sessions WHERE id = :id`
(`SessionDao.kt:142-143`). There is no `@ForeignKey` cascade from `sessions`
to `SegmentRow`, and `SessionRepository.purge()`
(`SessionRepository.kt:251-267`) never calls `clearSegments(id)` — that
method is only ever reached from `replaceSegmentsAtomically`, when a fresh
transcription lands, never from the delete path.

Every segment row (the actual transcript text) for a "deleted" recording
stays in `harken-local.db` permanently. This isn't just unbounded DB
growth: `purge()`'s own doc comment says "delete is the one operation a
user expects to be final," and this app's stated pitch is that nothing
leaves the phone and deletion is final. A user who deletes a recording for
its content still has that content sitting in the database.

## Fix

Delete segments in the same transaction as the session row, e.g.:

    @Transaction
    suspend fun deleteSessionAndSegments(id: UUID) {
        clearSegments(id)
        deleteSession(id)
    }

and call that from `purge()` instead of `deleteSession` directly.

## Found by

Fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Added `SessionDao.deleteSessionAndSegments` — `@Transaction`, calls `clearSegments`
then `deleteSession` — and pointed `SessionRepository.purge()` at it instead of the bare
`deleteSession`. `deleteSession` had exactly one caller, so nothing else needed to change.

New instrumented test `SessionDeleteTest.deletingASessionDeletesItsSegmentsToo` inserts a
session with two segments, deletes it, and asserts both `segmentsOnce` is empty and
`findById` is null against real SQLite (in-memory Room), not just a mocked call.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK (device `AIN065 - 16`, fresh
install, full instrumented suite including the new test).
