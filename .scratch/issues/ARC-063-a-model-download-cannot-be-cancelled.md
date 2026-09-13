# ARC-063 — A model download runs to completion after the user has left the screen

- **Severity:** medium
- **Area:** `speech/ModelDownloadManager.kt`
- **Status:** fixed

## Problem

`downloadProgress()` ran the whole 148 MB transfer inside `withContext(Dispatchers.IO)` in a
`callbackFlow`, and nothing in that arrangement could stop it:

```kotlin
fun downloadProgress(replaceExisting: Boolean = false): Flow<Int> =
    callbackFlow {
        ...
        withContext(Dispatchers.IO) {
            runCatchingDownload {
                downloadLock.withLock {
                    ...
                    downloadTo(partialFile) { percent -> trySend(percent) }
                    installPartial()
                }
            }...
        }
        close()
        awaitClose { }   // unreachable: close() above already ended the flow
    }
```

Three things stacked:

- `trySend` does not suspend, so the progress callback — the one place the transfer touched
  the coroutine machinery on every chunk — was never a cancellation point.
- `streamTo`'s read loop checked nothing:
  `while (input.read(buffer).also { read = it } != -1) { output.write(...) }`. A blocking
  `InputStream.read` is not a suspension point, so cooperative cancellation has nothing to
  cooperate with.
- The OkHttp `Call` was never cancelled, so even a check between chunks could not end a read
  already parked on a socket that had stopped delivering.

`awaitClose {}` sat after an unconditional `close()` and never ran, which is the visible
symptom of the same mistake: the flow had no teardown path at all.

## Reachability

Both callers collect in `viewModelScope` — `OnboardingScreen.downloadModel()` and
`SettingsViewModel.updateModel()` — so leaving the screen *did* cancel the coroutine. The
transfer carried on regardless:

- **Onboarding.** "Skip for now" is offered precisely so a user on a metered connection can
  decline the download. Taking it cancelled the collector and left 148 MB still coming down
  the user's connection, with no UI anywhere in the app admitting to it.
- **Settings.** Leaving mid-update did the same.
- **The launch sweep is blocked too.** `discardPartialDownload()` returns 0 immediately while
  `downloadInFlight` is true, so an abandoned-but-still-running transfer also stopped
  `MainActivity` from reclaiming the partial at the next launch.
- **Telemetry.** Whatever finally ended such a transfer was reported as
  `model_download_finished outcome=failed`, so a user walking away looked in the numbers like
  a broken server.

## The fix

`callbackFlow` becomes `flow { … }.flowOn(Dispatchers.IO)`. `emit` suspends, so the progress
path is a cancellation point by construction and the channel, `trySend`, `close()` and the
dead `awaitClose` all go. `downloadTo`/`streamTo` become `suspend`, with `onProgress` a
`suspend (Int) -> Unit`.

That alone does not cover `ensureModel()`, which emits no progress, so the read loop checks
for itself between chunks:

```kotlin
while (input.read(buffer).also { read = it } != -1) {
    currentCoroutineContext().ensureActive()
    output.write(buffer, 0, read)
    ...
}
```

And for a read that never returns, the call is cancelled with the coroutine:

```kotlin
val cancelsCall = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
```

OkHttp then raises a plain `IOException` out of the read, which would be classified as a
dropped connection and shown as a failed download; `ensureActive()` in the catch turns it back
into the cancellation it is. `downloadTo` reports `outcome=cancelled` rather than `failed`.

Nothing is thrown away on cancel. The `use` blocks flush and close the `FileOutputStream`, so
the `.tmp` keeps what it had and the next attempt resumes it with the existing `Range` header;
`installPartial()` is never reached, so no hash and no move; `downloadInFlight` is cleared in
the existing `finally`, so the launch sweep sees the file again.

No new UI, no new strings: this is the plumbing that makes backing out of a screen mean what
it looks like it means.

## Evidence

Four tests added to `ModelDownloadManagerTest`, on the existing fake-`OkHttpClient`
interceptor (no new dependency) with a body that delivers 8 KB at a time with a pause between
chunks — every pre-existing test served 4 KB instantly, which is why this survived:

- cancelling the collector stops the transfer instead of finishing it;
- a cancelled download is not treated as a failed one, and installs nothing;
- cancelling `ensureModel` stops the transfer with no progress emission to catch it — the test
  that isolates the read-loop check, since the progress path is stopped by `emit` alone;
- the attempt after a cancel resumes from the bytes already on disk, asserting the second
  request carries `Range: bytes=<kept>-` and that the resumed download completes to the full
  length.

All four fail against the pre-fix file and pass against the fixed one, verified by restoring
the original and re-running. Removing only the `ensureActive()` line from the read loop fails
the `ensureModel` test and no other, confirming that line is load-bearing rather than
decorative.

`.claude/scripts/check.sh` (ktlintCheck, compileDebugKotlin/compileReleaseKotlin, lint) — OK.
`.claude/scripts/test-fast.sh` (testDebugUnitTest) — OK.

Confirmed on an emulator configured to Nothing Phone 2 geometry (1080x2412, density 394),
against the real 147,964,211-byte release asset, via Settings → Update:

```
event=model_download_started
event=model_download_stream resumedFromBytes=0 httpCode=200 expectedTotalBytes=147964211
   ← Back pressed here
event=model_download_finished outcome=cancelled bytes=96107838 elapsedMs=21255
```

The partial stopped at 96,107,838 bytes — 65% — and stayed there across six samples over
twelve seconds, having been growing at roughly 5.8 MB/s until the Back press. The installed
model was untouched. Tapping Update again resumed rather than restarting:

```
event=model_download_stream resumedFromBytes=96107838 httpCode=206 expectedTotalBytes=147964211
event=model_download_finished outcome=succeeded bytes=147964211 elapsedMs=10823
event=model_verified bytes=147964211 elapsedMs=101
```

51.8 MB fetched instead of 148 MB, and the `.tmp` was gone afterwards.

The `call.cancel()` belt is the one part no unit test covers: an application interceptor
serving a synthetic body has no socket for `cancel()` to close, so the stalled-read case
cannot be reproduced with the fake client. It is reasoned, not tested.

Found in review slice 09 as S8/SP7.
