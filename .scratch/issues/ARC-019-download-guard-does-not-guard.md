# ARC-019 — The concurrent-download guard does not guard the download

- **Severity:** medium
- **Status:** closed
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

## Resolution

A process-wide `Mutex` now wraps the transfer itself, in both `ensureModel` and
`downloadProgress`, with the presence check re-done inside it so a caller that
waited uses the download it waited for.

Not the per-instance lock the ticket proposed: that would have made this fix wait
on ARC-014, and it would guard nothing while Onboarding and Settings each build
their own manager. The companion `Mutex` is correct with or without a
composition root, and it sits next to `downloadInFlight` — which keeps its one
real job, telling the launch-time cleanup not to delete a file a download is
still writing.

## Evidence

`check.sh` OK, `test-fast.sh` OK. New test: two managers calling `ensureModel`
concurrently against a client that delays its response make exactly **one**
request, and both get the model. Before the lock they both opened
`FileOutputStream(.tmp, append = true)` and interleaved into the same file.

## Device verification

Covered by the ARC-005 device run: the download completed once and verified
(`model_verified bytes=147964211`), which is also the check that would now catch
an interleaved file if one were ever produced.
