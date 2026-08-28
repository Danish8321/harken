# UI-014 — Persistent recording indicator across tabs

- **Severity:** medium
- **Status:** open
- **Area:** `ui/AppNav.kt` (`MainHost`), recording state source (`CaptureViewModel`)

## Problem

If a recording is in progress and the user switches to Library or
Settings, there's no indication in the nav bar that Harken is still
capturing. Easy to lose track, especially on a long field recording.

## Fix

Hoist recording-active state to `MainHost` (or a shared ViewModel scope
reachable from it) and render a small live pill/dot on or near the
Record tab item — riding along in the nav bar — whenever a capture is
active and the user is not on the Record tab. Uses `stateLive` color
role (UI-009) and `HarkenMotion.effectsFast()`/spatial tokens for its
own enter/exit, consistent with the rest of the state-indicator
language.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: start a recording, switch to Library then Settings, confirm
  the indicator shows on the Record tab in both; switch back to Record,
  confirm it's gone (subsumed by the actual live view); stop recording,
  confirm indicator clears everywhere.
