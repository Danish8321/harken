# UI-015 — Library list stagger-in

- **Severity:** low
- **Status:** fixed
- **Area:** `ui/LibraryScreen.kt`

## Problem

Library list rows appear all at once on entry/load — no sense of the
list assembling, which reads flat compared to the rest of the motion
work in this pass.

## Fix

Rows fade/slide up in sequence on entry (list load or tab arrival),
staggered by index with a small per-row delay, using
`HarkenMotion.spatialFast()`/`effectsFast()`. Cap the stagger so a long
list doesn't take visibly long to finish (e.g. only stagger the first
N visible rows, rest appear together). Must collapse to instant under
reduced motion.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: navigate to Library with several recordings present,
  confirm visible stagger on entry; scroll away and back, confirm it
  doesn't re-stagger obnoxiously on every scroll (only on genuine
  list-load/tab-arrival).
- Reduced motion on: confirm rows appear instantly, no stagger.

## Resolution

`LazyColumn`'s `items(...)` swapped for `itemsIndexed(...)`. Each row
is wrapped in `AnimatedVisibility` (`fadeIn` + `slideInVertically` on
`HarkenMotion.effectsFast()`/`spatialFast()`), gated by a per-row
`shown` state that a `LaunchedEffect` flips true after a
`index.coerceAtMost(STAGGER_CAP) * STAGGER_STEP_MS` delay
(`STAGGER_CAP = 8`, `STAGGER_STEP_MS = 35`), so the 9th+ row and beyond
all land at the same capped delay instead of the stagger stretching out
for a long list.

A `remember { mutableStateSetOf<UUID>() }` (`animatedIds`) held at
`LibraryScreen` scope, above the `LazyColumn`, tracks which session ids
have already played their entrance; each row's `LaunchedEffect` checks
membership before scheduling the delay and records itself in the set
either way. `LazyColumn` disposes a scrolled-off row's composition and
recomposes it on scroll-back, but since `animatedIds` lives above the
list it survives that — the row finds itself already in the set and
renders `shown = true` immediately, no replay, matching the ticket's
"don't re-stagger on every scroll" requirement.

Reduced motion is checked twice for different reasons: `HarkenMotion`'s
tokens already collapse to `snap()` so the enter transition itself has
no motion, but the artificial `delay()` before `shown` flips true is
plain `kotlinx.coroutines.delay`, not an animation spec — it doesn't
auto-collapse, so `LocalReducedMotion.current` is read directly and the
delay/stagger is skipped outright (row marked `shown` and added to
`animatedIds` immediately) when true.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: navigated Onboarding -> Library. No reachable backend
  this pass (`localhost:5057` refused, same constraint as UI-012/013),
  so `state.error != null && sessions.isEmpty()` takes the error-state
  branch ahead of the list branch — the stagger itself has no session
  rows to animate on this device/session and wasn't visible on-screen.
  Confirmed no crash and the error state renders correctly with the new
  imports/logic in place.

**Not verified:** the actual stagger animation, its scroll-away/back
no-replay behavior, and the reduced-motion instant-appear on-device —
all source-review only, blocked on the same unreachable-backend
constraint noted in UI-012/013 (no local sessions to populate the
list). The `animatedIds`/`itemsIndexed`/capped-delay logic was traced
by hand against `LazyColumn`'s known dispose/recompose behavior for
off-screen items.
