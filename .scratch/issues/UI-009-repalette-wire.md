# UI-009 — Re-palette to Wire

- **Severity:** high
- **Status:** fixed
- **Area:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/theme/ProtoColors.kt`

## Problem

Current palette (warm cream `#FAF1E1` + terracotta accent `#F6A06B` + sage
success) reads as a generic AI-app default. Reviewed against 3 directions
in a design comparison; user picked **Wire** — cool slate neutrals, a
single oscilloscope-cyan accent, amber reserved for the live/recording
state only.

## Fix

Replace every hex in `ProtoDarkColors` / `ProtoLightColors` with the Wire
values below. Field names (`screenBg`, `accent`, `stateLive`, etc.) stay —
this is a value swap, not a restructure (UI-008 already did the structural
work).

| Role | Dark | Light |
|---|---|---|
| screenBg | `#0E1316` | `#F3F6F7` |
| sheetBg | `#0E1316` | `#FFFFFF` |
| card | `#161C1F` | `#FFFFFF` |
| cardBorder | `#212A2E` | `#DDE6E8` |
| text | `#E7EEF0` | `#10161A` |
| textSecondary | `#7C8A8F` | `#5B676C` |
| navBg | `#161C1F` | `#FFFFFF` |
| navBorder | `#212A2E` | `#DDE6E8` |
| pillTrack | `#161C1F` | `#E4EAEC` |
| grabber | `#E7EEF0` | `#10161A` |
| rowHighlight | `#161C1F` | `#EDF2F3` |
| skeleton | `#212A2E` | `#DDE6E8` |
| accent | `#4FC3D6` | `#1F9CB0` |
| onAccent | `#04262B` | `#EAFBFD` |
| stateLive | `#332711` | `#F0A93E` (fill, needs a soft variant like current `stateDone`) |
| stateLiveFg | `#F0A93E` | `#3A2708` |
| stateDone | `#1A2A1F` | `#DCEBDF` |
| stateDoneFg | `#7FB88A` | `#1F4A2B` |
| stateDoneSoft | `#152219` | `#C9E0CE` |
| success | `#7FB88A` | `#2F6B3E` |
| stateError | `#E2584F` | `#C7392F` |
| stateErrorFg | `#2B0B08` | `#FFFFFF` |
| meterBg | `#0A0D0F` | `#E4EAEC` |
| ink (faint/muted/subtle/strong) | same alpha tiers as today, anchored to `#E7EEF0` dark / `#10161A` light | |

Light `stateLive` needs its own fill-behind-text worked out the same way
`stateDone`/`stateDoneSoft` are (a fill + soft variant), since the
comparison mock only showed dark-theme live. Work this out to keep
contrast >= 4.5:1 for text-on-fill, matching the rest of the palette's
accessible pairs.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: Onboarding, Record (idle + live), Library (populated + error),
  Settings — both light and dark theme. Confirm no leftover warm/cream/
  terracotta hex anywhere (grep `ProtoColors.kt` for the old values to be
  sure none were missed).

## Resolution

Replaced every hex in `ProtoDarkColors`/`ProtoLightColors` with the Wire
values from the table above. Worked out light-theme `stateLive` fill/soft
pair (`#F0A93E` fill, `#3A2708` fg) to match the accessible-pair pattern
`stateDone`/`stateDoneSoft` already used, since the original comparison
mock only showed dark-theme live. Field names, structure, and every other
file's mapping (Theme.kt's `protoColorScheme()`, UI-002/UI-008's work)
untouched — this was a pure value swap.

**Verified:**
- `bash .claude/scripts/check.sh` -> `== check: OK ==`
- `grep -rn "F6A06B\|FAF1E1\|E8735A\|9FB37D\|F0975C"` across
  `app/src/main/kotlin/` -> no matches, no leftover old palette hex
- `uninstallDebug` + `installDebug` clean install
- On-device, confirmed foreground via `dumpsys window | grep
  mCurrentFocus` (`com.harken.android.debug/...MainActivity`):
  - **Light:** Onboarding (slate bg, cyan "Next" button, amber icon
    circle), Record idle (slate bg, cyan record FAB, amber live nav
    pill, sage "studio-mac" done pill), Settings (cyan/amber segmented
    control)
  - **Dark:** Settings (near-black bg, amber "Dark" segment selected),
    Record idle (cyan FAB, amber nav pill, sage done pill), Library
    error state (coral `stateError` border/icon, cyan "Change address"
    link) — the exact seam UI-002 called out, now on Wire

**Not verified:** Record live/capturing state (would need an active
recording — not triggered this pass), Library populated state (0
recordings, backend unreachable this session).
