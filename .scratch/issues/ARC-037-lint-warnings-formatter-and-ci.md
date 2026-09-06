# ARC-037 — Lint's 76 warnings, a formatter, and CI

- **Severity:** medium
- **Status:** in-progress
- **Area:** `.claude/scripts/`, `app/build.gradle.kts`, repository root

## Problem

ARC-020 put `lintDebug` in `check.sh` and fixed everything it reported as an
error. It still reports 76 warnings, and two gaps remain that Lint does not
cover.

The warnings, by check:

| Check | Count | What it means here |
|---|---|---|
| `Typos` | 35 | Mostly in the long explanatory comments; needs reading, not a sweep |
| `GradleDependency` | 16 | Newer versions available — deliberate pins (material3 1.4.0 above the BOM, Room matched to KSP) sit among them, so this needs ARC-021's version catalog first |
| `UnusedResources` | 11 | Strings and colours left by removed surfaces; each is a delete or a bug |
| `PluralsCandidate` | 2 | `"%d segments"` shaped strings that should be plurals |
| `ObsoleteSdkInt` | 2 | Version checks that minSdk 26 already guarantees |
| `DefaultLocale` | 1 | ARC-029 |
| `ChromeOsAbiSupport` | 1 | arm64-v8a only, which ADR-0013 decided |

Then:

- **No formatter.** ktlint or detekt would need a new Gradle plugin, which
  needs sign-off.
- **No CI.** No `.github/workflows`; the gates run only when someone runs them.

## Why it is separate

ARC-020's claim was "nothing checks correctness except a compiler", and that is
now false — Lint runs and its errors are fixed. What is left is a different,
smaller job with a dependency question in the middle of it, and folding it back
into a closed ticket would hide that.

## Progress — the warnings

Lint's warning count is 70 -> 59, and every warning that named a defect in the
app's own code or resources is gone. Three commits:

- `17bae52` — `UnusedResources` 11 -> 2. Nine of the eleven were the summary
  feature the .NET tier used to fill: `replaceSummary` had no caller, so
  `SummaryCard` could never render. The whole path is deleted, table excepted
  (dropping it is a migration, and migrations wait for ARC-015).
- `bdc9fbd` — the last two `UnusedResources` were a real bug. Every recording
  failure reached the user as an English sentence built inside the foreground
  service; the two localizable strings for it had never been wired.
  `CaptureFailure` now names the cause, `RecordingError` carries a `@StringRes`
  plus the platform's own detail, and five causes have their own sentence.
- `ef9518d` — `PluralsCandidate` (3), `ObsoleteSdkInt` (2), `ModifierParameter`
  (3), `ConstantLocale` (1), `UseOfNonLambdaOffsetOverload` (1) and
  `DataExtractionRules` (1). Two of those were live defects, not style: the
  export formatter captured the locale at class load, and the save card's shake
  recomposed once a frame.

### Corrections to this ticket's own table

- **`Typos` (35) is not "mostly in the long explanatory comments".** All 35 are
  inside the base64 certificate blobs in `res/values/font_certs.xml`. None is in
  prose. They are a symptom of [ARC-038](ARC-038-typeface-depends-on-play-services.md),
  which proposes deleting that file, so no suppression is added here for a file
  that may not survive.
- **`DefaultLocale` (1) was already fixed** by ARC-029; what the report shows now
  is `ConstantLocale`, a different check, fixed in `ef9518d`.
- **`ChromeOsAbiSupport` (1) stays.** ADR-0013 chose arm64-v8a deliberately.
  A warning that names a decision the project made on purpose is left standing
  rather than suppressed — suppressing it would hide the next, real one.

## Progress — CI and the version pins

- `4efd7d7` — the gates ran on Windows and nowhere else: they invoked
  `./gradlew.bat` directly, which is why there was no CI. `_gradle.sh` picks the
  wrapper for the platform, and `.github/workflows/gates.yml` runs `check.sh`
  and `test-fast.sh` themselves rather than a workflow-shaped copy of them.
  `.gitattributes` is new and load-bearing: `core.autocrlf` was checking the
  scripts out as CRLF, and `#!/usr/bin/env bash` fails on Linux naming an
  interpreter that does not exist.
- `6bb1b6b` — nine libraries and the Gradle wrapper moved to the newest release
  that still builds. 59 warnings -> 54.

### The version notices are a ceiling, not a backlog

Most of what Lint names is a 2026 androidx release whose AAR metadata requires
compiling against API 37. `checkDebugAarMetadata` refuses those by name, and
compileSdk is 36 because AGP 8.12 recommends no higher. So the remaining 17
notices are one job — AGP 9, then compileSdk 37, then the androidx train — and
that job is worth its own ticket rather than being ground down one library at a
time. The ceiling is now written in `libs.versions.toml`.

Three pins are held back for their own reasons, also recorded there: Room ties
to KSP ties to Kotlin and its migration test needs a device; okhttp 4 -> 5 is a
major on the one network path; Kotlin and AGP are the toolchain.

## Still open

- **No formatter.** ktlint or detekt is a new Gradle plugin and needs sign-off.
  This is the only thing left in this ticket that is not blocked on something
  else, and it cannot be done without asking.
- **35 typos**, all inside `res/values/font_certs.xml` and all resolved by
  [ARC-038](ARC-038-typeface-depends-on-play-services.md) if it is accepted.
- **17 version notices**, held by the compileSdk 36 ceiling above.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==` on all three commits.
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 151 unit tests,
  0 failures, on all three.
- Lint warning counts read from `app/build/reports/lint-results-debug.xml`
  after each run, not from the summary line.

Not seen on a device. Three of these are user-visible and unverified on
hardware: the recording-failure dialog, the plurals, and the launcher icon after
the `mipmap-anydpi` rename.
