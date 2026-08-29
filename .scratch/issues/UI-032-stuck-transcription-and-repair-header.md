# UI-032 — Stuck-transcription reconciliation + wire up WavWriter.repairHeader

- **Severity:** medium
- **Status:** open — deferred out of the error/warning-surfacing pass (see UI-033).
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
