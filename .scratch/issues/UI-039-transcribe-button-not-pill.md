# UI-039 — The Library "Transcribe" button breaks the pill-shape convention

- **Severity:** low
- **Status:** fixed
- **Area:** `ui/LibraryScreen.kt`

## Problem

Every other `Button(...)` in the app explicitly passes `shape = PillShape`
(`HarkenStates.kt:71,107,119`; `OnboardingScreen.kt:244,293,309,315`;
`SessionSheet.kt:202,370`; `SettingsScreen.kt:102,286`). The "Transcribe"
button on a Library session card (`LibraryScreen.kt:512`) —

    Button(onClick = onTranscribe, enabled = transcribeEnabled) { Text(...) }

— is the sole exception and falls back to Material3's default button
shape, so it visibly doesn't match the pill look of every other button,
including the state chip it swaps places with in the same row.

## Fix

Add `shape = PillShape` to this `Button` call.

## Found by

Fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Added `shape = PillShape` to the button. `check.sh` OK, `test-fast.sh` OK, `test-full.sh`
OK (device `AIN065 - 16`).
