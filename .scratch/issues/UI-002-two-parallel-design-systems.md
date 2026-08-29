# UI-002 — Two parallel design systems composed together

- **Severity:** critical
- **Status:** fixed
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

## Resolution

Took the second route: `MaterialTheme.colorScheme` is now derived from
`ProtoColors` (`protoColorScheme()` in `Theme.kt`), so `SessionSheet`,
`AppNav`, `HarkenStates` and `HarkenSurfaces` inherit the Proto palette
through the roles they already read — no changes needed in those four files
at all. Depended on UI-008's semantic renames to have roles worth mapping
onto Material's names:

| Material role | ProtoColors source |
|---|---|
| primary / onPrimary | accent / onAccent |
| primaryContainer / onPrimaryContainer | stateLive / stateLiveFg |
| secondary / onSecondary | stateDone / stateDoneFg |
| secondaryContainer / onSecondaryContainer | stateDoneSoft / stateDoneFg |
| background / onBackground | screenBg / text |
| surface / onSurface | card / text |
| surfaceVariant / onSurfaceVariant | pillTrack / textSecondary |
| outline / outlineVariant | cardBorder (both) |
| error / onError | stateError / stateErrorFg |
| errorContainer / onErrorContainer | stateError @ 18% alpha / stateErrorFg (unused by any of the 4 files today) |

The old Organic-derived `LightColors`/`DarkColors` `lightColorScheme()`/
`darkColorScheme()` calls in `Theme.kt` are deleted — that was the second
system. `Color.kt`'s `Organic` object is trimmed from a full accent/neutral
ramp down to the two ink-anchor colors it still supplies to `LocalInk`
(audio surfaces are deliberately fixed dark/light regardless of app theme,
per the ADR-0010 doc comment, so they don't route through `ProtoColors`).
Wallpaper dynamic-color neutrals extraction (`dynamicColor = true`) is
unchanged — it still overlays onto the base scheme, now a Proto-derived one
instead of an Organic-derived one.

Confirmed `navBg`/`navBorder` in `ProtoColors` are literal duplicates of
`card`/`cardBorder` in both themes (not touched — out of scope for this
ticket, worth folding if `ProtoColors` gets revisited).

**Verified:**
- `bash .claude/scripts/check.sh` -> `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device: Library error card, chips, nav bar in **both** light and dark
  theme — the exact seam UI-002 called out (chips vs. error card) is gone;
  card color, border tint (now coral `stateError`, not Material red), button
  colors (`Retry` outline, `Change address` text = `accent` orange) all read
  as one palette in both themes.

**Not verified:** an open `SessionSheet` (needs a session; backend
unreachable this session, same limitation as UI-007) — verified by reading
`SessionSheet.kt`'s Material role usage instead (`primary`, `onPrimary`,
`primaryContainer`/`onPrimaryContainer`, `secondaryContainer`/
`onSecondaryContainer`, `surface`, `error`, `outlineVariant`, `background`,
`onSurfaceVariant` — all mapped above, none left pointing at a dead
Organic value).
