# UI-037 — Stuck-transcription reconciliation + wire up WavWriter.repairHeader

> Renumbered from UI-032 by [ARC-027](ARC-027-duplicate-tracker-id.md): this
> ticket and `UI-032-on-device-pivot-leftovers.md` were both filed as UI-032.
> The other one keeps the number — it is the one the index and the code
> comments already point at. Commits dated on or before 2026-09-05 that say
> "UI-032" may mean either; after that date it means the pivot-leftovers ticket.

- **Severity:** medium
- **Status:** fixed — both halves closed, see "Resolution" below.
- **Area:** `speech/TranscriptionCoordinator.kt`, `audio/WavWriter.kt`,
  `data/SessionRepository.kt`

## Problem

Found during the error/warning audit for UI-033.

1. **No stuck-transcription reconciliation.** `TranscriptionCoordinator`
   runs jobs on a raw `SupervisorJob() + Dispatchers.Default` scope, no
   `WorkManager`, no persistence of in-flight state. If the process is
   killed mid-transcription, the session stays in `Running` status
   forever — no timeout, no reconciliation check on next launch, no way
   for the user to retry short of manual intervention.

2. **`WavWriter.repairHeader` is dead code.** Documented as the recovery
   path for "process died mid-capture" (unpatched WAV header), but no
   caller exists anywhere in the app (verified via grep). A foreground
   service killed mid-recording currently leaves a WAV file with an
   invalid header and nothing ever repairs it.

## Why deferred

Both are reconciliation/recovery features in their own right — a
timeout + on-launch reconciliation design, and wiring a repair path into
app startup or session load — not simply "surface an existing failure to
the user." Scoped separately from UI-033 per user decision during the
error/warning grill.

## Suggested shape (not yet designed)

- On app start (or `LibraryViewModel` init), scan for sessions stuck in
  `Running` past some threshold (e.g. 10 min) and flip them to `Failed`
  with a reconciliation reason, surfaced via the existing Library "Failed"
  chip (UI-033's pattern).
- On app start, scan for recordings with an unpatched WAV header (e.g. a
  sentinel/marker left by the foreground service, or comparing declared
  vs actual file size) and run `WavWriter.repairHeader` before the file
  is offered for transcription.

## Resolution

**1. Stuck-transcription reconciliation — `2a07c85`, completed by `fac11e7`.**
No timeout threshold was needed: a transcription cannot outlive its process, so
*any* row still `Running` at launch died with the process. `MainActivity`
settles them next to the orphan-recording sweep via
`SessionRepository.failInterruptedTranscriptions()`, which writes
`error_transcription_interrupted` as the reason and emits
`transcription_interrupted_recovered`. The Library then offers Transcribe on
`Failed` rows (previously only on `Recorded`), and `fac11e7` renders the reason
on the card, since the message existed but no screen read it.

Verified on the release build on the Nothing Phone 2, 2026-09-05: killing the
app inside the first decode logged
`transcription_interrupted_recovered sessions=1` at relaunch, the row showed
*"Transcription stopped when the app closed. Tap to try again."* with a
Transcribe button, and the retry completed (171 s in, 16 s decoded over 2 spans,
4 segments).

**2. `WavWriter.repairHeader` is wired.** `RecordingRecovery` (`recording/`)
calls it on every orphan WAV it adopts at launch
(`RecordingRecovery.kt:41`), so a capture killed mid-recording gets its header
patched before the file is offered for transcription. Also covered by
`WavWriterTest.repairHeaderFixesAnOrphanedFile` and
`repairHeaderIsANoOpWhenAlreadyCorrect`.

A third recovery path was added alongside these and is worth listing here:
`ModelDownloadManager.discardPartialDownload()` reclaims an abandoned model
partial older than 24 h, so the same class of "process died mid-operation" leak
is now handled for recordings, transcriptions and downloads alike.
