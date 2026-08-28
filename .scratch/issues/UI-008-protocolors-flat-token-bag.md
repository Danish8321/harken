# UI-008 — ProtoColors is a flat token bag with positional names

- **Severity:** medium
- **Status:** open
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
