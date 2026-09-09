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

Items 1, 2, 3 and 4 done; 5 remains and 6 is half-answered, so this stays open.

**1. Chrome hoisted.** `MainHost` is deleted. `AppNav` now owns one
`Scaffold` above the `NavHost`; its `bottomBar` is a single
`FloatingTabBar` wrapped in `AnimatedVisibility` gated on
`tabOrder(currentRoute) >= 0`, so the bar slides down for ONBOARDING and
stays put across every tab switch. `openSession` state and the
`SessionSheet` call moved up with it. `OnboardingScreen`'s own
`statusBarsPadding()`/`navigationBarsPadding()` were removed — the hoisted
Scaffold supplies system-bar insets to every destination now, and leaving
them would have double-padded.

**2. Sliding selection pill.** The three per-item `itemBg`
`animateColorAsState` calls (dead code — the incoming bar always composed
with `selected` already at its target) are replaced by one indicator Box.
The bar measures itself with `onSizeChanged`, divides by tab count for a
slot width, and the indicator's `offset` animates to
`selectedIndex * slotWidth` under `animateIntAsState` +
`HarkenMotion.spatialDefault()`. `itemFg` is kept for the label/icon
colour cross-fade.

**3. `spatialSlow` for the NavHost slide,** per `Motion.kt`'s rule that
speed follows element size. Full-screen destination changes are the
largest surface that moves, so they get the slowest spring.

Verified: gradle `ktlintCheck` + `assembleDebug` clean, then
`installDebug` onto 'SM-E625F - 13'.

**4. The sheet had to leave its own window first.** Item 4 as written was
not reachable with `ModalBottomSheet`, and neither half of it was —
confirmed by inspecting the released `material3` 1.4.0 artifact, not by
inference:

- `ModalBottomSheetKt` composes into `ModalBottomSheetDialog` (the aar
  carries `ModalBottomSheetDialogWrapper` and
  `ModalBottomSheetDialogLayout`). `SharedTransitionLayout` cannot span two
  windows, so no shared element could ever have travelled from a Library
  card into it.
- Its show/hide runs on `SheetDefaultsKt.BottomSheetAnimationSpec`, a
  private top-level `TweenSpec`. `ModalBottomSheet` takes no
  `animationSpec` and neither does `rememberModalBottomSheetState`. So
  "bind the sheet's show/hide to `HarkenMotion.spatialSlow`" had no
  parameter to bind to — while `spatialSlow`'s own KDoc names "the session
  sheet" as the thing it exists for.

So the sheet is now a scrim plus a bottom-anchored `Surface` composed in
the app's own window, in a private `SheetSurface` in `SessionSheet.kt`. The
scrim fades on `effectsSlow`, the surface transforms or slides on
`spatialSlow`; the outer `AnimatedVisibility` is `EnterTransition.None` so
each child gets the half of the vocabulary it wants rather than both
sharing the fade. The sheet owns its own show and hide and only calls
`onDismiss` once the exit has settled — `onDismiss` is the caller removing
it, so firing it when the hide *starts* cut the animation off.

The transform itself: one `SharedTransitionLayout` in `AppNav` wraps both
the graph and the sheet. `SessionCard` is the near end via
`sharedElementWithCallerManagedVisibility` — not an
`AnimatedVisibilityScope`, because the card is not leaving a transition of
its own, it is standing still in a list while a sheet grows out of it. The
sheet is the far end via `sharedBounds`. Both take their bounds spec from
`spatialSlow` instead of the shared-transition default spring.

`OpenSession` carries `fromCard`, so the transform only runs where a
container actually exists: a `SessionCard` tap sets it, a search result and
`RecordScreen` do not, and without it the sheet slides up as before.
Otherwise a search result would hide a card that is nowhere on screen and
grow the sheet out of nothing. `SearchResultCard` is therefore still a
plain card — giving it the same treatment is a follow-up, not part of this.

Re-established by hand, because the Dialog window had supplied them: a
scrim that dismisses, `PredictiveBackHandler` shrinking the surface toward
its own bottom edge under the finger, a drag handle that dismisses past a
threshold or a flick and otherwise springs back on `spatialDefault`, and
system-bar insets via `safeDrawingPadding`. Focus containment is the one
thing composition cannot re-create, so the `Scaffold` behind the sheet
takes `Modifier.semantics { hideFromAccessibility() }` while it is open —
without that, TalkBack still walks the Library under the scrim.

Dropped with it: the nested-scroll swallower and the disabled overscroll
inside the transcript. Both existed solely so leftover drag could not reach
`ModalBottomSheet`'s drag handling and wobble the sheet at the list's
edges. Nothing above the list drags any more.

Item 6 is now half-answered: `ModalBottomSheetDialogWrapper` carried its
own `PredictiveBackOnBackPressedCallback`, so the sheet had predictive back
before and would have lost it — hence the `PredictiveBackHandler` above.
What still needs an on-device check is predictive back *between tabs* and
out of the app, which is navigation's, not the sheet's.

Verified: `bash .claude/scripts/check.sh` -> `== check: OK ==` (ktlint,
assemble debug/androidTest/release, Android lint), then `installDebug` onto
'AIN065 - 16'. The gesture and transform pass on the device itself is
still to do — that is eyes, not a script.
