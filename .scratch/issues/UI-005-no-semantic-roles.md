# UI-005 — No semantic roles on clickable elements

- **Severity:** high
- **Status:** fixed
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

## Resolution

Onboarding's **Skip** and **Next / Get started** were rebuilt as a real
`TextButton` and `Button` rather than patched with a role — that also brings
focus order, keyboard activation and enabled-state handling. `Button`'s default
elevation is zeroed to keep the prototype's flat look.

The remaining three `.clickable` blocks take `role = Role.Button`:

| Location | Control |
|----------|---------|
| `ui/LibraryScreen.kt:172` | session row |
| `ui/RecordScreen.kt:168` | "Uploaded · transcribing" banner |
| `ui/RecordScreen.kt:189` | "Upload failed · tap to retry" banner |

The record FAB was inspected and left alone: it already carries
`contentDescription = if (recording) "Stop recording" else "Start recording"`.

### Verified

`./gradlew installDebug`, then `adb shell uiautomator dump` against
`com.harken.android.debug` (checking the foreground window first — an earlier
dump silently captured SystemUI):

- **Onboarding** — two `android.widget.Button` nodes now sit under the Skip and
  Next labels. Before the fix these were plain `View`/`TextView`.
- **Record** — the FAB reports `android.widget.Button` with
  `content-desc="Start recording"`.

### Not verified

- The two Record banners and the Library session row need a reachable backend to
  render; Library showed "Backend unreachable" throughout. Their roles are
  source-verified only.
- Not traversed with TalkBack actually enabled — the node tree is the evidence.
- The bottom nav items report as `View`, not `Button`. That is correct:
  `NavigationBarItem` declares `Role.Tab`, which uiautomator flattens to `View`.
