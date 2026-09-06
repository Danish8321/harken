# ARC-017 — Derived recording titles are hardcoded English

- **Severity:** medium
- **Status:** fixed
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

## Resolution

The data layer reports the kind and the presentation layer names it.

- `data/PartOfDay.kt` is a new enum — `Morning`, `Afternoon`, `Evening`,
  `LateNight`, `Unknown` — with `of(startedAtIso, zone)` and `now(zone)`. The
  zone handling that `DerivedTitle` got right is carried over unchanged.
- `SessionView.title: String` and `hasLocalTitle: Boolean` became
  `localTitle: String?` and `partOfDay: PartOfDay`. The boolean was the same fact
  as the null, stated twice.
- `RecordingTitle.kt` (root package, not `ui`, because the notification and the
  export both need a name and neither is a screen) resolves the two into text:
  `PartOfDay.titleRes()`, `Context.recordingTitle(localTitle, partOfDay)` and a
  `@Composable SessionView.displayTitle()`.
- Five strings moved into `strings.xml`. `Unknown` is titled "Recording" rather
  than guessed at — the old code fell through to "Late night recording" for a
  timestamp it could not read.
- `exportItems()` takes the resolver as a parameter, so the repository builds
  file names a person reads without knowing their language.

`object DerivedTitle` is deleted, and `SessionRepository` no longer imports
`java.time` at all.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 151 unit tests, 0
  failures.

Not seen on a device. What a phone would show that a JVM cannot: the notification
itself, and whether backgrounding the app actually stops the meter recomposing.
