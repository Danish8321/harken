# UI-003 — Blank screen on cold start while DataStore loads

- **Severity:** high
- **Status:** fixed
- **Area:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/AppNav.kt`

## Problem

`AppNav.kt:60`:

```kotlin
if (onboardingComplete == null) return
```

While the `onboardingComplete` DataStore flow is still emitting its initial
`null`, `AppNav` composes nothing at all — the window paints the bare
`themes.xml` background with no content.

Observed directly: a screenshot taken ~2s after launch captured a fully blank
white frame with only the system status bar; the following capture (2s later)
showed the rendered Record screen.

The early return is deliberate and its reasoning is sound (the comment above it
explains that defaulting to `Record` would flash past onboarding for a
first-time user). The gap is that "wait" is rendered as "nothing".

## Fix

Render something during the null window instead of returning — the theme
background plus the app wordmark, or a minimal centered progress indicator.
Alternatively adopt the AndroidX splash screen API and hold the splash until
the first non-null value arrives.

Make sure whatever is shown uses the resolved theme background so it does not
flash white on a dark-theme device.

## Verification

- `./gradlew installDebug`
- Force-stop the app, relaunch, and screenshot immediately (`adb exec-out
  screencap -p`); confirm the first frame shows themed content, not a blank
  white window. Repeat in dark theme.

## Resolution

Two separate white frames turned out to be involved:

1. **The Compose gap.** The early return now renders `SplashPlaceholder()` — the
   resolved `screenBg` with the Harken wordmark — instead of nothing.
2. **The launch window underneath it.** `themes.xml` inherited the platform
   `Theme.Material.Light` background, so the OS splash painted white even on a
   dark-theme device — visible in a burst capture as a white frame *before* any
   Compose frame. `android:windowBackground` now points at a new
   `@color/window_background` (`#FAF1E1`, `#1C1A17` in `values-night`), matching
   `ProtoColors.screenBg` on both sides.

Verified by burst-capturing `adb exec-out screencap -p` in a loop straight after
`am start`, in both modes: dark now shows a `#1C1A17` splash then the wordmark
then onboarding, with no white frame anywhere in the sequence; light shows the
cream equivalent.

## Follow-up (not fixed)

The launch window's status-bar icons draw white regardless of mode, so they are
low-contrast over the cream light-mode splash. Adding
`android:windowLightStatusBar` to the theme had no observable effect on this
device (Samsung One UI, Android 13) — the Android 12+ splash window appears not
to honour it — so the attribute was removed rather than left in as dead config.
Lasts a few hundred ms and only affects the splash, not app content.
