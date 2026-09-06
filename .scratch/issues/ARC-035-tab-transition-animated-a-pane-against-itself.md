# ARC-035 — The tab transition animated each screen against itself

- **Severity:** medium
- **Status:** closed
- **Area:** `ui/AppNav.kt`, `ui/theme/Motion.kt`

## Problem

Found by the Lint pass ARC-020 added, as
`UnusedContentLambdaTargetStateParameter` on `AppNav.kt:196`.

`MainHost` wrapped each tab's pane in an `AnimatedContent` keyed on the tab
index, with a shared-axis `transitionSpec`. Its content lambda ignored the
target state:

```kotlin
AnimatedContent(targetState = tabIndex, ...) {
    Box { content { id -> openSessionId = id } }   // `it` unused
}
```

`content` is fixed per destination — each `composable(Routes.X)` builds its own
`MainHost` around its own screen — so both halves of the transition rendered the
*same* screen. Every tab change slid a pane out and the identical pane in, then
the NavHost swapped destinations underneath with no transition of its own. The
shared-axis motion UI-012 specified has never actually been shown.

## Resolution

The transition moved onto the `NavHost`, which is the only thing that knows both
the destination being left and the one being entered.
`sharedAxisTransition` (one `ContentTransform` for an `AnimatedContent<Int>`)
was replaced by `sharedAxisEnter` / `sharedAxisExit`, since a NavHost takes the
two halves separately, and direction comes from a new `tabOrder(route)` — with
Onboarding at -1 so finishing it slides forward into Record like any other
rightward move. The `AnimatedContent` in `MainHost` is gone.

## Evidence

`check.sh` (dotnet build, assembleDebug, assembleRelease, lintDebug) and
`test-fast.sh` (14 + 32 .NET, 92 Android JVM) both pass; Lint reports the error
no longer.

## Device verification

Fresh install on the Nothing Phone 2 (uninstall then `installDebug`). Cold
start 663 ms. Record → Library → Settings → Record by tap: each pane slides in
the direction of the tapped tab, no crash in `logcat -b crash`.

## Status: closed
