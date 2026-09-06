# ARC-028 — Comments point at files that no longer exist

- **Severity:** low
- **Status:** fixed
- **Area:** `speech/OnDeviceTranscriber.kt:19`, others

## Problem

`OnDeviceTranscriber.kt` line 19 refers the reader to `network/HarkenApi.kt`
for the shape of a transcribed segment. That file was deleted when Retrofit
went (ADR-0011). The comment is now a dead end that costs a reader a search
before they conclude it is stale.

## Fix

Sweep the comments for references to deleted files and either update them to
the surviving type or delete the sentence.

## Resolution

Swept every `path/like.this` in a Kotlin comment against the filesystem. Five
were dead, all pointing at the deleted MAUI client:

- `speech/OnDeviceTranscriber.kt` — `network/HarkenApi.kt`, cited as the shape a
  local segment deliberately is not. Fixed in the ARC-010 commit.
- `audio/AudioRecordCapture.kt`, `recording/RecordingController.kt`,
  `recording/RecordingForegroundService.kt`, `recording/RecordingState.kt` —
  four "Ports src/Harken.Mobile/..." provenance lines. The claim each one made
  after the path is still true and is kept; the path is deleted, because a
  reader cannot open it and finds nothing when they search.

Two `.cs` references survive on purpose — `audio/WavWriter.kt` cites
`src/Harken.Core/Audio/WavWriter.cs` and `WavWriterTest.kt` cites
`tests/Harken.Core.UnitTests/Audio/WavWriterTests.cs`. Both files exist today.
Both become dead the moment ADR-0015 Option A is accepted, and that ADR's own
consequences list is where that belongs.

`docs/plans/slice-*.md` still name `src/Harken.Mobile` throughout and were left
alone: they are records of what was planned at the time, not directions to a
reader of today's code.

## Evidence

The sweep itself: every `src/...` and `dir/File.ext` token in
`src/Harken.Android/app/src/**/*.kt` resolved against the working tree. It now
reports only false positives (a wrapped line, a relative path built at runtime
in a test, a trailing full stop).

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 151 unit tests,
  0 failures.
