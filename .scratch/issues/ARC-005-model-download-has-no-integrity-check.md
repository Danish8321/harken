# ARC-005 — The downloaded model is never verified, only counted

- **Severity:** high
- **Status:** open
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
