# ARC-056 — A late progress update re-posts the transcribing notification after the service stops

- **Severity:** medium
- **Status:** fixed
- **Area:** `speech/TranscriptionService.kt`

## Problem

The service stops itself from a collector on the main thread:

```kotlin
TranscriptionCoordinator.activeSessionId.collect { active ->
    if (active != sessionId) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
```

and it renders progress from the decode thread:

```kotlin
private fun publishProgress(fraction: Float) {
    val percent = (fraction * 100).roundToInt().coerceIn(0, 100)
    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(percent))
}
```

Nothing orders those two. `onProgress` is called per span by the coordinator's decode loop;
the coordinator clears `activeSessionId` in its own `finally`. If the last span's callback
lands after the collector has already run `stopForeground(STOP_FOREGROUND_REMOVE)`, the
`notify` posts notification 1002 again — this time as an ordinary notification, with no
foreground service attached to it and nothing left alive to cancel it.

What is then on screen is "Transcribing…" with a progress bar, for a transcription that has
finished, and it stays until the user swipes it away. It is dismissible, unlike ARC-043's,
so it is a smaller bug than that one — but it is the same notification telling the same lie,
and this time it is reachable without a model-fetch failure.

Seen on the device pass for slice 11 (Nothing Phone (2), Android 16): a `transcribing`
notification still posted after the transcription had finished. Recorded there as a
follow-up rather than diagnosed, and the reading above is from the code, not from a second
device run.

## The fix

`ProgressGate` (`speech/ProgressGate.kt`): a one-way gate both paths go through.
`publishProgress` notifies inside `gate.render { }`, and the collector removes the
notification inside `gate.close { }`.

A flag was the first idea and is not enough. A thread can read a flag as "still open",
be descheduled, and post after the other thread has written it and removed the
notification — the flag narrows the window rather than closing it, whether it is
`@Volatile` or an `AtomicBoolean`. The gate takes one lock on both sides instead, so a
render either finishes before the removal or never runs.

The gate does not re-open: a service instance renders one transcription and then stops, so
re-opening could only ever mean the wrong session's progress under the same id.

`onDestroy` was the other candidate and has the same hole — the late `notify` can land
after `onDestroy` has run.

## Evidence

`check.sh` OK, `test-fast.sh` OK, `ProgressGateTest` 5/5. Falsified: swapping the gate for
the plain flag this ticket first proposed fails `a render that arrives while the close is
removing the notification waits, and is skipped`, and nothing else (203 tests completed, 1
failed).

**Still owed a device pass, and blocked on one thing.** Reproducing it means running a
real transcription, which means the 148 MB `ggml-base.en.bin`, which this phone does not
currently have and which its only live network is metered LTE — no Wi-Fi reachable in the
session that would otherwise have done it. Deferred rather than paid for out of the user's
cellular data; it is a fifteen-minute pass the next time the phone is on Wi-Fi, and the
thing to watch is the notification shade in the seconds after the Library flips the
session to done.

The symptom was seen once during slice 11's Task 10 pass
(`docs/plans/slice-11-import-audio.md`); the mechanism is read off the code, and what a
phone would show is a "Transcribing…" notification with a progress bar, dismissible,
for a decode the Library already lists as done or cancelled.
