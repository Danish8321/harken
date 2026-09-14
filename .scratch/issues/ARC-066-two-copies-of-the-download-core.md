# ARC-066 — `ensureModel` and `downloadProgress` each write out the download

- **Severity:** low
- **Area:** `speech/ModelDownloadManager.kt`
- **Status:** fixed

## Problem

Slice-09 review finding **S5**. ARC-063 had already factored out the leaf work —
`downloadTo`, `installPartial`, `verifyPartial` — but the sequence that uses them was still
written twice:

```kotlin
// ensureModel()
downloadLock.withLock {
    if (!modelFile.exists()) { modelsDir.mkdirs(); downloadTo(partialFile); installPartial() }
}
// downloadProgress(replaceExisting)
downloadLock.withLock {
    if (modelFile.exists() && !replaceExisting) return@withLock
    modelsDir.mkdirs(); downloadTo(partialFile) { percent -> emit(percent) }; installPartial()
}
```

Four lines, plus the comment explaining why the lock is a lock, in two places. The two copies
also disagreed about one thing — only the flow knew about `replaceExisting`, the Settings
"update" action — and nothing tested that, so the disagreement was invisible.

## The fix

One private `installIfMissing(replaceExisting, onProgress)` holding the locked section: the
re-check inside the lock, `mkdirs`, `downloadTo`, `installPartial`. Both entry points call it
and keep everything else.

- **The core is silent and has no dispatcher.** `ensureModel` keeps `withContext(IO)`,
  `runCatchingDownload` and `Log.e(TAG, "ensureModel download failed")`; `downloadProgress`
  keeps `flowOn(IO)`, its `CancellationException` rethrow and
  `Log.e(TAG, "downloadProgress failed")`. The two messages are how logcat says which entry
  point failed, and `flowOn` is what makes the flow's cancellation behave the way ARC-063
  fixed it — neither belongs to a function that cannot tell which caller it is serving.
- **`ensureModel` was not rewritten as `downloadProgress().collect {}`**, which the finding
  offered first. It would make one path of two, at the cost of giving a caller that wants a
  path and a `Result` a Flow and its progress machinery, and of folding together two
  cancellation stories that `cancelling ensureModel stops the transfer, with no progress
  emission to catch it` exists to keep apart.
- **The early exits stay in the wrappers**, where they differ: `ensureModel` returns the path
  it already has, `downloadProgress` emits `100`.

## Evidence

`.claude/scripts/check.sh` and `.claude/scripts/test-fast.sh` both pass (244 tests, up from
242). The 16 tests `ModelDownloadManagerTest` already had — integrity, the shared-download
race, resume-after-cancel, both cancellation paths — pass unchanged, which is what a
refactor's evidence looks like.

Two new tests cover `replaceExisting`, which nothing exercised before and which is now the one
decision the shared core makes on behalf of both callers:

- `an update re-fetches a model that is already installed` — after `ensureModel` has installed
  it, `downloadProgress(replaceExisting = true)` makes a second request and the model is still
  present afterwards. The installed file is deliberately not deleted first, so what proves the
  update ran is the request count, not a missing file.
- `a model already installed is reported complete without a request` — the default path emits
  `[100]` and never touches the network.

Progress arrives once per 64 KB chunk, so both tests see exactly `[100]` from a 4 KB fixture;
the request count is what tells the two apart. An assertion of mine that expected more than one
emission failed for that reason and was wrong, not the code.

Mutation check — `replaceExisting` dropped from the core's guard, everything else in place:

```
ModelDownloadManagerTest > an update re-fetches a model that is already installed FAILED
244 tests completed, 1 failed
```

Restored; both gates green.

## What this does not cover

Nothing here was run on a device. The Settings update path's user-visible behaviour — that a
failed update leaves the previous model working — is covered on the UI side by ARC-065's
`ModelDownloadTest`, and on this side only by the fact that `installPartial` still verifies
before it moves.
