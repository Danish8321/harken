# UI-013 — Record screen micro-interactions

- **Severity:** medium
- **Status:** fixed
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

## Resolution

- **Idle breathing**: `IdleMeter`'s 12 bars now ride a shared
  `rememberInfiniteTransition` phase (`t`, 0..2π, 3200ms linear loop),
  each bar reading `sin(t + i * 0.5f)` at its own phase offset so the
  row ripples rather than pulsing in lockstep. Reduced motion pins the
  infinite transition's target to `0f`, freezing every bar at its own
  fixed offset — no per-frame animation, matching the existing
  `MorphShapes.kt` pattern for infinite transitions (can't be snapped by
  a `HarkenMotion` token, so it reads `LocalReducedMotion` directly).
- **Record button color morph**: `rememberRecordShape` already handled
  the shape morph (circle -> cookie); added `animateColorAsState` on
  both container and content color, accent -> `stateLive`/`stateLiveFg`
  (the Wire palette's amber reserved for "live", not red — ADR-0010's
  record button has never used an error color, and Wire's own decision
  was that amber, not error-red, means recording; ticket's literal
  "error fill" wording is superseded by that established convention).
  Uses `HarkenMotion.effectsFast()`, riding the same state change as the
  shape spring so both read as one transition.
- **Upload status morph**: the two disconnected `AnimatedVisibility`
  blocks (Succeeded, Failed — Uploading had no UI at all) replaced with
  one `UploadStatusCard`, gated by a single outer `AnimatedVisibility`
  on `status != Idle`. Inside, an `AnimatedContent` keyed on `status`
  scale+fades the icon (spinner/check/warning) in place while the card
  background color-morphs via `animateColorAsState`; title/body text
  swap with the icon rather than the whole card fading. New
  `record_upload_uploading_title`/`_body` strings fill the previously
  -unhandled `Uploading` state.
- **Retry shake**: tapping the failed card now also drives an
  `Animatable<Float>` through a decaying zig-zag (10/-10/6/-6/0) applied
  as a horizontal `offset`, alongside the existing `retryUpload()` call
  — skipped outright under reduced motion rather than snapped, since a
  snapped shake is still a shake.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: idle meter bars show visible per-bar height variation
  (breathing, not flat); granted mic permission and started a real
  recording — button visibly morphed to the amber cookie shape with a
  dark stop icon, live meter showed real cyan amplitude bars; stopped
  recording — button morphed back to the accent circle, and since no
  real backend is reachable (`localhost:5057` refused), the upload
  naturally exercised the **Failed** path end-to-end: red card, warning
  icon, retry copy. Tapped the failed card — captured mid-shake, card
  visibly offset from its resting alignment relative to the idle meter
  card above it, confirming the shake plays. Reduced motion
  (`animator_duration_scale 0`): idle bars render flat/uniform instead
  of rippled, confirming the freeze.

**Not verified:** the Uploading spinner state and the Succeeded->check
mark morph — no reachable backend this pass means `UploadStatus` never
actually passed through `Uploading` or `Succeeded` on device (it went
straight to `Failed`), so those two `AnimatedContent` branches are
verified by source review only, not on-screen; reduced-motion instant
cut specifically for the button color/shape morph and the upload status
icon swap wasn't separately re-verified (both use the same
`HarkenMotion`-token-collapses-to-`snap()` mechanism already confirmed
working elsewhere in this pass).
