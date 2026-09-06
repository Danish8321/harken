# ARC-033 — Nothing can leave the app except a copied transcript

- **Severity:** medium
- **Status:** open
- **Area:** `ui/SessionSheet.kt`

## Problem

The session sheet can copy or share the transcript text. There is no way to get
the audio out, and no way to back anything up — and with ARC-002 fixed there
will deliberately be no Auto Backup either, so a lost phone is every recording
gone.

For an app whose premise is that the recording never leaves the device, the
user's own copy is the only safety net there can be, and it does not exist.

## Fix

Share the WAV via a `FileProvider`, and an explicit "export everything" that
writes the audio plus a transcript file per session to a user-chosen directory
through the Storage Access Framework. Both are user-initiated, which keeps the
privacy claim intact.

## Progress — sharing (done)

The Share button in the session sheet called

```kotlin
fun share() { /* wired by the host Activity via ACTION_SEND, unchanged from the previous build */ }
```

an empty method behind a live button: sharing a transcript did nothing at all. It now
sends `ACTION_SEND` with the transcript text, and a second action shares the recording
itself through a `FileProvider` (`${applicationId}.files`, `exported=false`,
`grantUriPermissions=true`) with `FLAG_GRANT_READ_URI_PERMISSION` — one read grant, for
one file, to the one app the user picks. Disabled rather than hidden when the WAV is gone,
so the action does not appear and disappear between two recordings that look the same.

Known rough edge: the shared file keeps its on-disk name, `<session id>.wav`. Renaming it
for the receiver would mean copying the file (a three-hour recording is ~345 MB) or a
custom provider that overrides the display name; neither is worth it before anyone has
complained about the name.

Evidence: `.claude/scripts/check.sh` OK.

## Remaining — export everything

The Storage Access Framework half is not built: a user-chosen directory receiving every
recording's audio plus a transcript file. That is the actual backup story; sharing covers
one recording at a time.
