# UI-006 — No reduced-motion handling

- **Severity:** high
- **Status:** open
- **Area:** `src/Harken.Android` — Record, Onboarding, theme/MorphShapes

## Problem

No animation in the app checks the OS "Remove animations" / animator duration
scale setting. Searches for `reducedMotion`, `animationScale` and
`Settings.Global` across the UI tree return zero matches.

Animations that run unconditionally:

| Location | Animation |
|----------|-----------|
| `ui/RecordScreen.kt` (via `theme/MorphShapes.kt`) | Record FAB circle↔cookie shape morph |
| `ui/RecordScreen.kt:299-303` | FAB press scale spring (`0.92f`) |
| `ui/RecordScreen.kt:258-263` | Live meter bar loop, 90ms tick while recording |
| `ui/OnboardingScreen.kt:113` | Pager dot width tween (300ms) |
| `ui/OnboardingScreen.kt:88` | `AnimatedContent` step transition |
| `ui/RecordScreen.kt` (banners) | `AnimatedVisibility` fade in/out |

Users who enable reduced motion for vestibular reasons get the full set anyway.

## Fix

Read `Settings.Global.ANIMATOR_DURATION_SCALE` once, expose it through a
`CompositionLocal` (or a small `rememberReducedMotion()` helper alongside
`HarkenMotion`), and have `HarkenMotion.spatialFast()` / `spatialDefault()` /
`effectsDefault()` return a snap spec when it is on.

The live meter is a special case: it conveys real information (input level), so
it should keep updating — but the shape morph and press spring can snap.

## Verification

- `./gradlew compileDebugKotlin` clean
- `adb shell settings put global animator_duration_scale 0`, relaunch, and
  confirm the FAB and onboarding transitions snap rather than animate while the
  live meter still tracks audio. Restore with `... scale 1`.
