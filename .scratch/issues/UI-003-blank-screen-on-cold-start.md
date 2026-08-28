# UI-003 — Blank screen on cold start while DataStore loads

- **Severity:** high
- **Status:** open
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
