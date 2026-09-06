# ARC-033 — Nothing can leave the app except a copied transcript

- **Severity:** medium
- **Status:** open
- **Area:** `ui/SessionSheet.kt`

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
