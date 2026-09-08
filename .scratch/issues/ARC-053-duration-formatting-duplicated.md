# ARC-053 — Duration formatting is reimplemented ad hoc in three places

- **Severity:** low
- **Status:** fixed
- **Area:** `ui/LibraryScreen.kt`, `ui/SessionSheetViewModel.kt`

## Problem

Three inline duration formatters, none hours-aware, despite the app supporting recordings
up to 3 hours (`RecordingForegroundService.kt:156`):

- `LibraryScreen.kt:468` — `" · ${it / 60}m ${(it % 60).toString().padStart(2, '0')}s"`
- `LibraryScreen.kt:372` — `"${at / 60}:${(at % 60).toString().padStart(2, '0')}"`
- `SessionSheetViewModel.kt:392` — `"${duration / 60}m ${(duration % 60).toString().padStart(2, '0')}s"`

Two hours-aware formatters already exist in the same feature set — `TranscriptText.timestamp()`
(data layer, used for exports/share) and `RecordScreen.formatElapsed()` (live timer, and via
same-package visibility `SessionSheet.kt:547`'s transcript-row timestamps). A 2-hour
recording shows `"125m 30s"` in the Library card and session sheet meta line, but `"2:05:30"`
in the live timer or a search result deep-linking into it — the same figure, three
inconsistent renderings, not wrong data but a real user-visible DRY violation.

## Fix

Route all three call sites through `TranscriptText.timestamp()` (or a shared formatter),
removing the duplicated inline computations.

## Found by

Third fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Routed all three call sites through `TranscriptText.timestamp()`. This is a visible copy
change (`"5m 30s"` -> `"5:30"`, `"125m 30s"` -> `"2:05:30"`) but that's the fix: one
formatter, one rendering, matching what the live timer and transcript rows already show.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK.
