# ARC-025 — Flows keep collecting while the app is in the background

- **Severity:** medium
- **Status:** open
- **Area:** `ui/` (7 call sites)

## Problem

Every flow in the UI is read with `collectAsState()`. `collectAsStateWithLifecycle`
appears nowhere. `collectAsState` binds collection to the *composition*, which
survives the app going to the background, so the amplitude meter's
`StateFlow<Int>` — updated 6.25 times a second by the recording service —
continues to push recompositions to a screen nobody is looking at, for the
whole recording.

This is the recomposition budget being spent on nothing, on the one code path
where the app is already doing continuous work, on battery.

## Fix

`collectAsStateWithLifecycle()` throughout (already available via
`lifecycle-runtime-compose`, which the project depends on).
