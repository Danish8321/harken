# ARC-007 — A deleted recording reappears at the next launch

- **Severity:** high
- **Status:** open
- **Area:** `data/SessionRepository.kt`, `recording/RecordingRecovery.kt`

## Problem

`SessionRepository.purge` deletes the database row first and the WAV second,
and logs-and-continues when the file delete fails:

```kotlin
dao.deleteSession(id)
if (!file.delete()) Log.w(TAG, "...")
```

`RecordingRecovery.recover()` runs on every launch and adopts any WAV on disk
whose UUID filename is not in the session table. So a failed delete does not
leave a harmless orphan: it leaves a file that the app is *designed* to
resurrect. The user deletes a recording, restarts the app, and it is back —
with a derived title, because the row that carried the real one is gone.

Delete is the one operation a user expects to be final, and this is the one
place where the two mechanisms — purge and recovery — were written without
reference to each other.

## Fix

Delete the file first and only remove the row if that succeeds; if the file
cannot be deleted, keep the row and surface the failure. A tombstone table
would also work but is more machinery than the problem needs.
