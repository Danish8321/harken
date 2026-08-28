# UI-004 — Error-state buttons below 44dp touch target

- **Severity:** high
- **Status:** fixed
- **Area:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/components/HarkenStates.kt`

## Problem

`HarkenStates.kt:92` and `HarkenStates.kt:95` both pin an explicit
`Modifier.height(40.dp)`:

```kotlin
OutlinedButton(onClick = onRetry, shape = PillShape, modifier = Modifier.height(40.dp)) { Text("Retry") }
TextButton(onClick = onSecondary, shape = PillShape, modifier = Modifier.height(40.dp)) { Text(secondaryLabel) }
```

40dp is below the 48dp Material minimum and the 44dp WCAG 2.1 AA target. The
explicit height also overrides the automatic touch-target expansion Material 3
would otherwise apply.

These are the **Retry** and **Change address** buttons on the Library
backend-unreachable card — the primary recovery path when the backend is down,
so they are exactly the controls that should be easiest to hit.

## Fix

Raise to at least 48dp, or drop the explicit height and let `ButtonDefaults`
size them. If the compact look matters visually, keep the visual height and
restore the target with `Modifier.sizeIn(minHeight = 48.dp)` or
`minimumInteractiveComponentSize()`.

## Verification

- `./gradlew compileDebugKotlin` clean
- Enable Developer options → "Show layout bounds" (or run an Accessibility Scanner
  pass) on the Library error state and confirm both buttons report ≥48dp.

## Resolution

The ticket named two sites; a sweep for fixed dimensions under 48dp found three
more interactive controls with the same defect. All five now use
`Modifier.heightIn(min = 48.dp)` (or `size(48.dp)` for the icon button) so the
control can still grow with its content:

| Location | Was | Control |
|----------|-----|---------|
| `components/HarkenStates.kt:58` | `height(46.dp)` | empty-state primary action |
| `components/HarkenStates.kt:92` | `height(40.dp)` | **Retry** |
| `components/HarkenStates.kt:95` | `height(40.dp)` | **Change address** |
| `ui/SettingsScreen.kt:82` | `height(44.dp)` | **Test** connection |
| `ui/SessionSheet.kt:100` | `size(42.dp)` | delete icon button |

Re-running the sweep afterwards leaves only non-interactive hits: the decorative
icon inside the 96dp onboarding badge, the Record header row, and the meter bar
rows.

Verified: `./gradlew installDebug`, then on-device captures of the Library
backend-unreachable card (Retry visibly taller than the pre-fix capture) and the
Settings backend card.

Tried and reverted: `contentPadding` on the Test button to stop the short label
rounding into a lozenge. It made no visible difference — `ButtonDefaults`
already applies 24dp horizontal — so it came back out rather than staying in as
dead config.
