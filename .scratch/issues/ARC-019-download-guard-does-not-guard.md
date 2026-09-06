# ARC-019 — The concurrent-download guard does not guard the download

- **Severity:** medium
- **Status:** open
- **Area:** `speech/ModelDownloadManager.kt`

## Problem

`downloadInFlight` is a companion `AtomicBoolean` — process-wide, so it does
survive the fact that Onboarding and Settings each build their own
`ModelDownloadManager` (ARC-014). But it is only consulted by
`discardPartialDownload`. `ensureModel` never checks it.

Two managers therefore open
`FileOutputStream(destination, /* append = */ true)` on the same `.tmp` and
interleave their writes. The result is a file of roughly the right size — often
of *exactly* the right size, since both are appending toward the same
`Content-Length` — made of two interleaved halves. Nothing detects it
(ARC-005).

The name says the flag guards a download. It guards a cleanup.

## Fix

Make `ensureModel` itself the critical section (a `Mutex` on the single
manager instance once ARC-014 lands), so a second caller joins the download in
progress instead of starting a second one.
