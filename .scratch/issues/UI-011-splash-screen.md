# UI-011 — Splash screen with mark-to-wordmark continuity

- **Severity:** high
- **Status:** open
- **Area:** new — `ui/SplashScreen.kt`; wiring in `AppNav.kt`

## Problem

No splash exists. `AppNav.SplashPlaceholder` is a bare static hold-frame
shown only while DataStore reads the onboarding flag (UI-003's fix) — not
a designed screen. User wants a real splash shown on **every** launch.

## Fix

- Single animated brand moment, ~1.5–2.5s, tap-to-skip, auto-dismisses
  into Onboarding (first run) or Record (returning user) — not a
  multi-screen feature carousel (that content stays in Onboarding,
  first-run only).
- No existing logo/wordmark asset. Build the mark natively in Compose
  (Canvas/vector), not a raster image — themeable across light/dark, no
  asset pipeline. Motif: something tied to "listening" (audio/signal),
  consistent with the Wire palette's oscilloscope-cyan accent.
- **Continuity moment (highest-value animation item from the motion
  list, UI-012 depends on this existing first):** the splash mark
  animates in, then morphs/settles directly into the Record screen's
  wordmark position — not two disconnected animations. This likely means
  the splash and `RecordScreen`'s wordmark share a Compose
  `SharedTransitionLayout` or an equivalent coordinated animation rather
  than independent fade-outs.
- Respect `LocalReducedMotion` (UI-006) — reduced motion collapses this
  to an instant cut, no morph.
- Replaces `AppNav.SplashPlaceholder`'s bare text-only hold-frame.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: cold launch, confirm splash plays, confirm it dismisses into
  the correct destination (Onboarding vs Record depending on
  onboarding-complete flag), confirm tap-to-skip works, confirm the mark
  visibly continues into the Record wordmark rather than cutting.
- Reduced motion on: confirm splash is instant, no morph.
