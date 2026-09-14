# ARC-065 — The model download's state machine is written out twice

- **Severity:** low
- **Area:** `ui/OnboardingScreen.kt`, `ui/SettingsViewModel.kt`, `ui/SettingsScreen.kt`
- **Status:** fixed

## Problem

Slice-09 review finding **S4**. `OnboardingViewModel.downloadModel()` and
`SettingsViewModel.updateModel()` were the same twenty-line `catch`/`onCompletion`/`collect`
block, driving the same three fields — `modelDownloadState`, `modelDownloadProgress`,
`modelDownloadError` — declared separately in each of the two UiStates, and initialised by the
same expression in each:

```kotlin
modelDownloadState = if (modelDownloadManager.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
```

Settings carried a fourth field, `modelPresent`, that Onboarding did not, and set it in both
arms of its collect; that was the only real difference between the two bodies, besides
`replaceExisting = true`.

Nothing here was broken. What it lacked was any way to be checked: neither ViewModel had a
JVM test, the state machine lived in two places that had to be kept in step by hand, and the
one rule with a consequence — *a failed update still has the old model installed*, so the
button must say "Update" rather than "Download" — was a line inside one of the two copies.

A smaller oddity went with it: `enum class ModelDownloadState` was declared in
`OnboardingScreen.kt`, so Settings' vocabulary lived inside another screen's file.

## The fix

`ui/ModelDownload.kt` — the enum, one value type, its transitions, and the fold:

```kotlin
data class ModelDownloadUi(
    val state: ModelDownloadState = ModelDownloadState.NotStarted,
    val progress: Int = 0,
    val error: ModelDownloadFailure? = null,
    val present: Boolean = false,
) {
    companion object {
        fun of(present: Boolean) = ModelDownloadUi(state = if (present) Ready else NotStarted, present = present)
    }
}

internal fun Flow<Int>.asDownloadUi(start: ModelDownloadUi, presentOnFailure: () -> Boolean): Flow<ModelDownloadUi>
```

- **`Flow<Int>`, not `ModelDownloadManager`.** The manager is OkHttp and the filesystem, and
  `ModelProvider` declares only `ensureModel`, so a fold written against the manager would be
  reachable only from an instrumented run. Written against the percentages it emits, every
  branch is drivable from a plain JVM test with `flowOf(...)` and a throwing `flow { }`.
- **`of(present)` is the one init rule.** Both ViewModels called the same conditional; now
  neither spells it.
- **The re-entry refusal moved into the fold.** A `start` already `Downloading` emits nothing
  and never subscribes, so the second tap on Download is refused in the one place that is
  tested, rather than by an `if (…) return` each caller had to remember.
- **`finished()` needs no guard.** The pair it replaces ran `catch` and then `onCompletion`,
  which saw `failure == null` on a caught failure and so needed
  `&& state != ModelDownloadState.Failed` to avoid reporting Ready over a failure. The fold
  reaches `finished()` only on the path that did not throw.
- **Cancellation is rethrown untouched** and emits nothing: leaving the screen mid-transfer is
  not an outcome, and the last value stands. This is the behaviour ARC-063 gave
  `downloadProgress`, preserved rather than re-decided.
- `ModelDownloadState` moved to the new file — a same-package move, so no import changed.

## Evidence

`.claude/scripts/check.sh` and `.claude/scripts/test-fast.sh` both pass (242 tests, up from
233).

`ModelDownloadTest` — nine tests, the first JVM tests either screen's download has had:
the two `of()` rules, the `Downloading`-before-any-percentage first emission, the
0/10/55/90/100 fold ending `Ready` with `present`, a retry clearing the error it retries, an
`UnknownHostException` classified `NoConnection` while keeping the percentage it reached, the
failed update reporting the model still present, the refused second start, and cancellation
not being reported as a failure.

Mutation checks, one at a time, everything else in place:

```
# guard `if (start.state == Downloading) return@flow` removed
ModelDownloadTest > a second start while one is running emits nothing and never collects FAILED
242 tests completed, 1 failed

# `failed(e, presentOnFailure())` replaced with `failed(e, present = false)`
ModelDownloadTest > a failed update reports the model still present FAILED
242 tests completed, 1 failed
```

Both restored; `test-fast.sh` and `check.sh` green again.

## What this does not cover

No test drives `OnboardingViewModel` or `SettingsViewModel` themselves — they are
`AndroidViewModel`s, and this repo has no Robolectric. What is tested is the fold they both
now delegate to; that they call it with the right `replaceExisting` and store the result in
the right field is still only checked by the compiler and by reading. The screens'
rendering of `state.download` was not exercised on a device for this change.
