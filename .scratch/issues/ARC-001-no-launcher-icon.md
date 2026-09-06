# ARC-001 — The app ships with no launcher icon

- **Severity:** critical
- **Status:** open
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
