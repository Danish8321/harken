# ARC-033 — Nothing can leave the app except a copied transcript

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/SessionSheet.kt`, `export/`, `ui/SettingsScreen.kt`

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

## Resolution — export everything

Settings grew a Backup card: one button, `ACTION_OPEN_DOCUMENT_TREE`, and every recording
plus its transcript written into the folder the user picked. No storage permission is ever
requested — the app holds one directory grant, for as long as the copy takes, and
`ExportService` releases it in its `finally`.

The work is split so the interesting parts are testable off-device:

- `data/TranscriptText.kt` — the transcript as text, for every copy of it that leaves the
  app. The session sheet used to build its own string with raw second counts (`[742s]`),
  a number no reader can place in a recording, and it would have disagreed with whatever
  the export wrote. One formatter now; `plainText` delegates to it.
- `data/ExportNaming.kt` — `<date> <time> <title>`, so a year of recordings sorts
  chronologically by name alone. Strips the characters no common filesystem takes (the
  union of the Windows set and POSIX's separator, because the point of these files is
  that they get copied elsewhere), the trailing dots and spaces Windows removes silently,
  and numbers a collision rather than overwriting it.
- `export/LibraryExporter.kt` — the copy itself, through `DocumentsContract`. Streams the
  WAV in 64 KB chunks with a cancellation check per chunk, so stopping a multi-gigabyte
  export does not mean waiting for the current three-hour recording to finish. A file the
  destination refuses is counted and skipped: one bad recording must not cost the user
  the other ninety-nine.
- `export/ExportService.kt` — a `dataSync` foreground service, for the same reason
  `TranscriptionService` is one. Run from a ViewModel the copy would die on the first
  navigation and leave a folder of half-written files that *looks* like a backup, which
  is worse than no backup because the user would not know.
- `export/ExportStatus.kt` — process-wide progress, read by Settings. A ViewModel that
  outlived the export would be the bug, not the fix.

The result line reports what is not simply "it worked": recordings whose audio was already
gone (transcript written anyway), and files the destination refused. A backup the user
believes is complete and is not is the exact failure this feature exists to prevent.

### Deviation

No new dependency. `androidx.documentfile` is the usual way to write into a tree Uri;
`DocumentsContract.createDocument` against the tree's own document Uri is the same three
calls without it.

`ExportItem` holds every transcript in memory for the length of the export — text, so a
few hundred recordings is a couple of megabytes. The audio, which is the part measured in
gigabytes, is streamed from its path and never loaded.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==` (debug, release and lint)
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 139 unit tests, 0 failures.
  Nineteen are new: timestamp formatting and the transcript file header, the naming rules
  (zone, forbidden characters, length, collisions), and the size formatter.

`LibraryExporter.export` itself is not unit-tested: it is `ContentResolver` and
`DocumentsContract`, which is what an instrumented test is for. Its two decisions that are
not I/O — what a file is called, and what goes in the transcript — are the two objects
that *are* tested.

## Device verification — not done

No device was attached (`adb devices` is empty). Outstanding, on a fresh install:

- Export into a Downloads subfolder and open the result on a desktop: every recording
  present, playable, and paired with a readable `.txt`.
- Cancel mid-export from the notification and confirm the app stops promptly and says so.
- Export twice into the same folder and confirm the second run does not overwrite the
  first (the SAF provider's own de-duplication, since the in-run `taken` set is fresh).
