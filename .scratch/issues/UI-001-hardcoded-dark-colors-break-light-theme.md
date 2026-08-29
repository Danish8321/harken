# UI-001 — Hardcoded dark-theme colors break light theme

- **Severity:** critical
- **Status:** fixed
- **Area:** `src/Harken.Android` — Record, Library, Onboarding screens

## Problem

Seven sites paste dark-palette hex literals directly into composables with no
theme branch. Light theme is user-reachable (Settings → Appearance → Light), so
these render wrong whenever it is selected.

| Location | Literal | Sits on | Light-theme contrast |
|----------|---------|---------|----------------------|
| `ui/RecordScreen.kt:172` | `0xFFCCDBB2` (check icon) | `c.card` = `#FFFFFF` | **1.46:1** |
| `ui/RecordScreen.kt:118` | `0xFFAEBF92` (status dot) | `c.accentFill2` = `#9FB37D` | **1.16:1** |
| `ui/LibraryScreen.kt:167` | `0xFFAEBF92` / `0xFF82796A` (progress bar) | `c.cardBorder` | not a token in either palette |
| `ui/OnboardingScreen.kt:57` | `0xFF4A2E19` bg, `0xFFFFC6A5` tint | screen bg | dark-only `accentFill` pair |
| `ui/OnboardingScreen.kt:58` | `0xFF333B26` bg, `0xFFCCDBB2` tint | screen bg | dark-only `accentFill2` pair |
| `ui/OnboardingScreen.kt:59` | `0xFF4A2E19` bg, `0xFFFFC6A5` tint | screen bg | dark-only `accentFill` pair |
| `ui/OnboardingScreen.kt:116` | `0xFF3A352D` (inactive pager dot) | `c.screenBg` | dark-only |

Both 1.46:1 and 1.16:1 are far below the WCAG AA non-text minimum of 3:1 —
those elements are effectively invisible in light theme.

## Root cause

Values were copied from `ProtoDarkColors` during the prototype port instead of
being read from the resolved `ProtoColors` instance.

## Fix

Route every one through the `c: ProtoColors` already in scope. `0xFFAEBF92` and
`0xFF82796A` correspond to no existing token — either add semantic tokens for
them (see UI-008) or map to the nearest existing role.

## Verification

- `./gradlew compileDebugKotlin` clean
- `./gradlew installDebug`, then switch Settings → Light and screenshot Record,
  Library and Onboarding; confirm the status dot, check icon and pager dots are
  all clearly visible.

## Resolution

New `ProtoColors.success` token (`#CCDBB2` dark / `#4F6B2A` light, 6.0:1 on the
light card) plus routing every literal through the resolved `c`. Onboarding's
`OnboardStep` now carries a `StepTone.Warm | Sage` selector instead of raw hex,
so the icon badge resolves to the `accentFill*` / `accentFill2*` pair.

`grep -rn "Color(0x" ui/` outside `ui/theme/` returns nothing.

Verified on device (SM-E625F, light mode): onboarding badges both tones, pager
dots, Record status dot. **Not** pixel-verified — the Library progress bar and
the Record "Uploaded · transcribing" check icon need a reachable backend and a
completed upload to render; both are compile-correct and read `c.success`.

`ProtoAccentColor` / `ProtoAccentOn` remain theme-independent — folding them into
the semantic layer belongs to UI-008.
