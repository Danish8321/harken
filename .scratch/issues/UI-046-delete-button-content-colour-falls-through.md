# UI-046 — The delete button's label is painted in `onPrimary`, not `onError`

- **Severity:** low
- **Status:** fixed
- **Area:** `ui/SessionSheet.kt`

## Problem

The delete confirmation's filled button names its container and nothing else:

```kotlin
colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
```

`ButtonDefaults.buttonColors` defaults every argument it is not given, and its default
`contentColor` is `colorScheme.onPrimary` — the foreground for the container this button
does not have. So the label on the error fill is painted in `onAccent` (#2B2016), not
`onError` (`stateErrorFg`, #2B0B08).

Measured, not inferred: on 'AIN065' in Dark the glyph cores of that label sample #2B2016.

Nothing is illegible. #2B2016 on `errorInk`'s #FF9E93 is 7.99:1, well past AA, which is
why UI-044's device pass walked past it once before noticing. Two smaller things are
wrong:

- The colour is right by luck. It survives because `onAccent` and `stateErrorFg` are both
  near-black warm inks; the day either theme's `onAccent` moves — and UI-044 moved the
  light accent pair already — this label follows a role it has nothing to do with.
- `ProtoContrastParityTest` asserts `stateErrorFg on errorInk` with the comment "a
  Button(containerColor = error) with onError content". That is the only such button in
  the app and it does not have `onError` content, so the assertion describes a pair
  nothing paints.

## The fix

Name the content colour at the same call:

```kotlin
colors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
    ),
```

This is the only `buttonColors(` in the app, so there is no second site and no helper
worth extracting for one call.

The parity assertion stays exactly as it is. It was written for the pair this button
*should* paint; the fix makes it true rather than making it removable.

## Evidence

Measured on the device, before and after, because no unit test can see this one: the
parity test asserts the pair the button *should* paint, so it was green while the button
painted something else. That is the gap this ticket is, and a JVM test of a Compose
default's fall-through would be a test of Material, not of Harken.

On 'AIN065' in Dark, the delete dialog's "Delete" button, glyph cores taken by pixel
frequency over the label's bounds:

| | fill | label |
|---|---|---|
| before | #FF9E93 | **#2B2016** (`onAccent`) |
| after | #FF9E93 | **#2B0B08** (`stateErrorFg`) |

The fill is unchanged, which is the point — only the foreground was wrong.

`check.sh` OK, `bash .claude/scripts/test-fast.sh` OK.
