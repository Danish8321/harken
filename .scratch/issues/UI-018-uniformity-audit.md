# UI-018 — Component uniformity audit

- **Severity:** medium
- **Status:** fixed
- **Area:** whole `ui/` tree

## Problem

To be filled in during the audit itself (deliberately last in the
sequence — UI-009 through UI-017 introduce new components/values that
would otherwise need auditing twice). Audit scope: buttons (shape,
elevation, press state), cards (corner radii, border treatment,
elevation), spacing scale (padding/gap values across screens), icon
style (stroke weight, size, filled vs outline consistency).

## Fix

Grep every screen file for radius/padding/elevation literals, tabulate
actual values in use, flag any that don't match the most common value
for that role, and either conform them or promote the value to a shared
token in `HarkenShapes`/`ProtoColors`/a new spacing-scale object if none
exists yet. Concrete findings and the fix table go here once the audit
runs.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: side-by-side screenshots of all screens, confirm consistent
  corner radii, spacing rhythm, icon weight throughout.

## Resolution

Audit method: grepped `RoundedCornerShape(\d`, card `.padding(...)`,
and `Icon(...).size(N.dp)` across every file in `ui/` and `ui/components/`,
then tabulated by role (not just by raw value — a splash-screen hero
icon and an inline meter icon are different roles and were expected
to differ).

**Findings and fixes:**

1. **Card corner radius drift (fixed).** The "primary card" role
   (a full-width `Column`/`Row` on `c.card` background holding a
   labeled block of content) used `RoundedCornerShape(24.dp)` in
   `SettingsScreen.kt` (line 163) and `LibraryScreen.kt` (line 215),
   but `RoundedCornerShape(22.dp)` in `RecordScreen.kt`'s cap-warning
   banner (line 188) and `UploadStatusCard` (line 307) — a 2dp,
   almost-imperceptible-alone but real, split. None of these four
   matched any of `Theme.kt`'s own declared shape tokens
   (`extraSmall=10, small=14, medium=20, large=26, extraLarge=30`)
   either, meaning the "primary card" role never had a single source
   of truth. Conformed both of RecordScreen's cards to `24.dp`,
   matching the two-screens-already-agree precedent set by Settings
   and Library rather than promoting a brand-new token — 24dp already
   had two independent screens' worth of buy-in.
2. **Card padding drift (fixed), same two RecordScreen cards.** The
   cap-warning banner used `.padding(14.dp)`, and `UploadStatusCard`
   used `.padding(horizontal = 16.dp, vertical = 14.dp)` — both
   differed from Settings/Library's uniform `.padding(16.dp)` for the
   same "primary card" role. Conformed both to `.padding(16.dp)`.
3. **Duplicate icon at two different sizes (fixed).**
   `SessionSheet.kt` renders `Icons.Filled.AutoAwesome` twice, once at
   `20.dp` (line 233, inline in a row) and once at `19.dp` (line 388,
   in the summary-card header) — same icon, same rough visual weight,
   no reason for the two to differ. Conformed the 19dp instance to
   `20.dp`.
4. **Icon-size spread across screens — reviewed, not flagged.** Sizes
   found: 15dp (RecordScreen meter-row mic icon), 16dp (RecordScreen
   cap-warning triangle), 18dp (`HarkenSurfaces.kt` default content
   padding, not an icon), 20dp (RecordScreen `UploadStatusCard`
   check/warning icons, SessionSheet AutoAwesome after fix 3), 26dp
   (`HarkenStates.kt` empty/error-state icon), 28dp (SplashScreen hero
   mic icon), 42dp (OnboardingScreen step badge icon). Each of these
   is a genuinely distinct role — inline status glyph vs. hero mark vs.
   empty-state illustration vs. onboarding badge — and Android/Compose
   convention already varies icon size by prominence within a screen.
   Forcing these to one value would flatten a deliberate visual
   hierarchy, so left as-is; this is a documented audit conclusion,
   not an oversight.
5. **Pill badges, buttons, elevation — no drift found.**
   `RoundedCornerShape(999.dp)` (pill shape) is used consistently for
   every badge/chip across RecordScreen, OnboardingScreen,
   SettingsScreen, and LibraryScreen. `FloatingActionButtonDefaults`
   elevation (`defaultElevation = 10.dp`, `pressedElevation = 4.dp`)
   is set in exactly one place (the record FAB, its own unique hero
   role) — no other button sets explicit elevation, so there's nothing
   to conform. Small single-purpose radii (`4.dp` onboarding progress
   dot, `3.dp` RecordScreen meter bars) are one-off decorative shapes,
   not a "card" or "button" role, and were left alone for the same
   reason as finding 4.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install on the physical
  device
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: exercised the RecordScreen `UploadStatusCard` by
  granting mic permission, starting and stopping a recording (no
  reachable backend, same recurring constraint as prior tickets, so
  it naturally lands on the Failed/red card) — confirmed via
  screenshot the card now renders with the wider, softer 24dp/16dp
  corner-and-padding treatment. Compared side-by-side against
  SettingsScreen's cards (Backend / Capture limits / Appearance,
  screenshotted separately) — same corner radius and edge padding by
  eye, closing the drift described in finding 1/2. LibraryScreen's
  error-state card was also screenshotted but is a distinct role (a
  bordered warning banner, not the "primary card" pattern) and was
  correctly left out of the conformance.

**Not verified:** the SessionSheet `AutoAwesome` icon-size fix and
the icon-size survey in finding 4 — no reachable backend and no local
sessions this pass means there was no session to open a `SessionSheet`
for, same recurring blocker as UI-016. Verified by source review only
(both `Modifier.size(20.dp)` call sites read back correctly after the
edit, confirmed via `check.sh` compiling clean).
