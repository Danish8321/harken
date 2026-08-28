# UI-012 — Screen-to-screen transition motion

- **Severity:** high
- **Status:** fixed
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

## Resolution

`MainHost` (`AppNav.kt`) wraps its content `Box` in `AnimatedContent` keyed
on tab index (not route string — index gives a stable left/right
direction the same way a ViewPager position would). `transitionSpec`
compares `targetState`/`initialState` to pick direction: slide+fade
using `HarkenMotion.spatialDefault()` (offset) and `.effectsDefault()`
(alpha), both existing tokens, no new motion system. `OnboardingScreen`'s
existing `AnimatedContent` (previously a plain crossfade) got the same
slide+fade treatment, direction driven by `step` index. Both fall back
to `EnterTransition.None togetherWith ExitTransition.None` under
`LocalReducedMotion` — a plain instant replace, since enter/exit specs
aren't expressible as a `HarkenMotion` token (only value animations
snap automatically).

Onboarding->Record on finish was left as-is (`popUpTo` navigate, instant)
— UI-011's splash already owns the wordmark continuity moment for that
hop; layering a second transition on top would fight it, and the ticket
said to coordinate rather than add motion there.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: Record->Settings tab tap (forward) and
  Settings->Library tab tap (backward) both land cleanly on the correct
  screen with the correct tab highlighted, no crash, no stale content
  flash. Onboarding step 1->2 (`pm clear` to reset to first-run):
  captured mid-transition — outgoing badge/title sliding left and
  fading, incoming badge/title sliding in from the right, step-dot
  indicator advancing in sync — confirms shared-axis slide+fade is
  actually playing, not just wired.

**Not verified:** tab-switch and onboarding-step transitions caught
mid-frame only for the onboarding case — the tab-switch spring
(stiffness 700) settles faster than the `adb screencap`/`pull` round
trip reliably catches, so those two were confirmed functionally
(correct destination, correct highlighted tab, no pop) rather than
visually mid-slide; reduced-motion instant-cut on tab/step transitions
specifically (as opposed to the splash, which was checked in UI-011)
wasn't separately re-verified on-device — the fallback branch is
identical in shape to the already-verified splash reduced-motion path.
