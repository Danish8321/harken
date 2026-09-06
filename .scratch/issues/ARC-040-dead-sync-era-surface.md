# ARC-040 — The sync tier is gone; its DAO surface and one of its chips are not

- **Severity:** medium
- **Status:** done
- **Area:** `data/local/SessionDao.kt`, `data/SessionRepository.kt`,
  `ui/LibraryScreen.kt`

## Problem

ADR-0011 made the app on-device only and the sync tier was removed, but the
persistence layer it was written for stayed. Five DAO methods had zero callers:

| Method | Written for |
|---|---|
| `insertIfNew` | mirroring a row the backend already had |
| `updateMirroredFields` | the same, on re-sync |
| `upsertMirrored` | the transaction wrapping those two |
| `setPendingUpload` | marking a WAV as awaiting upload |
| `observeTagStrings` | a tag filter that was never built |

They were not merely unused. `updateMirroredFields` carried a careful comment
about not clobbering `localTitle`, `localTags` or `pendingUploadPath` during a
sync — advice about a hazard that no longer exists, which is worse than no
comment, because the next reader spends time on it.

Three columns are write-only in the same way:

- `source` — every call site passes the literal `"Microphone"`, threaded through
  `createLocalSession` from three places as if it varied.
- `syncedAt` — stamped at creation, read by nothing.
- `hasSummary` — written `false` at creation and never set true since the
  summary feature went (see the commit that deleted `SummaryCard`).

`hasSummary` was worse than dead: `LibraryScreen` still branched on it.

```kotlin
s.hasSummary -> Triple(c.stateDone, c.stateDoneFg, R.string.library_chip_summarized)
```

A "Summarized" chip that no recording can ever earn — an unreachable branch
sitting in the middle of the chip logic every reader of that screen has to
step through.

## The fix

Deleted the five methods, the unreachable chip branch, the string it named, the
`hasSummary` field on `SessionView` and the `source` parameter on
`createLocalSession`. `source` is now written once, in the repository, next to
a comment saying what it and its two neighbours are waiting for.

The columns themselves stay. Dropping a column is a migration, and migrations
go through `schema.sh` (ARC-015) — the same ticket that owns the
`pendingUploadPath` rename, which is the opposite problem: a column that is very
much alive under a name that describes a tier that no longer exists.

## Evidence

`check.sh` and `test-fast.sh` green.
