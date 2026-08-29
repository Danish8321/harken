# UI-014 — Persistent recording indicator across tabs

- **Severity:** medium
- **Status:** fixed
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

## Resolution

Read directly from `RecordingState.isRecording` (process-wide singleton
`StateFlow<Boolean>`) in `MainHost` rather than the ticket's suggested
hoist-to-ViewModel route — no new wiring needed since it's already
globally observable, same pattern `RecordScreen.kt`'s `LiveMeter`
already uses for `RecordingState.amplitude`.

Per tab: `showLiveDot = tab.route == Routes.Record && isRecording &&
!selected` — dot only shows on the Record tab item, only while
recording, only when the user isn't already on Record (the live button
itself is the indicator there, a second dot on top would be noise).
Rendered as a small `stateLive`/`accent` nested-circle dot, top-end of
the tab icon, `AnimatedVisibility` with `scaleIn/scaleOut +
fadeIn/fadeOut` on `HarkenMotion.spatialFast()`/`effectsFast()` for its
own enter/exit.

One build hiccup en route: the `AnimatedVisibility` call sits inside
`NavigationBar { tabs.forEach { NavigationBarItem(icon = { ... }) } }`
— `NavigationBar`'s content lambda carries an implicit `RowScope`
receiver, and Kotlin's overload resolution picked up
`RowScope.AnimatedVisibility` instead of the top-level one even though
the call is nested inside the `icon` lambda, not `RowScope` itself.
Fixed by fully-qualifying the call as
`androidx.compose.animation.AnimatedVisibility(...)` at that site.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: granted mic permission, started a real recording from
  Record; switched to Library — live dot visible on the Record tab
  icon; switched to Settings — dot still visible there too; switched
  back to Record — dot gone, real live view/button in its place;
  stopped the recording — switched to Library again, dot cleared
  everywhere. Full lifecycle confirmed visually via screenshots at each
  step.

**Not verified:** reduced-motion for the dot's own enter/exit
specifically wasn't separately re-checked this pass — it uses the same
`HarkenMotion`-token-collapses-to-`snap()` mechanism already confirmed
working elsewhere (UI-006, UI-013).
