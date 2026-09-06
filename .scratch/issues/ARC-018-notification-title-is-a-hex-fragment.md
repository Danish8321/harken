# ARC-018 — The recording notification is titled with eight hex characters

- **Severity:** medium
- **Status:** fixed
- **Area:** `recording/RecordingForegroundService.kt`

## Problem

```kotlin
recordingId.toString().take(8)
```

is used as the notification's title. The user, mid-meeting, sees `4f3a91c2`
sitting in their shade next to a red dot. This is the app's most persistent
surface — it is visible for the entire recording, on the lock screen, and it is
the thing a user taps to get back in.

The id is there because the notification is built before any title exists. But
the elapsed time and the recording's derived title (ARC-017) are both available
by then, and both are what a person would want to read.

## Fix

Title the notification with the app name or the derived title, and put the
elapsed time and level in the text line, which is what
`LiveUpdateNotification` is already shaped for.

## Resolution

The notification is titled with the name the recording will be saved under —
"Morning recording", or whatever the user's language calls it — resolved through
ARC-017's `Context.recordingTitle`. The elapsed time was already on the line
below it, as a chronometer while running and as frozen text while paused;
`notification_recording_body` now says "Recording" beside it, so the shade states
what the app is doing rather than only how long it has been doing it.

The title is fixed once, when the recording starts, and held on the service. Read
per notification it would rename itself under the user mid-recording — a capture
that begins at 11:58 would become "Afternoon recording" on the next refresh.

`notification_recording_title` ("Recording — %1$s") is deleted: with a real title
the prefix read "Recording — Morning recording".

### Known imprecision

The saved session derives its own title from `startedAt`, which is computed at
stop time as `endedAt - duration`. For a recording that crosses a boundary the
two can disagree — the shade says "Morning recording" and the library row says
"Afternoon recording". The row is the one that persists and it is derived from
the recording's real start, so it wins; the notification is transient and is not
worth a second clock to keep in step.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 151 unit tests, 0
  failures.

Not seen on a device. What a phone would show that a JVM cannot: the notification
itself, and whether backgrounding the app actually stops the meter recomposing.
