# UI-002 — Two parallel design systems composed together

- **Severity:** critical
- **Status:** open
- **Area:** `src/Harken.Android` — whole UI tree

## Problem

After the prototype-to-main promotion, the app runs two independent color/type
systems side by side, and they are nested inside one another at runtime.

Measured usage (`grep -c` per file):

| File | `ProtoColors` refs | `MaterialTheme` refs |
|------|--------------------|----------------------|
| `ui/RecordScreen.kt` | 21 | 0 |
| `ui/LibraryScreen.kt` | 11 | 0 |
| `ui/SettingsScreen.kt` | 21 | 0 |
| `ui/OnboardingScreen.kt` | 6 | 0 |
| `ui/SessionSheet.kt` | 0 | 35 |
| `ui/AppNav.kt` | 0 | 9 |
| `ui/components/HarkenStates.kt` | 0 | 11 |
| `ui/components/HarkenSurfaces.kt` | 0 | 6 |

The two systems meet wherever a Proto screen composes a Material component:

- `LibraryScreen` (Proto) renders `ErrorState` / `EmptyState` / `SkeletonRow`
  (Material) — visible as a seam between the filter chips and the error card.
- The `AppNav` bottom nav (Material) sits under every Proto screen.
- `SessionSheet` (Material) opens from the Proto library list.

Two resolution paths exist: `theme/Theme.kt` (`Organic.*` → `MaterialTheme`) and
`theme/ProtoColors.kt` (`ProtoDarkColors` / `ProtoLightColors`).

## Not the problem

Both paths resolve light/dark **correctly and identically** —
`MainActivity.kt:24-28` and `ProtoColors.kt:142-146` were checked and agree.
This is duplication, not divergence. No theme-mismatch bug today; the cost is
two systems to keep in sync and a visible style seam.

## Fix

Pick one system. Recommended: keep `ProtoColors` as the app's visual language
(it is what shipped) and either port `HarkenStates`, `HarkenSurfaces`,
`SessionSheet` and the `AppNav` nav bar onto it, or drive `MaterialTheme`'s
`colorScheme` from the Proto tokens so Material components inherit them.

Depends on UI-008 (token restructure) if the second route is taken.

## Verification

- `./gradlew compileDebugKotlin` clean
- On-device screenshots of Library (error + populated), an open `SessionSheet`,
  and the nav bar in both themes; confirm one consistent palette throughout.
