# ARC-036 — The wallpaper-colours switch crashes on Android 8 through 11

- **Severity:** high
- **Status:** closed
- **Area:** `ui/theme/Theme.kt`, `ui/SettingsScreen.kt`

## Problem

Found by the Lint pass ARC-020 added: `NewApi` on `Theme.kt:119`, twice.

`HarkenTheme` called `dynamicDarkColorScheme(context)` /
`dynamicLightColorScheme(context)` with no API guard. Both are `@RequiresApi(31)`
— they read `android.R.color.system_*`, which does not exist below Android 12 —
and `minSdk` here is 26.

The call is reachable: `dynamicColor` is a real DataStore-backed setting with a
switch in Settings ("Wallpaper colours"). On Android 8 through 11 flipping that
switch took the whole app down.

## Resolution

`DynamicColorAvailable` — one `Build.VERSION.SDK_INT >= S` predicate in
`Theme.kt` — now guards the branch, so the flag is ignored below 31 and the
Proto neutrals stand. The same predicate hides the Settings row entirely on
those devices: a switch that moves and changes nothing is worse than one that
is not offered, and hiding it keeps the two decisions from drifting apart.

## Evidence

`check.sh` and `test-fast.sh` both pass; Lint reports neither `NewApi` error.

## Device verification

The reference device is API 35, so it takes the *available* branch: the
"Wallpaper colours" row is present in Settings after a fresh install, which is
the guard proving it did not over-hide. The below-31 branch is compile-time
guarded and has no device on hand; the crash it removes is the documented
behaviour of a `@RequiresApi` call.

## Status: closed
