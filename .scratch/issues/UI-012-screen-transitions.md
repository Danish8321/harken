# UI-012 — Screen-to-screen transition motion

- **Severity:** high
- **Status:** open
- **Area:** `ui/AppNav.kt`, `ui/OnboardingScreen.kt`

## Problem

`HarkenMotion` (spring tokens, reduced-motion aware) exists and is used
for in-screen state changes, but zero transition exists between screens.
`NavHost` has no `enterTransition`/`exitTransition`; tab switches and
Onboarding->Record are instant cuts. `MainHost`'s comment ("each screen
now owns its own entry transition on a spatial spring") is aspirational —
not actually implemented anywhere.

## Fix

Reuse existing `HarkenMotion` tokens — no new motion system.

- **Tab switching** (Record/Library/Settings): shared-axis slide+fade via
  `AnimatedContent` keyed on `currentRoute`, using `spatialDefault()` /
  `effectsDefault()`. Restrained — no bounce (dampingRatio already 0.8,
  not overshoot, for spatial defaults).
- **Onboarding steps**: slide+fade between the 3 steps, currently
  instant (confirm exact current behavior when implementing — may
  already use `HorizontalPager` with default anim).
- **Onboarding -> Record** (`popUpTo` navigate on finish): coordinate
  with UI-011's splash-to-wordmark continuity if they end up overlapping
  in the nav graph.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: tap each of the 3 tabs repeatedly, confirm consistent
  slide+fade both directions; step through onboarding forward/back/skip;
  confirm no visual pop or flash on any transition.
- Reduced motion on: confirm all transitions collapse to instant cuts.
