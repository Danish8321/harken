# UI-017 — Haptic feedback pairing

- **Severity:** low
- **Status:** open
- **Area:** `ui/RecordScreen.kt`, `ui/CaptureViewModel.kt`

## Problem

No haptic feedback anywhere in the app. Cheap addition, meaningfully
raises perceived quality on Android, especially for a record-button tap
where audio confirmation isn't appropriate (would be picked up by the
mic).

## Fix

Use `HapticFeedback`/`LocalHapticFeedback` (Compose) at:
- Start recording tap
- Stop recording tap
- Upload succeeded (once, on transition to done)
- Upload failed (once, on transition to failed)

Distinct feedback types where the platform supports it (e.g.
`HapticFeedbackType.LongPress` vs a lighter tick) so start/stop don't
feel identical to success/fail. No haptic on every re-render — must fire
exactly once per state transition, not on recomposition.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device (physical device required — haptics don't emulate): tap
  start/stop, confirm distinct feel; trigger upload success/fail if
  backend reachable.
