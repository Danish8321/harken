# UI-010 — Typography swap

- **Severity:** medium
- **Status:** fixed, partly superseded
- **Area:** `ui/theme/Theme.kt`, `ui/theme/Type.kt` (font declarations)

> **The font choice below still holds** — Space Grotesk / Figtree / IBM Plex
> Mono are what ships. The *location* moved: the families left
> `ProtoColors.kt` for `ui/theme/Type.kt` (UI-029), and the duplicate
> declaration in `Theme.kt` is gone. Any colour values quoted below are
> superseded by UI-020/UI-024.

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

## Resolution

`HeadingFont` (Theme.kt) and `ProtoHeadingFont` (ProtoColors.kt) both
Caprasimo -> Space Grotesk (Normal/Medium/Bold weights declared). Figtree
body font untouched. New `ProtoMonoFont` (IBM Plex Mono, Normal/Medium)
added to ProtoColors.kt, applied in RecordScreen.kt to the numeric/
technical readouts only — format line (`16 kHz mono · caps at 3h`), idle
elapsed (`0:00`), live elapsed counter, cap countdown (`cap 3:00:00`) —
while labels around them (`INPUT IDLE`, `tap to start`, `LIVE INPUT`)
stay on Figtree, since those are words, not readouts.

Confirmed both `HeadingFont`/`ProtoHeadingFont` were in fact still
separately declared (UI-002 merged the color systems, not the font
declarations) — both needed the swap, done identically.

**Verified:**
- `bash .claude/scripts/check.sh` -> `== check: OK ==`
- `grep -rn Caprasimo` across `app/src/main/kotlin/` -> only the two
  explanatory comments left, no font declarations
- `uninstallDebug` + `installDebug` clean install
- On-device, confirmed foreground via `dumpsys window | grep
  mCurrentFocus`: Onboarding ("Meet Harken" renders Space Grotesk, not
  the Caprasimo slab), Record idle ("Harken" wordmark + "Ready when you
  are" in Space Grotesk; "16 kHz mono · caps at 3 h" and "0:00" render
  in IBM Plex Mono, visibly monospace against the Figtree "INPUT IDLE" /
  "tap to start" labels beside them)

**Not verified:** Record live/capturing state's mono readouts (elapsed
counter, cap countdown) — would need an active recording, not triggered
this pass; Library and Settings screens (neither uses ProtoMonoFont, and
their Space Grotesk headings weren't separately re-screenshotted — same
font family object as Record's, no reason to expect divergence).
