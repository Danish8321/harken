# UI-004 — Error-state buttons below 44dp touch target

- **Severity:** high
- **Status:** open
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
