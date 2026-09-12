# ARC-055 — A killed import leaves its staged copy in the cache forever

- **Severity:** medium
- **Status:** fixed
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

`ImportStaging.sweep(cacheDir)`, called from `MainActivity`'s launch path right after
`RecordingRecovery` — the same shape as recovery, and guarded the same way. `leftovers` is
the pure half and the test seam: the files whose name starts `import-` or ends
`.partial.wav`, directories excluded, everything else in the cache left alone.

The guard is the part that matters. The sweep cannot tell a file abandoned by a dead
process from one an import is writing this second, so it only runs when
`ImportCoordinator.activeImportId.value` is null. A share lands in its own task and runs
the launch path again while an earlier import is still going — the same route that let
recovery adopt a live recording (slice 11 task 11) — and there the sweep is skipped
entirely. Skipping costs nothing: the next launch sweeps.

`.partial.wav` was a literal in `AudioImporter` and is now `ImportStaging.PARTIAL_SUFFIX`,
because the sweep has to recognise the same name the importer writes and two copies of it
would drift.

`filesDir` is still never swept: a WAV there is either a Session's audio or an orphan
`RecordingRecovery` is about to adopt, and deleting on a guess there loses a recording.

## Evidence

`check.sh` OK, `test-fast.sh` OK, `ImportStagingSweepTest` 5/5. Falsified: dropping the
`isFile` guard fails `a directory is never swept, whatever it is called` and nothing else
(208 tests completed, 1 failed).

Device pass on 'AIN065' (Nothing Phone (2), Android 16). The original leftovers — a 541 MB
`import-…` and a 20 MB `…partial.wav`, recorded in the Task 10 pass in
`docs/plans/slice-11-import-audio.md` — were deleted by hand before this existed, so the
pass plants the same four shapes instead and drives a cold launch:

```
$ adb shell run-as com.harken.android.debug sh -c 'cd .../cache && ls -la'
abc123.partial.wav      harken-local.db.lck     import-deadbeef
import-dir/             keepme.txt
$ adb shell am force-stop com.harken.android.debug
$ adb shell monkey -p com.harken.android.debug -c android.intent.category.LAUNCHER 1
$ adb shell run-as com.harken.android.debug sh -c 'cd .../cache && ls -la'
harken-local.db.lck     import-dir/             keepme.txt
```

Both matching files gone after one launch. The three non-matching entries survived, and
they are the three ways the sweep could have been too greedy: `import-dir` is a directory
whose name starts `import-` (the `isFile` guard), `keepme.txt` matches neither rule, and
`harken-local.db.lck` is Room's lock — a file the sweep deleting would be worse than the
bug it fixes. Planted files removed afterwards.

The guard's other half — the sweep skipped entirely while `activeImportId` is non-null —
is covered by `ImportStagingSweepTest` and is not reachable from adb without racing a live
import against a launch, so it stays unit-tested only.
