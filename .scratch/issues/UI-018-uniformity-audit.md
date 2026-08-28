# UI-018 — Component uniformity audit

- **Severity:** medium
- **Status:** open
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
