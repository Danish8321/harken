# UI-038 — The silence-timeout hint stays on screen while paused

- **Severity:** low
- **Status:** open
- **Area:** `ui/RecordScreen.kt`

## Problem

While a recording is paused the Record screen still reads:

    Stops after 5 min silence

Nothing is being captured while paused, so no silence is accumulating and the
auto-stop cannot fire. The line states a rule that is not running.

Observed on device, 2026-09-07, on an SM-E625F running Android 13: the status
line above it correctly flips to `PAUSED · NOT RECORDING` while this hint below
does not change.

It is worth more than its size suggests, because the pause is exactly when a
user wonders whether the app is about to end their session for them.

## Fix

Hide it while paused, or say what is actually true then — that a paused
recording waits indefinitely. Whichever reads better next to
`PAUSED · NOT RECORDING`, which is the line it sits under.

## Found by

The device sweep after ARC-015 closed. Pause/resume itself is correct: the
elapsed clock holds across the pause (`recording_paused elapsedMs=111224` /
`recording_resumed elapsedMs=111225`) and no silence is written into the WAV —
3711360 bytes over a 116397 ms session is exactly 32000 bytes/second with
nothing added for the 26 seconds spent paused.
