# ARC-001 — The app ships with no launcher icon

- **Severity:** critical
- **Status:** closed
- **Area:** `app/src/main/AndroidManifest.xml`, `app/src/main/res/`

## Problem

`res/` contains exactly one drawable (`ic_notification_mic.xml`), no `mipmap-*`
directory of any density, and no `ic_launcher` of any kind. `<application>` in
the manifest declares `android:label`, `android:theme` and
`android:roundIcon`/`android:icon` — nothing. Android therefore falls back to
the default green robot.

Every launch of the app starts from the home screen, so this is the single most
visible surface in the product and the only one that was never designed. A
recorder that is meant to be a daily driver is identified on the home screen by
the placeholder that says "nobody finished this".

It also blocks release: Play Console rejects an upload with no adaptive icon.

## Fix

An adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` +
`ic_launcher_foreground`/`ic_launcher_background` drawables) drawn from the same
mark the splash already uses, plus a monochrome layer for Android 13 themed
icons, and `android:icon`/`android:roundIcon` on `<application>`.

## Resolution

An adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` (and `ic_launcher_round`)
over `@color/launcher_background` — the app's own slate `#2C313A`, fixed in both
themes because a home screen is not the app's canvas — with
`drawable/ic_launcher_foreground.xml` carrying the mark in the tan accent, and a
`monochrome` layer for Android 13 themed icons. `android:icon` and
`android:roundIcon` now point at them.

The mark is a five-bar level meter whose centre bar is the microphone: the two
things the splash shows in sequence, drawn as one shape. Three weights carry it
— ticks, bars, then a capsule at twice their width — because the first draw gave
all five bars the same width and 1.5dp of ground, and on a real home screen it
resolved into a single blob.

## Evidence

`check.sh` (dotnet build, assembleDebug, assembleRelease, and the new lintDebug
step) and `test-fast.sh` (14 + 32 .NET, 92 Android JVM) both pass.

## Device verification

Fresh install on the Nothing Phone 2 (uninstall, then `installDebug`). App
drawer screenshot: the mark renders tan-on-slate at launcher size, legible as a
microphone, with nothing clipped by the round mask — the outer ticks were moved
in from x 19/89 to x 20/88 after the first draw showed them cut.

## Status: closed
