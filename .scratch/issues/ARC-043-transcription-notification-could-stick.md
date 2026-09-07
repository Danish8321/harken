# ARC-043 — The transcribing notification could stay up forever

- **Severity:** medium
- **Status:** done
- **Area:** `speech/TranscriptionService.kt`

## Problem

The service stops itself when the coordinator goes idle, by watching
`activeSessionId`:

```kotlin
TranscriptionCoordinator.activeSessionId.drop(1).collect { active ->
    if (active == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
}
```

`drop(1)` assumes the collector attaches while the decode is still running — the
comment says so: *"activeSessionId is already non-null by the time this
collects"*. It is not guaranteed. `transcribe` launches on
`Dispatchers.Default` and returns immediately; the collector is only launched
several statements later. A transcription that fails at once — no model and no
network is the realistic case, and now the most likely one after ARC-042 made
that path distinct — can clear `activeSessionId` inside that window.

Then `drop(1)` discards the very `null` the collector was waiting for, nothing
else is ever emitted, and the service holds a `dataSync` foreground
notification open with a progress bar frozen at 0% for a transcription that
already ended. The only ways out are force-stop or reboot.

Narrow, but the failure is unbounded and the user cannot dismiss a foreground
notification.

## The fix

Watch for "no longer ours" rather than "not the first value":

```kotlin
TranscriptionCoordinator.activeSessionId.collect { active ->
    if (active != sessionId) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
}
```

Correct whichever order the two coroutines run in: attached during the decode,
the first value is our own id and nothing happens; attached after it finished,
the first value is `null` and the service stops immediately. It is also shorter,
and it drops the `drop` import.

## Evidence

`check.sh` and `test-fast.sh` green. Not reproduced on a device: the window is
microseconds wide and needs a model-fetch failure to hit. What a phone would
show is a stuck "Transcribing 0%" notification for a recording the Library
already lists as failed.
