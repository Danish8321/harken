# ARC-056 — A late progress update re-posts the transcribing notification after the service stops

- **Severity:** medium
- **Status:** open
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

Make the two paths agree on one piece of state — the service's own "am I still the one
rendering this" flag, set false where the collector stops it, and checked in
`publishProgress` before it notifies. Both touch it from different threads, so it wants to
be an `AtomicBoolean` or `@Volatile`, not a plain `var`.

A cancel in `onDestroy` is not enough on its own: the late `notify` can land after
`onDestroy` has already run, and the ordering the flag fixes is the same one either way.

## Evidence

Not yet fixed. The mechanism is read off the code; the symptom was observed once on device
during slice 11's Task 10 pass and is noted in `docs/plans/slice-11-import-audio.md`.
