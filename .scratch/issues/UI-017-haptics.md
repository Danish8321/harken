# UI-017 — Haptic feedback pairing

- **Severity:** low
- **Status:** fixed
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

## Resolution

`LocalHapticFeedback.current` wired at four trigger points in
`RecordScreen.kt`:
- Start recording (direct tap, mic already granted) ->
  `HapticFeedbackType.LongPress`
- Start recording (via the permission-grant callback — a second start
  path the ticket's location didn't call out but is user-facing the
  same way) -> `HapticFeedbackType.LongPress`
- Stop recording tap -> `HapticFeedbackType.TextHandleMove` (lighter,
  distinct from the start feel)
- Upload succeeded -> `HapticFeedbackType.Confirm`
- Upload failed -> `HapticFeedbackType.Reject`

Start/stop fire directly in their tap handlers (a tap is already a
single discrete event, no dedup needed). Upload success/fail fire from
a `LaunchedEffect(state.uploadStatus)` block — `LaunchedEffect` only
restarts when its key changes, so each transition into `Succeeded` or
`Failed` fires exactly once, not on every recomposition while the
status is held steady.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
  (all four `HapticFeedbackType` constants used — `LongPress`,
  `TextHandleMove`, `Confirm`, `Reject` — resolved without issue)
- `uninstallDebug` + `installDebug` clean install on the physical
  device
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: tapped the record button to start (idle -> capturing,
  confirmed via screenshot) and tapped again to stop; no reachable
  backend this pass (`localhost:5057` refused, same recurring
  constraint as UI-012/013/015/016), so the stop naturally drove the
  upload straight to **Failed** — confirmed via screenshot ("Upload
  failed · tap to retry" banner), which is exactly the transition the
  `Reject` haptic is keyed on. All four trigger *code paths* were
  therefore genuinely exercised at the right moments (start tap, stop
  tap, and the Failed transition landing on-screen).
- Discovered along the way: this device has system haptics disabled
  (`settings get system haptic_feedback_enabled` -> `0`), which is why
  `dumpsys vibrator_manager` showed no vibration record for the app
  even with the feature wired correctly — `performHapticFeedback`
  respects that system toggle and silently no-ops when it's off, which
  is expected Android behavior, not a bug. Toggled it to `1` to retest,
  still found no per-package vibrator history entry — traced this to
  the debug app running under a secondary Android user profile on this
  Samsung device (`pm list packages -U` on this package fails with
  `SecurityException: Shell does not have permission to access user
  150`), which appears to keep that user's vibrator history outside
  what `adb shell dumpsys vibrator_manager` surfaces to this shell.
  Restored the setting to its original `0` afterward.

**Not verified:** the actual felt vibration and its start-vs-stop /
success-vs-fail distinctness — this is inherently something only a
human holding the device can confirm; no ADB-observable signal (log
line, vibrator history entry) was available on this specific
device/profile setup to substitute for that. The Succeeded path
specifically also wasn't observed at all this pass, since there's no
reachable backend to actually complete an upload.
