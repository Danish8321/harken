# ARC-032 — There is no way to find anything

- **Severity:** high
- **Status:** fixed
- **Area:** `ui/LibraryScreen.kt`, `data/local/SessionDao.kt`

## Problem

Library offers four filters (All / Meetings / Field / Ideas) and no search. The
entire value of transcribing a meeting is being able to find the sentence
later; without search, a transcript is something you scroll.

This is the largest gap between what the app does and what a daily driver does.
It is also nearly free: the transcript segments are already rows in SQLite, and
Room supports FTS4 through `@Fts4` with a content table, so the query is a
schema change and a `WHERE ... MATCH ?`, not a new subsystem.

## Fix

An FTS-backed search over segment text and titles, surfaced as a field at the
top of Library, with the matching line shown on the result row and the
transcript opened scrolled to it. Schema change goes through
`.claude/scripts/schema.sh`; show the migration before applying.

## Resolution

A search field at the top of Library over transcript segments and user-typed titles.
Typing runs one query 180ms after the last keystroke (`collectLatest`, so a keystroke
cancels both the debounce and any query already in flight); results replace the session
list, each row showing the recording, the line that matched with the term highlighted,
the offset it sits at, and how many lines in that recording matched. Tapping a result
opens the transcript scrolled to that line, outlined so it is findable once the scroll
settles.

### Deviation from the fix as written: LIKE, not FTS4

The ticket said FTS4 with a content table. That was not built, deliberately:

* FTS4 with `contentEntity` means a virtual table plus five triggers, hand-written into a
  migration that runs over real recordings. The cost of getting it wrong is user data.
* FTS matches whole tokens. "record" would not find "recording" without the user typing a
  wildcard, which is not what a search field is expected to do. `LIKE '%term%'` matches
  the substring, which is.
* The scan it replaces is over segment rows already indexed by session, and its cost is
  now measured rather than assumed: every search emits `search chars=… segmentMatches=…
  sessions=… elapsedMs=…` (shapes only — never the term, ADR-0011). If that `elapsedMs`
  ever justifies an index, the telemetry says so, and the query is one method to swap.

YAGNI, with the measurement in place that says when it stops applying.

### Scope

* `data/SearchQuery.kt` — new. The two pure pieces: `likePattern` (escapes `%`, `_` and
  the backslash for `LIKE … ESCAPE ''`) and `snippet` (a window centred on the match,
  with the match's range in it).
* `data/local/SessionDao.kt` — `searchSegments`, `searchTitles`, `sessionsByIds`; new
  `SegmentMatch` projection in `LocalModels.kt`. No schema change, so no migration.
* `data/SessionRepository.kt` — `search()` returning `SearchHit` per session, newest
  first, each carrying its earliest matching line.
* `ui/LibraryViewModel.kt` — `LibrarySearchState`, held separately from `LibraryUiState`
  so a keystroke does not recompose every session card.
* `ui/LibraryScreen.kt` — the field (a `BasicTextField` in a pill, not a Material outlined
  box, which reads as a form control on this surface), the results list, the result card.
* `ui/AppNav.kt`, `ui/SessionSheet.kt` — the segment id travels with the session id, so a
  result opens the transcript at the line that matched.
* `res/values/strings.xml` — hint, clear, empty state, two plurals.

## Evidence

* `.claude/scripts/check.sh` — OK (dotnet build, assembleDebug, assembleRelease, lintDebug;
  no new Lint findings).
* `.claude/scripts/test-fast.sh` — OK, 113 tests, including 9 new `SearchQueryTest` cases
  covering wildcard escaping and the snippet window.
* `assembleDebugAndroidTest` — compiles. `SessionSearchTest` (new, 6 cases) proves the
  LIKE/ESCAPE behaviour against real SQLite: case-insensitive matching, newest-session
  ordering, `%` and `_` as literals, the read limit, titles, and `sessionsByIds`.

## Device verification

Not yet done: no device was attached when this landed (`adb devices` empty), so
`connectedDebugAndroidTest` and the manual pass — type a term, tap a result, confirm the
transcript opens on the matching line — are outstanding. Both are on the next device
session, alongside ARC-023's work to put the instrumented suite in a gate.
