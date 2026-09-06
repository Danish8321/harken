# ARC-005 — The downloaded model is never verified, only counted

- **Severity:** high
- **Status:** closed
- **Area:** `speech/ModelDownloadManager.kt`

## Problem

`ModelDownloadManager` checks one thing after the transfer: that
`destination.length()` equals the `expectedTotal` it derived from
`Content-Length`. That is a truncation check, not an integrity check.

The manager resumes interrupted downloads with a `Range` header appended to an
existing `.tmp`. A `.tmp` written against one release asset and resumed against
a different one — the tag re-pointed, a CDN node serving a stale object, any
proxy in between — produces a file of exactly the right length whose middle is
from another build. `nativeLoadModel` is then handed 148 MB of arbitrary bytes,
in C++, in-process.

`StalePartialAgeMs = 24h` narrows the window; it does not close it, and it does
nothing about a corrupted transfer that completes inside a day.

## Fix

Ship the expected SHA-256 of `ggml-base.en.bin` as a constant, hash the file
after `Files.move` (streamed, so it costs one read and no extra memory), and
delete + report `ModelDownloadFailure` if it does not match. This also makes
resume safe to keep.

## Resolution

The expected SHA-256 of the release asset ships as a constant, and the completed
`.tmp` is hashed before it is moved into place:

```kotlin
private fun installPartial() {
    verifyPartial()
    ...
}
```

Verified before the move rather than after, so the installed path never briefly
holds the wrong file — an update that fetches a bad object leaves the working
model where it was. A file that fails is deleted rather than kept as a resume
point, since resuming would append to bytes already known to be wrong.

`ModelIntegrityException` is its own type so the user is told the download did
not arrive intact, rather than that the server is down; it is the one failure
that is neither a network fault nor resumable.

## Evidence

`check.sh` OK, `test-fast.sh` OK. Three new cases in `ModelDownloadManagerTest`,
served through an OkHttp interceptor rather than a socket: a response of the
right length and the wrong bytes fails with `ModelIntegrityException` and leaves
neither the model nor the partial on disk; a response whose hash matches is
installed; and the failure classifies as `Corrupt`.

## Device verification

Nothing Phone 2, fresh install, model fetched over the network from the real
release URL:

```
event=model_download_finished outcome=succeeded bytes=147964211 elapsedMs=21412
event=model_verified bytes=147964211 elapsedMs=126
```

So the constant matches what the release actually serves, and the hash costs
126 ms against a 21-second download. The model then loaded and decoded
(`modelLoadMs=142`, `segments=8`).
