# UI-008 — ProtoColors is a flat token bag with positional names

- **Severity:** medium
- **Status:** fixed
- **Area:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/theme/ProtoColors.kt`

## Problem

`ProtoColors` is a single 24-field data class of concrete colors, duplicated in
full for light and dark (`ProtoLightColors` / `ProtoDarkColors`). There is no
primitive → semantic → component layering: every field is simultaneously a raw
value and a usage role.

Two concrete symptoms:

1. **Positional names doing semantic work.** `accentFill2` is a *green* that
   means "connected / summarized / success" — used for the studio-mac status
   pill, the Library "Summarized" chip and the connection-OK chip in Settings.
   Nothing in the name says so, so the next person to add a success state has
   no way to know which token to reach for. Same for `accentFill` (warm, means
   "live / active") and the `ink28` / `ink4` / `ink55` / `ink7` alpha ramp,
   whose names encode an alpha the light palette does not honour:

   | Token | Dark alpha | Light alpha | Name accurate? |
   |-------|-----------|-------------|----------------|
   | `ink28` | 0.28 | **0.32** | light only |
   | `ink4`  | 0.40 | 0.40 | yes |
   | `ink55` | 0.55 | **0.60** | light only |
   | `ink7`  | 0.70 | **0.72** | light only |

   Three of the four names are wrong in light theme.

2. **No restyle path.** Changing the accent means editing both palette
   constants plus the standalone `ProtoAccentColor` / `ProtoAccentOn` top-level
   vals, which sit outside the data class entirely and are therefore *not*
   theme-aware — they are the same hex in light and dark.

This is also what makes UI-001 easy to commit: with no semantic layer, reaching
for a literal is no less obvious than reaching for a token.

## Fix

Introduce three layers:

- **Primitive** — the raw ramps (warm, sage, danger, neutral), theme-independent.
- **Semantic** — roles: `surface`, `surfaceElevated`, `textPrimary`,
  `textMuted`, `stateLive`, `stateDone`, `stateError`, `accent`, `onAccent`.
  Light and dark differ only here.
- **Component** — only where a component genuinely needs its own value
  (e.g. `meterBackground`).

Fold `ProtoAccentColor` / `ProtoAccentOn` into the semantic layer so they become
theme-aware, and rename the `ink*` ramp to its actual alphas.

Blocks the cleanest fix for UI-002 (driving `MaterialTheme.colorScheme` from
these tokens needs semantic names to map onto Material's roles).

## Verification

- `./gradlew compileDebugKotlin` clean
- Side-by-side screenshots of all four screens in both themes before and after,
  confirming no rendered pixel changes — this is a pure refactor.

## Resolution

Renamed the flat token bag to semantic roles and folded the two standalone,
non-theme-aware top-level vals into the data class:

- `accentFill`/`accentFillFg` -> `stateLive`/`stateLiveFg` (warm, "live/active")
- `accentFill2`/`accentFill2Fg`/`accentFill2Soft` -> `stateDone`/`stateDoneFg`/`stateDoneSoft`
  (sage, "connected/summarized/done")
- `dangerFill`/`dangerFillFg` -> `stateError`/`stateErrorFg`
- `ProtoAccentColor`/`ProtoAccentOn` (top-level, same hex in both themes — the
  "no restyle path" bug) folded into the data class as `accent`/`onAccent`,
  same hex values preserved
- `ink28`/`ink4`/`ink55`/`ink7` -> `inkFaint`/`inkMuted`/`inkSubtle`/`inkStrong`.
  Kept the numeric alpha out of the name entirely rather than trying to pick
  one "accurate" number — the same role is 0.28 in dark and 0.32 in light, so
  no single number is honest in both themes. Tier ordering (faint < muted <
  subtle < strong) holds in both themes, so tier names stay true where numbers
  didn't.
- `success` field kept as-is; it was already role-named accurately.

All ~16 call sites across `RecordScreen.kt`, `LibraryScreen.kt`,
`SettingsScreen.kt`, `OnboardingScreen.kt` updated to the new names.
`RecordButton` (private composable in `RecordScreen.kt`) previously read the
theme-independent top-level `ProtoAccentColor`/`ProtoAccentOn` directly with no
`ProtoColors` in scope; it now calls `rememberProtoColors()` itself.

Not a restructure into primitive/semantic/component layers as the ticket's
"Fix" section sketched — the screen/surface colors (`screenBg`, `card`, `nav*`,
etc.) already differ per theme with no shared raw value, so a primitive layer
under them would be manufactured, not real. The two actual symptoms in the
ticket (positional accent naming, the two-tier ink lie, the un-theme-aware
accent) are what's fixed.

**Verified:**
- `./gradlew compileDebugKotlin` clean (`bash .claude/scripts/check.sh` -> `== check: OK ==`)
- `uninstallDebug` + `installDebug` clean install
- On-device screenshots, light theme (System, unreachable backend): Onboarding
  (accent circle/button), Record (studio-mac pill sage, record FAB accent
  orange, idle meter tick opacity), Library (All chip accent, error card),
  Settings (System segment accent) — all pixel-identical to pre-refactor.

**Not verified:** dark theme on-device (source-identical hex values to light
theme's mirrored fields, same construction pattern — not re-screenshotted);
`stateError`/`stateErrorFg` visually (backend genuinely unreachable this
session, no failed-upload or bad-status state to trigger).
