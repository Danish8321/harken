# UI-013 — Record screen micro-interactions

- **Severity:** medium
- **Status:** open
- **Area:** `ui/RecordScreen.kt`

## Problem

Record screen has some `AnimatedVisibility` fades already (upload
success/failed banners, the 10.5s cap-warning) but is otherwise static:
the idle meter doesn't move, the record button's idle->live change is a
plain state swap, and upload status (uploading -> done) just fades one
view out and another in rather than transitioning as one continuous
piece of feedback.

## Fix

- **Ambient idle animation**: idle meter bars breathe gently (subtle
  amplitude loop) rather than sitting static — echoes the splash mark's
  motif (UI-011), ties the two together. Must stop/collapse under
  reduced motion.
- **Record button morph**: idle (circle, accent fill) -> live (rounded
  square/stop shape, error fill) as a shape+color spring morph, not an
  instant swap. `MorphShapes.kt` already exists — check whether it
  already covers this or needs extending.
- **Upload status morph**: uploading spinner -> done tag transitions via
  a check-mark morph in place, not a fade-out/fade-in of two separate
  composables.
- **Retry shake**: failed-upload row gives a gentle shake/pulse on tap,
  in addition to (not replacing) the existing color-based failed state.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: idle screen (meter breathing visible), start/stop recording
  (button morph), a real or simulated upload success and failure (status
  morph, retry shake) — backend reachability permitting; source-review
  fallback if unreachable, same as prior tickets.
- Reduced motion on: confirm idle meter is static, button/status changes
  are instant, no shake plays.
