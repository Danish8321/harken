# UI-011 — Splash screen with mark-to-wordmark continuity

- **Severity:** high
- **Status:** fixed, superseded
- **Area:** new — `ui/SplashScreen.kt`; wiring in `AppNav.kt`

> **The mark-to-wordmark continuity below still ships**, but the screen
> around it was rewritten three times after this ticket: UI-019 (waveform
> + crossfade to app root), UI-021 (full-width marching waveform, tagline,
> bigger wordmark), UI-025 (bar density) and UI-026 (glow re-parented onto
> the mic, 88dp circle). Read the layout details here as history.

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

## Resolution

New `SplashScreen.kt`, shown from `AppNav` as an overlay (not a NavHost
destination) on every process cold-start, after the `onboardingComplete`
DataStore read resolves. Single `Animatable<Float>` driven 0->1 over
1800ms (`CubicBezierEasing(0.22f, 1f, 0.36f, 1f)`); phases derived by
remapping that one value (`enterT` 0-0.35, hold 0.35-0.65, `exitT`
0.65-1) rather than chaining separate `animateTo` calls, so tap-to-skip
can cleanly interrupt with a second `animateTo`/`snapTo` on the same
Animatable.

Mark: accent-filled circle with a mic glyph, fades/scales in on enter,
dissolves on exit — doesn't travel. Wordmark: a `Text` whose position is
driven by `BiasAlignment(morphT * -1f, morphT * -1f)` inside a
`Box(padding = 20dp/16dp)`, morphing from centered/40sp to Record's exact
top-start wordmark slot (20dp/16dp inset, 20sp) when destination is
Record — chosen over `SharedTransitionLayout` (heavier wiring, and
Navigation Compose doesn't share elements across destinations cleanly).
For the Onboarding destination (no equivalent slot to land on) the whole
overlay just fades out instead of morphing. Tap-to-skip: full-screen
`clickable` that `snapTo(1f)` then calls `onFinished()` immediately.
Respects `LocalReducedMotion` — an early-return branch skips straight to
`onFinished()` with a plain themed background box, no animation.

**Verified:**
- `bash .claude/scripts/check.sh` -> `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus` before each capture: cold launch mid-splash frame shows
  the accent-cyan mic mark + "Harken" wordmark, Space Grotesk, centered,
  Wire palette; a later capture mid-exit shows the wordmark having slid
  to Record's top-start slot and shrunk while the mark dissolves —
  continuity confirmed, not a cut. First-run cold launch dismisses into
  Onboarding; after completing onboarding, a fresh cold launch dismisses
  into Record. Reduced motion (`settings put global
  animator_duration_scale 0`): cold launch at 1.2s already shows Record
  fully rendered, no splash frame observed — instant-cut confirmed.

**Not verified:** tap-to-skip mid-animation on device (interrupt path
exercised only by code reading, not an actual on-device tap during the
1.8s window — timing makes this hard to hit reliably via `adb input
tap`); the two system-splash-icon frames seen at very early capture
timestamps (before ~0.9s) are Android 12+'s own `SplashScreen` API
showing the adaptive icon, not this ticket's splash — expected and out
of scope.
