# UI-005 — No semantic roles on clickable elements

- **Severity:** high
- **Status:** open
- **Area:** `src/Harken.Android` — Record, Library, Onboarding screens

## Problem

`grep -rn "Role" app/src/main/kotlin/com/harken/android/ui` returns zero
matches. No composable in the app declares `Role.Button`, `Role.Switch`, or any
other semantic role.

Six real `.clickable` blocks are affected — TalkBack announces each as plain
text with no indication it is actionable:

| Location | What it is |
|----------|------------|
| `ui/LibraryScreen.kt:171` | Session row — opens the session sheet |
| `ui/RecordScreen.kt:168` | "Uploaded · transcribing" banner — opens the session |
| `ui/RecordScreen.kt:189` | "Upload failed · tap to retry" banner — retries the upload |
| `ui/OnboardingScreen.kt:125` | **Skip** button (a `Box`, not a `Button`) |
| `ui/OnboardingScreen.kt:131` | **Next** / **Get started** button (a `Box`, not a `Button`) |

The two onboarding controls are the worst case: they are styled as buttons and
are the only way forward through onboarding, but structurally they are
`Box{}.clickable` with no button semantics whatsoever.

Related: 11 of 20 `contentDescription` values are `null`. Most are decorative
and correctly nulled, but each should be confirmed against its use — the mic
icon inside the record FAB carries the only label for that control.

## Fix

Add `role = Role.Button` to each `.clickable` call. For the onboarding controls,
prefer replacing the `Box` with a real `Button`/`TextButton` so focus order,
keyboard activation and state description come for free.

## Verification

- `./gradlew compileDebugKotlin` clean
- Enable TalkBack and traverse Record, Library and Onboarding; confirm each
  control is announced as a button and is activatable from the accessibility
  focus.
