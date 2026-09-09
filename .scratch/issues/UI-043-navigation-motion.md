# UI-043 — Navigation motion: the tab bar travels with the screen, and there is no shared-element anywhere

- **Severity:** medium
- **Status:** open
- **Area:** `ui/AppNav.kt`, `ui/theme/Motion.kt`, `ui/LibraryScreen.kt`,
  `ui/SessionSheet.kt`

Split from [UI-042](UI-042-world-class-punch-list.md), which covers type/shape
consolidation and per-screen motion gaps. This ticket is navigation motion
specifically: the transitions between destinations, and the chrome that
should stay still across them.

## Problem

### 1. The floating tab bar is inside each destination, so it animates away with the screen

`AppNav.kt` builds the tab bar per-route, not once:

```
composable(Routes.RECORD)   { MainHost(navController) { open -> RecordScreen(...) } }
composable(Routes.LIBRARY)  { MainHost(navController) { ... } }
composable(Routes.SETTINGS) { MainHost(navController) { SettingsScreen() } }
```

`MainHost` owns the `Scaffold` whose `bottomBar` is `FloatingTabBar`, so
RECORD, LIBRARY and SETTINGS each compose their own. The NavHost's
`enterTransition`/`exitTransition` therefore slide and fade **the whole
destination including its tab bar**: on every tab switch the bar the user
just tapped slides out from under their finger while a second, identical
bar slides in from the opposite side.

Two consequences:

- A floating pill bar is persistent chrome. Chrome that travels with
  content reads as unstable — this is the most-seen motion in the app and
  it is the least correct.
- `FloatingTabBar`'s `animateColorAsState` on `itemBg`/`itemFg`
  (`AppNav.kt:267-276`) never visibly runs. The incoming bar composes with
  `selected` already `true`, so both springs start at their target value.
  The selection change is animated in code and instant on screen.

### 2. No sliding selection indicator

Selection is three independent per-item background/foreground colour
animations. Even once the bar is persistent, the selected pill would
cross-fade in place rather than travel. A single shared pill that slides
between tabs under `spatialDefault` is the standard high-craft treatment,
and only becomes possible after the hoist in finding 1.

### 3. The NavHost breaks Motion.kt's own speed rule

`Motion.kt:32` states it: *"Speed follows element size: fast for small
controls, default for most things, slow for full-screen surfaces."*
`AppNav.kt:141` passes `HarkenMotion.spatialDefault()` as the slide spec
for full-screen destination changes. Per the app's own vocabulary this
should be `spatialSlow`. The most prominent transition in the app is the
one place that breaks the documented rule.

### 4. No shared element / container transform anywhere

Grep for `SharedTransition|LookaheadScope|animateBounds` across
`com/harken/android` returns nothing. Tapping a `SessionCard` is the most
repeated navigation action in the app and the sheet simply slides up on
stock Material motion — `ModalBottomSheet` in `SessionSheet.kt` is not
bound to `HarkenMotion` at all, so the largest surface in the app is the
only one still using default motion while everything else uses the custom
spring vocabulary.

`navigation-compose` is on `2.10.0` and `SharedTransitionLayout` is
available, so a card-expands-into-sheet container transform is reachable
without a new dependency. This is the single highest-value "expensive"
moment available in the product.

### 5. Smaller

- The floating bar never reacts to scroll, so it permanently occupies
  space over long Library lists — no hide-on-scroll-down / reveal-on-up.
- Predictive back: `targetSdk = 37`, so the system-level animation is
  default-on, but nothing in the repo wires in-app back progress into the
  `popEnterTransition`/`popExitTransition`. Needs verifying on-device
  rather than assuming it already works.

## Fix

1. **Hoist the chrome above the graph.** Move `Scaffold` +
   `FloatingTabBar` out of `MainHost` and above the `NavHost`, so only
   screen content sits inside the animated destinations. Thread the
   Scaffold's `padding` down into the graph. `openSession`/`SessionSheet`
   state moves up with it. This is the prerequisite for 2 and fixes 1 on
   its own.
2. **Sliding selection pill.** Replace the three per-item
   `animateColorAsState` backgrounds with one indicator whose offset/width
   animates between tab slots under `spatialDefault`.
3. **`spatialSlow` for the NavHost slide** in `AppNav.kt:141`, per
   `Motion.kt`'s stated rule.
4. **Container transform, `SessionCard` -> `SessionSheet`,** via
   `SharedTransitionLayout`; and bind the sheet's own show/hide to
   `HarkenMotion.spatialSlow` rather than Material defaults.
5. **Hide-on-scroll for the floating bar** in Library, driven by the
   existing `LazyListState`.
6. **Verify predictive back on-device** before deciding whether it needs
   wiring at all.

Items 1 and 3 are small and unlock the rest; 4 is the largest and should
land on its own.

## Verification

- `bash .claude/scripts/check.sh` (or this repo's actual gate: gradle
  `ktlintCheck` + `assembleDebug`)
- `installDebug` clean install, then on-device:
  - switch tabs and confirm the bar stays put while only content slides
  - confirm the selection pill visibly travels between tabs
  - confirm reduced-motion (system "remove animations") still collapses
    every one of these to a snap — the hoist must not bypass
    `LocalReducedMotion`
  - open a session from Library and confirm the container transform
  - back-gesture from each tab, with predictive back observed

## Resolution

Not yet started — filed from audit findings only.
