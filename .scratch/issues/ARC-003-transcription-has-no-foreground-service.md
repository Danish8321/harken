# ARC-003 — A transcription is killed whenever the user leaves the app

- **Severity:** critical
- **Status:** open
- **Area:** `speech/TranscriptionCoordinator.kt`

## Problem

`TranscriptionCoordinator` is a process-wide `object` running the decode on
`CoroutineScope(SupervisorJob() + Dispatchers.Default)`. Its doc comment
explains that it lives outside a ViewModel so navigating away does not cancel
it — which is correct as far as it goes, and stops one screen short.

Nothing holds the *process* up. The moment the last Activity stops, the process
becomes a cached process with no foreground component, and it is then the first
thing Android reclaims — while holding the ~610 MB working set
[ADR-0014](../../docs/adr/0014-minimum-supported-device.md) measured. A decode
of a 21-minute meeting takes 597 seconds on the reference device. Ten minutes
of not-looking-at-the-app is the normal case, not the edge case.

`failInterruptedTranscriptions` already exists to clean up after this, which is
the tell: the app has a recovery path for its most common failure instead of a
fix for it. UI-035 taught the message to name low memory as a possible reason;
on a device *above* the bar the same kill still happens for the ordinary reason
that a cached process holding 610 MB is what the LMK takes first.

## Fix

Run the decode inside a `dataSync` foreground service (or an expedited
`WorkManager` job, which wraps one) for the duration, with a notification that
shows which recording is being transcribed and lets the user cancel. The
coordinator's one-at-a-time invariant moves into the service.
