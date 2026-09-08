# ARC-052 — Recording notification channel name is hardcoded

- **Severity:** low
- **Status:** fixed
- **Area:** `recording/RecordingForegroundService.kt`

## Problem

`RecordingForegroundService.kt:385-390`:

```kotlin
manager.createNotificationChannel(
    NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW),
)
```

`TranscriptionService` and `ExportService` both name their channel via a string resource
(`R.string.notification_transcribing_channel`, `R.string.notification_exporting_channel` —
confirmed in `strings.xml:28,188`). `RecordingForegroundService` — the channel a user sees
most often, in system Settings → App notifications — hardcodes `"Recording"` with no
matching `strings.xml` entry, inconsistent with the app's own established pattern and left
untranslated.

## Fix

Add `notification_recording_channel` to `strings.xml` and use
`getString(R.string.notification_recording_channel)`.

## Found by

Third fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Added `notification_recording_channel` to `strings.xml` next to its transcribing/exporting
siblings, and switched the channel creation to `getString(R.string.notification_recording_channel)`.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK.
