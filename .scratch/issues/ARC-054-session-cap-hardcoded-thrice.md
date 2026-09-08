# ARC-054 — The 3-hour session cap is hardcoded independently in three files

- **Severity:** low
- **Status:** fixed
- **Area:** `recording/RecordingForegroundService.kt`, `ui/RecordScreen.kt`, `values/strings.xml`

## Problem

- `RecordingForegroundService.kt:156` — `sessionCapMs = TimeUnit.HOURS.toMillis(3)`
- `strings.xml:157` — `<string name="settings_session_cap_value">3 hours</string>`
- `RecordScreen.kt:238` — `AnimatedVisibility(elapsed >= 10500, ...)` (the "ending soon" banner)

`10500` (175 minutes) has no comment, no name, and no expressed relationship to the 3-hour
cap it is implicitly 5 minutes before. If the cap ever changes in
`RecordingForegroundService`, the Settings copy and this warning threshold silently go
stale — nothing ties the three together, the kind of magic-number drift the codebase is
otherwise disciplined about (see ARC-021's version catalog).

## Fix

One shared constant (e.g. `SessionCap.CAP_MS` / `SessionCap.WARNING_LEAD_MS`) referenced by
both the service and the UI; derive the Settings display string from it.

## Found by

Third fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Added `SessionCapLimits` (`recording/SessionCapLimits.kt`) with `CAP_MS` and
`WARNING_LEAD_MS`. `RecordingForegroundService` and `RecordScreen`'s warning threshold now
both derive from it (named `SessionCapLimits`, not `SessionCap`, to avoid colliding by name
with the existing `RecordingStopReason.SessionCap` enum entry in the same package).
`strings.xml`'s `settings_session_cap_value` stays a plain string — XML can't read a
Kotlin constant — but now carries a comment pointing at `SessionCapLimits` so a future
change to the cap doesn't silently leave the Settings copy stale.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK.
