# ARC-017 — Derived recording titles are hardcoded English

- **Severity:** medium
- **Status:** open
- **Area:** `data/SessionRepository.kt` (`object DerivedTitle`)

## Problem

`DerivedTitle.of` returns `"Morning"`, `"Afternoon"`, `"Evening"`,
`"Late night"` as Kotlin string literals in the data layer, then composes
`"$part recording"`. UI-007 moved every other user-visible string into
`strings.xml`; this one was missed because it does not live in a composable.

Two problems, not one: the strings are unlocalizable, and the data layer is
producing presentation text at all. `SessionView` carries a display title so
that the UI does not have to think, which means the repository now depends on
`Locale` and on the user's language.

## Fix

Return the *kind* (a time-of-day enum, or a null title) from the repository and
resolve it to a `stringResource` at the point of display.
