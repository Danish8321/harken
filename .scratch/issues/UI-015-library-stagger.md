# UI-015 — Library list stagger-in

- **Severity:** low
- **Status:** open
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
