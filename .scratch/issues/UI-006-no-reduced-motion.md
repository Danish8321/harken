# UI-006 — No reduced-motion handling

- **Severity:** high
- **Status:** fixed
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

## Resolution

`theme/ReducedMotion.kt` (new) reads `Settings.Global.ANIMATOR_DURATION_SCALE` and
exposes it as `LocalReducedMotion`, provided by `HarkenTheme`. Android has no
reduced-motion callback, so it registers a `ContentObserver` on the setting URI —
the toggle is flipped while the app is backgrounded, and it must not need a
relaunch.

Every `HarkenMotion` token now returns `snap()` when the flag is set. That is the
main lever: any future animation bound to a motion token honours the setting by
construction, with no call-site opt-in to forget.

Three animations cannot be expressed as a spec and read the local directly:

| Location | Under reduced motion |
|----------|----------------------|
| `theme/MorphShapes.kt` — record cookie spin (infinite) | holds still; the shape change alone signals recording |
| `ui/OnboardingScreen.kt` — step `AnimatedContent` | `EnterTransition.None` / `ExitTransition.None` |
| `components/HarkenStates.kt` — skeleton pulse (infinite) | holds its dim alpha |

Also re-bound to tokens so they snap for free: the onboarding pager dot (was a raw
`tween(300)`) and the three Record banner fades (were bare `fadeIn()`/`fadeOut()`).

The live meter deliberately keeps updating — it carries real input level, not
decoration.

### Verified

`.claude/scripts/check.sh` → `== check: OK ==`. Then `uninstallDebug` +
`clean installDebug`, and an on-device A/B on onboarding with **no relaunch in
between**:

- `adb shell settings put global animator_duration_scale 0` — tapping Next and
  capturing immediately gives a fully settled step 2: opaque text, pager dot
  already at full width, no transition frame.
- `... animator_duration_scale 1` — the same immediate capture shows the previous
  step ghosted through the new one mid-cross-fade, and the Get started button
  mid-fade.

That the second capture animated without a relaunch is the evidence the
`ContentObserver` works. Scale restored to 1.

### Not verified

- The record FAB press spring and the cookie spin need microphone permission and a
  live recording; source-verified only (both bound to `spatialFast()` /
  `LocalReducedMotion`).
- The skeleton pulse needs a slow backend response to render.
