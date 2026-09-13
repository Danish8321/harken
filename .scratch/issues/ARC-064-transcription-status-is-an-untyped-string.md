# ARC-064 — Transcription status is an untyped string, and the delete guard reads the wrong one

- **Severity:** medium
- **Area:** `data/SessionRepository.kt`, `data/local/SessionDao.kt`, `ui/LibraryViewModel.kt`, `ui/LibraryScreen.kt`, `ui/SessionSheetViewModel.kt`
- **Status:** fixed

## Problem

`SessionView.status` was a nullable `String`. The values it could hold were written as SQL
literals in the DAO (`'Running'`, `'Succeeded'`, `'Failed'`) and as a Kotlin literal in the
repository (`"Recorded"`), and read back by comparing against literals in three UI files.
Nothing named the set, so nothing could check a member against it.

Two defects came out of that.

### 1. A branch on a value nothing ever wrote

`"Pending"` was read in four places:

```kotlin
// LibraryViewModel.subtitle
val transcribing = visible.count { it.status == "Pending" || it.status == "Running" }
// LibraryScreen — the haptics on a settled transcription
val settled = statuses.filterKeys { before[it] == "Running" || before[it] == "Pending" }
// SessionCard
val transcribing = isTranscribing || s.status == "Pending" || s.status == "Running"
```

and written nowhere. `git log -S"'Pending'" --all` and `git log -S'"Pending"' --all` both
return nothing, so it was never written in the app's history either — no persisted row can
hold it and no migration is needed. `LibrarySearchTest` had drifted further still, building
a fixture with `status = "Completed"`, a fifth spelling belonging to no one.

The dead branch was not the expensive part. `SessionCard`'s `when` ended in
`else -> CardAction.Transcribed`, so any status it did not recognise — a typo in a literal, a
value written by a future writer — reported the recording as transcribed. That is the one
direction that asserts finished work about a row nothing can vouch for.

### 2. The delete guard consults the row, and the row is one hop behind

`TranscriptionCoordinator.activeSessionId` is set the instant Transcribe is tapped;
`sessions.transcriptionStatus` flips to `Running` a coroutine hop later, once Room's write
lands. `SessionCard` knew that and ORed both together. `isSelectable` did not:

```kotlin
private fun isSelectable(session: SessionRepository.SessionView) = session.status != "Pending" && session.status != "Running"
```

So for the length of that hop, a recording being decoded could be long-pressed into the
selection and deleted — `purge` deletes the WAV the running decode is reading, and the row it
is about to write. Two separate answers to "is this transcribing?", and every caller had to
remember to ask both.

## The fix

`TranscriptionStatus` — an enum of the four states that exist, carrying its own persisted
spelling:

```kotlin
enum class TranscriptionStatus(val stored: String) {
    Recorded("Recorded"), Running("Running"), Succeeded("Succeeded"), Failed("Failed");

    companion object {
        fun of(stored: String?): TranscriptionStatus = entries.firstOrNull { it.stored == stored } ?: Recorded
    }
}
```

- **Typed at the repository seam.** `SessionRow.transcriptionStatus` stays a nullable `String`
  — the column is unchanged, so there is no migration — and `SessionRepository.toView` maps it
  through `of()`. Everything above the repository holds the type.
- **Unrecognised reads land on `Recorded`**, including the null the column still permits. That
  is the recoverable direction: a `Recorded` card offers Transcribe, so the worst outcome is a
  user transcribing something already transcribed. The `else` this replaces claimed the
  opposite.
- **The DAO binds, never writes literals.** All four writes take the value as a `:parameter`
  from `TranscriptionStatus`, so the type is the single definition of what is in the column.
  Room cannot run on this repo's JVM test runner, so a literal drifting from a member is a
  bug no test here could catch — the way to not have it is to not have a second copy.
- **One effective status.** `LibraryUiState.statusOf(session)` folds `transcribingSessionId`
  into the row's status, and `isSelectable`, `subtitle` and `SessionCard` all read that. The
  OR inside `SessionCard` is gone, and its `when` is exhaustive over the enum.
- `SessionSheetUiState.status` is `TranscriptionStatus?`, where null now means "not loaded
  yet" rather than "some string we don't recognise".

## Evidence

`.claude/scripts/check.sh` and `.claude/scripts/test-fast.sh` both pass (233 tests).

New tests:

- `TranscriptionStatusTest` — every member round-trips `stored`; `null`, `"Pending"`,
  `"Completed"`, `""` and `"running"` all read as `Recorded`.
- `LibrarySelectionTest` gains two on the coordinator's gap: a row whose stored status is
  still `Recorded` but which `transcribingSessionId` names cannot be long-pressed into
  selection, and cannot be added to a selection already open.

Mutation check on the second defect — `isSelectable` put back to reading `session.status`
directly, with everything else in place:

```
LibrarySelectionTest > a row the coordinator just started cannot be added to a selection already open FAILED
LibrarySelectionTest > a long-press is refused from the tap that starts a transcription, not from the row's write FAILED
233 tests completed, 2 failed
```

Restored, both pass.

Emulator regression pass (Nothing Phone 2 geometry, `emulator-5554`), recording a fresh
session and driving it through the Library. The first `uiautomator` sample after the
Transcribe tap:

```
text="3 recordings · 1 transcribing" text="Transcribing" text="Transcribed" text="Transcribed"
```

Both the chip and the subtitle read the coordinator through `statusOf` — they say
"transcribing" on the tap, not a hop later. The next sample reads `3 recordings` with three
`Transcribed` chips, so the settle still lands. A long-press then opened selection
(`1 selected`), and Delete took the list from three recordings to two.

## What this does not cover

The DAO's four `UPDATE`s are still only checked by construction. `SessionDao`'s behaviour
needs Room, which needs an instrumented run (`androidTest`), and the two instrumented tests
that build a `SessionRow` now use `TranscriptionStatus.Succeeded.stored` rather than a
literal of their own — but nothing asserts that, say, `markLocalTranscriptionStarted` leaves
a row that `of()` reads back as `Running`. That assertion belongs in `androidTest` and is not
written.
