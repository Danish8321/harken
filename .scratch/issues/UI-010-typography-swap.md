# UI-010 — Typography swap

- **Severity:** medium
- **Status:** open
- **Area:** `ui/theme/Theme.kt`, `ui/theme/ProtoColors.kt` (font declarations)

## Problem

Display face is Caprasimo — a rounded bubblegum slab. Clashes with the
Wire direction (UI-009): cold, technical, precision-instrument. Body face
Figtree is fine and stays. No mono face exists despite the app showing
live numeric readouts (recording timer, meter labels, cap countdown).

## Fix

- Display: Caprasimo -> **Space Grotesk** (geometric, technical, still has
  personality — not a neutral fallback face).
- Body: Figtree unchanged.
- New utility face: **IBM Plex Mono**, for numeric/technical readouts —
  recording timer (`record_meter_idle_elapsed`), live counter, cap
  countdown (`record_meter_cap`), format line, any hex-ish detail text.
  Wire into `HarkenTypography` as a new style or applied directly at the
  call sites currently using `ProtoBodyFont` for these strings.

Both `ProtoHeadingFont`/`ProtoBodyFont` in `ProtoColors.kt` and
`HeadingFont`/`BodyFont` in `Theme.kt` need the swap — confirm both are
still in use (UI-002 merged the color systems but font declarations may
still be duplicated across the two files).

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: every screen, confirm no Caprasimo glyphs remain (check
  headline text render), confirm mono face renders on the recording timer
  and meter readouts specifically.
