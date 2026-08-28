# UI-007 — No strings.xml, all copy inlined in Kotlin

- **Severity:** medium
- **Status:** fixed
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

## Resolution

`res/values/strings.xml` (new) holds every user-facing string in the app —
~110 entries plus two plurals — grouped by screen so the copy reads as a set
against `docs/brand-guidelines.md`. Content descriptions live in the same file;
they are user-facing too.

`app_name` is deliberately NOT in it: it varies per build type and is generated
by `resValue` in `app/build.gradle.kts` (the debug variant is "Harken Debug").

Three things needed more than a literal swap:

- **Counts became plurals.** The Library subtitle built `"${n} recording${if (n
  == 1) "" else "s"}"` in Kotlin, and the transcript meta did the same for
  segments. Both are now `getQuantityString` — English has two forms, many
  languages do not, and a hand-rolled `+ "s"` cannot be translated at all.
- **`LibraryFilter` had one field doing two jobs.** Its `label` was both the chip
  text and the tag matched against `session.tags`. Localizing it would have
  orphaned every tag already stored on a device, so it is now `tag` (fixed, not
  localized) and `label` (a string resource). `ThemeMode.label` and the nav
  `Tab.label` became `@StringRes Int` the same way.
- **ViewModels produce copy too.** Settings' connection messages and the session
  sheet's toasts are built off the UI thread; all five ViewModels are already
  `AndroidViewModel`, so they resolve through `getApplication()`. `confirm()` now
  takes a `@StringRes Int` rather than a `String`, which makes it impossible to
  pass an unlocalized literal.

Also fixed in passing: the nav bar icons had `contentDescription = tab.label`,
which made TalkBack read each tab name twice — `NavigationBarItem` already
announces the visible label. They are now `null`, which is what a decorative
icon beside its own label should be.

### Verified

- `.claude/scripts/check.sh` → `== check: OK ==`
- `grep -n 'Text("\|contentDescription = "' ui/*.kt ui/components/*.kt` returns
  nothing — no bare literal remains in any composable.
- `uninstallDebug` + `clean installDebug`, then on-device captures of Onboarding,
  Record, Library and Settings: no missing-resource placeholders, no truncation.
  Two formatted strings render correctly — the Library subtitle reads
  "0 recordings" (the `other` plural form) and the error body interpolates
  "Nothing answered at localhost:5057". The `
` in `record_idle_headline` still
  breaks "Ready when / you are." across two lines.

### Not verified

- The session sheet (rename, delete dialog, summary controls, transcript rows,
  playback labels) needs a session to open, and the backend was unreachable
  throughout. Source-verified only.
- No second locale exists yet, so nothing exercises the plurals' other forms or
  reveals layouts that would break under longer translations.
