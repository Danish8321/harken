# UI-041 — The launcher icon's mic glyph reads too large on a real home screen

- **Severity:** low
- **Status:** fixed
- **Area:** `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_launcher_monochrome.xml`

## Problem

Reported directly by the user after installing the app: the mic glyph fills almost the
whole visible circle, with no breathing room against the neighbouring launcher icons.

`ic_launcher_foreground.xml`'s v3 revision (ARC-001 history) deliberately sized the glyph
to fill the full 68x56dp safe zone edge-to-edge (28dp capsule, 44dp base) after v1's
smaller glyph read as a faint smear on a real home screen. That overcorrected: on-device
(Nothing Launcher) the glyph now reads oversized against every neighbouring icon.

## Fix

Scaled the whole glyph 0.7x about the viewport centre (54,54) — same silhouette, same
centring, just smaller: 28dp capsule -> 19.6dp, 56dp tall -> 39.2dp. Applied identically to
`ic_launcher_monochrome.xml` so the Android 13+ themed-icon variant stays in sync.

## Found by

Direct user report, 2026-09-08.

## Resolution, 2026-09-08

Edited both vector drawables' path data (capsule, stem, base) to the 0.7x-scaled
coordinates, keeping the centroid fixed at the viewport centre.

Verified on device, not just by reading the vector math: a first install showed no visible
change because the Nothing Launcher caches a rendered adaptive-icon bitmap per package and
doesn't invalidate it on a same-versionCode update-install. A full uninstall + reinstall
(the fresh-install discipline this project already requires for manual verification) forced
the launcher to re-render the icon from the new resources. Cropped a screenshot of the app
drawer before and after: the tan glyph now sits with visible margin inside the dark circle
instead of touching it.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK (device `AIN065 - 16`, fresh
install).
