# UI-040 — Reopening the sheet for a different session can show the previous one's duration

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/SessionSheetViewModel.kt`

## Problem

`SessionSheetViewModel` is deliberately reused across sheet openings
(`SessionSheet.kt:132-134`). `load(id)`'s combine block preserves a
nonzero `playbackDurationMs` across re-emissions, so the decoder's exact
value survives later recomposition:

    playbackDurationMs =
        _uiState.value.playbackDurationMs.takeIf { it > 0 }
            ?: (duration * 1000),

That's correct for repeated emissions of the *same* session (e.g. while
it's still transcribing), but `stopPlayback()` (`SessionSheetViewModel.kt:201`)
resets `isPlaying`/`positionMs` on dismiss and never resets
`playbackDurationMs`. Playing session A, closing the sheet, then opening
session B: `load(B)`'s first emission sees A's stale nonzero
`playbackDurationMs` and keeps it, so `PlaybackCard`'s total-time label
shows A's duration next to B's transcript until the user taps play on B.

Self-correcting, not data-destructive, but wrong information on a normal
usage path (listen to one recording, then open another).

## Fix

Reset `playbackDurationMs` to 0 in `stopPlayback()` alongside
`positionMs`. `SessionSheet.kt` disposes the old `sessionId` key (running
`stopPlayback()`) before the new one's `LaunchedEffect` calls `load()`,
so this clears the stale value before the next session's first emission
without touching the same-session preservation the field exists for.

## Found by

Second fresh full-repo audit, 2026-09-08 (post-install verification pass
after ARC-045/046/047/048 and UI-039).

## Resolution, 2026-09-08

Added `playbackDurationMs = 0` to `stopPlayback()`'s state update, alongside the existing
`isPlaying`/`positionMs` reset.

No dedicated test: `SessionSheetViewModel` extends `AndroidViewModel` and drives a real
`MediaPlayer`, and this repo has neither Robolectric nor a mocking library configured — a
JVM unit test would need one added, which is out of scope for a one-line fix (no new
dependency without asking). Verified by reading `SessionSheet.kt:120,135`: `load()` and
`stopPlayback()` are both keyed on `sessionId` (`LaunchedEffect`/`DisposableEffect`), and a
key change disposes the old effect (running `stopPlayback()`) before the new one's
`LaunchedEffect` fires `load()` — so the reset lands before the next session's first
emission in every case, not just usually.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK (device `AIN065 - 16`).
