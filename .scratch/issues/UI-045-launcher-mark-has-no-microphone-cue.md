# UI-045 — The launcher mark reads as a lump, not a microphone

- **Severity:** low
- **Status:** open — implemented, on-device verification pending a connected device
- **Area:** `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_launcher_monochrome.xml`

## Problem

Four revisions of the mark (ARC-001 v1..v3, UI-041 v4) all argued about one variable:
how big the glyph should be. v1 was a smear, v3 filled the safe zone, v4 scaled v3 by
0.7. Size was never the fault.

The fault is proportion. v4 is a capsule 19.6dp wide over a stem 8.4dp wide over a base
25.2dp wide, and the stem starts 1.4dp *inside* the top of the base. Three stacked
horizontal masses with almost no gap between them merge into one silhouette at launcher
scale: a bust, or a rubber stamp. Nothing in the shape says microphone, because the part
that would say it — the cradle the head hangs in — was never drawn.

The meter that ARC-001 wanted (the mark should say "listening", not just "microphone")
was dropped at v3 because it was drawn as four ticks *beside* the mic, competing with it
for the same 72dp.

## Fix

v5, chosen by the user from a set of seven candidates rendered at the real launcher
ladder (160/112/72/48/40px, round / squircle / square masks):

- **A cradle.** A 16dp-radius half-ring under the head, 3.6dp stroke, round caps. This is
  the shape that reads as a microphone at 48px when nothing else resolves.
- **A stem clear of the foot.** 3.6dp wide, 10dp of it, meeting a 22x3.4dp foot at its
  top edge rather than sinking into it. Air between the masses is what stops them merging.
- **The grille is the meter.** Four vertical slits, 3dp wide on a 4.5dp pitch, lengths
  9/12/16/20dp, centred on the head's midline. The thing a microphone has on its face is
  a level meter, so the second reading costs the first nothing — and nothing sits beside
  the mic competing for width. Below ~72px the slits stop resolving and the mark degrades
  into a plain, correct microphone: the detail rewards looking, it doesn't carry the read.

The slits are cut out of the head with `android:fillType="evenOdd"` on a single path
(capsule as the outer contour, four slits as inner contours), not painted in
`launcher_background`. The themed layer is re-tinted by the system to one flat
wallpaper-derived colour, so painted slits would look right in `ic_launcher_foreground`
and vanish entirely in `ic_launcher_monochrome`.

Geometry (108dp viewport): head 22x34dp at (43,27), r11 · slits at x 45.75 / 50.25 /
54.75 / 59.25, r1.5 · cradle r16 about (54,52), 3.6dp stroke · stem 3.6x10dp at (52.2,68)
· foot 22x3.4dp at (44.7,78), r1.7. The mark spans y 27..81.4, centred on 54.2 in the
72dp visible circle.

## Verification

- `bash .claude/scripts/check.sh` — OK.
- Both drawables rendered straight from their own XML by a scratch VectorDrawable
  rasteriser (M/a/A/v/h/z, even-odd scanline fill, stroked arc) at 160/112/72/48/40px
  inside the 72-of-108dp visible circle, so what was checked is the shipped path data and
  not a transcription of it. The slits are holes in both layers; the silhouette reads as a
  microphone at every size.
- **Not done: the home-screen check.** No device was attached (`adb devices` empty) after
  the change, and this is precisely the mark whose four previous revisions were each
  argued from a desktop preview. Nothing here is confirmed until it is seen on a launcher.
- When that runs, it needs an **uninstall + reinstall**, not an update-install: UI-041
  found that the Nothing Launcher caches one rendered adaptive-icon bitmap per package and
  does not invalidate it on a same-versionCode update, so an update-install shows the old
  icon and looks like the change did nothing.

## Found by

Direct review of the mark after UI-042's on-device pass, 2026-09-09.
