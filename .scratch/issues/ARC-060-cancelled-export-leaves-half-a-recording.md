# ARC-060 — Cancelling an export leaves half a recording in the backup folder

- **Severity:** high
- **Status:** fixed
- **Area:** `export/LibraryExporter.kt`, `export/ExportService.kt`

## Problem

`ExportService`'s KDoc says why it is a foreground service at all:

> Run from a ViewModel it would die the moment they left Settings, leaving a directory of
> half-written recordings that looks like a backup — which is worse than no backup, because
> they would not know.

It guards against exactly one way that happens — the process dying — and the copy loop
underneath it has two more, both reachable with the app alive and behaving normally.

**A cancelled copy leaves the document it had started.** `LibraryExporter.copy` created the
document before it had a byte to put in it:

```kotlin
val target = create(parent, "audio/x-wav", displayName)
source.inputStream().use { input ->
    resolver.openOutputStream(target)?.use { output ->
        while (true) {
            currentCoroutineContext().ensureActive()
            ...
```

`ensureActive` throws out of both `use` blocks, which close the streams — and that is all
they do. The document stays in the user's chosen folder, correctly named, playable, holding
however much of the recording had been copied. Cancel is a button on the export notification,
so this needs nothing to go wrong: the user stops a backup and is left with a file that
claims to be a recording and is a fraction of one. A mid-copy `IOException` — the destination
filling up, a card pulled — leaves the same thing, and is counted as `failed: 1` in a report
that says nothing about what is still on disk.

**A cancellation was counted as one file failing.** The loop wrapped each copy in
`runCatching`, which catches `Throwable`:

```kotlin
val written = runCatching { copy(parent, audio, "$name.wav") }
    .onFailure { Log.w(TAG, "Could not export audio for item $index", it) }
    .getOrNull()
if (written == null) failed++
```

So a `CancellationException` from inside the copy was swallowed, `failed` was incremented for
a recording nothing was wrong with, and the loop carried on to write that item's transcript
before the *next* item's `ensureActive` finally stopped it. The class's own doc claimed
"Cancellation is honoured between files and inside a copy"; between files it was, inside a
copy it was not. This is what the cancellation test caught — the WAV was correctly discarded
and a `.txt` for the same recording was still sitting in the folder afterwards.

**The reported size counted characters, not bytes.** `bytes += text.length` on a `String`
that was written as `text.toByteArray()`. Every non-ASCII character in a transcript made the
"you backed up N" line an undercount. Cosmetic beside the other two, fixed here because it is
the same three lines.

## Related but not this

ARC-055 and the ingest side solved the identical problem for imports by staging under a
`.partial.wav` name in `cacheDir` and only moving the file into place once it is whole. The
export cannot borrow that: it writes through the Storage Access Framework into a folder the
app has one temporary grant on, and SAF renaming is provider-dependent. Deleting the document
it created is the equivalent that works here.

## The fix

`copy` and `write` now own the document's whole life:

```kotlin
val document = destination.create("audio/x-wav", displayName)
var finished = false
try {
    ...
    document.close()
    finished = true
} finally {
    if (!finished) document.discard()
}
```

and the two `runCatching` blocks became explicit `try`/`catch` that rethrow
`CancellationException` and swallow only `Exception`.

Getting a test onto any of this needed a seam. `LibraryExporter` took a `ContentResolver` and
a tree `Uri` and called `DocumentsContract` statics, which are unmocked stubs on the JVM — so
the copy loop, which is the whole of the app's backup story, had no test at all and could not
have had one. It now takes an `ExportDestination` that hands back an `ExportDocument`
(`stream`, `close`, `discard`); `SafDestination` is the real one and lives in the same file.
The class already claimed to be testable without a device — "it is given a resolver, a
destination and a list, and it reports what it wrote" — and this is what makes that true.

## Evidence

`.claude/scripts/test-fast.sh`: **215 tests, 0 failures** (211 before). `check.sh` OK.

`LibraryExporterCopyTest`, four tests against an in-memory destination that records what was
created and what survived.

Each of the three fixes falsified on its own, and each brought down exactly the test written
for it and nothing else:

| reverted | fails |
|---|---|
| `discard()` on the unfinished document | `a copy that fails part way leaves nothing behind`, `cancelling mid-copy leaves nothing behind` |
| `runCatching` back on the audio copy | `cancelling mid-copy leaves nothing behind` |
| byte count back to `String.length` | `the reported size counts the transcript's bytes, not its characters` |

`a copy that finishes survives whole` is the control: it stays green through all three, which
is what says the discard only fires on the path it is meant to.

**Not reproduced on a device.** Doing so means a real folder picker, a real SAF provider and a
library big enough that a Cancel tap lands inside a copy rather than between two. What the
tests prove is that the exporter discards the document and stops; what they cannot prove is
that `DocumentsContract.deleteDocument` succeeds against whatever provider backs the folder
the user picked — a failure there is logged and swallowed, which leaves the original bug for
that provider. Worth a pass in the same device session as ARC-056.
