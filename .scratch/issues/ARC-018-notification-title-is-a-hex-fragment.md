# ARC-018 — The recording notification is titled with eight hex characters

- **Severity:** medium
- **Status:** open
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
