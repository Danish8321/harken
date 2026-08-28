# UI-007 — No strings.xml, all copy inlined in Kotlin

- **Severity:** medium
- **Status:** open
- **Area:** `src/Harken.Android/app/src/main/res/values`

## Problem

`res/values/` contains only `font_certs.xml` and `themes.xml`. There is no
`strings.xml` anywhere in the project, so every user-facing string is a Kotlin
literal inside a composable — screen titles, button labels, error copy,
onboarding body text, capture-limit descriptions.

Consequences:

- No localization path at all; the app is English-only by construction.
- The voice and tone rules just written into `docs/brand-guidelines.md` cannot
  be reviewed or enforced in one place — the copy is scattered across six
  screen files.
- Error strings (the longest and most carefully written copy in the app, e.g.
  the backend-unreachable text in `ui/LibraryScreen.kt:94`) cannot be revised
  without touching layout code.

## Fix

Extract user-facing strings to `res/values/strings.xml` and reference them with
`stringResource(R.string.…)`. Worth doing in one pass per screen rather than
incrementally, so the copy can be proofread as a set against the brand
guidelines.

Keep `contentDescription` values in the same file — they are user-facing too.

## Verification

- `./gradlew compileDebugKotlin` clean
- On-device screenshots of all four screens confirming no missing-resource
  placeholders and no truncated labels.
- `grep -rn '"' ui/*.kt | grep Text(` should return no bare literals afterwards.
