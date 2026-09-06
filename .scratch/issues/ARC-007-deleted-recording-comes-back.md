# ARC-007 — A deleted recording reappears at the next launch

- **Severity:** high
- **Status:** closed
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

## Resolution

`SessionRepository.purge` deletes the audio first and the row second, and fails
the whole operation if the file cannot be deleted:

```kotlin
val audio = dao.findById(id)?.pendingUploadPath
audio?.let { path ->
    val file = java.io.File(path)
    check(!file.exists() || file.delete()) { "Could not delete the audio for this recording" }
}
dao.deleteSession(id)
```

The old order looked harmless — a file the delete missed is just an orphan —
except that `RecordingRecovery` is built to adopt orphans, so the recording came
back at the next launch with a derived title. Delete is the one operation a user
expects to be final, so a half-done one is no longer reported as done:
`SessionSheetViewModel` already surfaces the `Result` failure as a toast.

No tombstone table: it is more machinery than the problem needs, and the
ordering makes the two mechanisms agree without one.

## Evidence

`check.sh` OK, `test-fast.sh` OK. Not unit-tested: `SessionRepository` takes a
`HarkenDatabase`, so a fake would mean implementing the whole DAO — verified on
device instead.

## Device verification

Nothing Phone 2, fresh install. Deleted a recording from the session sheet, then
`adb shell am force-stop` and relaunched. `run-as ... ls files` shows the deleted
recording's WAV gone and only the surviving recording's file left; Library reads
"1 recording" after the restart, and `RecordingRecovery` logged nothing. Before
this change a delete whose file removal failed came back at exactly this point.
