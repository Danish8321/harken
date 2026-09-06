# ARC-037 — Lint's 76 warnings, a formatter, and CI

- **Severity:** medium
- **Status:** open
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
