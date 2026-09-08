# ARC-051 — `MainActivity` still uses plain `collectAsState`

- **Severity:** medium
- **Status:** fixed
- **Area:** `MainActivity.kt`

## Problem

`MainActivity.kt:48-49`:

```kotlin
val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.System)
val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
```

ARC-025 (fixed) claims "all nine call sites across six files now use
`collectAsStateWithLifecycle`." A repo-wide grep for `collectAsState(` (excluding
`...WithLifecycle`) finds exactly these two remaining call sites, both still importing
plain `androidx.compose.runtime.collectAsState` (line 8). These drive the whole app's
theme at the composition root, so the DataStore flows here keep a live collector attached
even while the Activity is stopped/backgrounded — the same class of problem ARC-025 fixed,
left over in the one file that wasn't checked.

## Fix

Switch both to `collectAsStateWithLifecycle(initialValue = ...)`, importing from
`androidx.lifecycle.compose`.

## Found by

Third fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Switched both to `collectAsStateWithLifecycle(initialValue = ...)`; removed the now-unused
plain `collectAsState` import.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK.
