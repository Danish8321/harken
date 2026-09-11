# ARC-055 — A killed import leaves its staged copy in the cache forever

- **Severity:** medium
- **Status:** open
- **Area:** `ingest/ImportStaging.kt`, `ingest/AudioImporter.kt`, `HarkenApplication` / `MainActivity`

## Problem

An import writes two files into `cacheDir` and deletes both on every path it controls:

- `import-<uuid>` — the byte-for-byte copy of the user's file, made because a `content://`
  grant does not outlive the activity that received it (`ImportStaging.stage`).
- `<uuid>.partial.wav` — the decode target, promoted into `filesDir` only once the decode
  finished (`AudioImporter`).

The path it does not control is the process dying mid-import: a swipe-away, a low-memory
kill, a crash. `ImportService` is a foreground service, so this is uncommon, but when it
happens both files stay, and nothing sweeps `cacheDir` at startup. Android reclaims a cache
directory only when the device is low on storage, which is exactly when it is least helpful
to have been carrying the garbage.

Found by slice 11's device pass. What the phone actually held afterwards was a 541 MB
`import-b6c3a2e8-…` and a 20 MB `4c78f98a-….partial.wav`, from an import killed on purpose
to test exactly that path — deleted by hand over adb, which is not a fix.

The size is what makes it more than tidiness: a staged copy is the size of the source file,
and the slice's own test material included a 576 MB one.

## The fix

A startup sweep, in the same shape as `RecordingRecovery`: a directory listing against a
prefix, run once per launch, deleting nothing it does not recognise.

```kotlin
// Cache files an import owns, left by a process death. Anything older than the
// launch is safe: an import in this process has not started yet.
cacheDir.listFiles()
    ?.filter { it.name.startsWith("import-") || it.name.endsWith(".partial.wav") }
    ?.forEach { it.delete() }
```

The one thing it must not do is run while an import is live — a second `MainActivity` in
its own task is reachable through the share target, and that is precisely how ARC-056's
sibling bug (recovery adopting the live recording, slice 11 task 11) was found. Either the
sweep runs before any import can be admitted, or it takes the in-flight staged path the way
`RecordingRecovery` takes the in-progress recording id.

`filesDir` is not swept: a WAV there is either a session's audio or an orphan
`RecordingRecovery` is about to adopt, and deleting on a guess there loses a recording.

## Evidence

Not yet fixed. Reproduced on the device pass by killing the app mid-import
(`am force-stop`) and listing `cacheDir` afterwards; see the Task 10 record in
`docs/plans/slice-11-import-audio.md`.
