# ARC-002 — `allowBackup=true` copies every recording and transcript off the phone

- **Severity:** critical
- **Status:** open
- **Area:** `app/src/main/AndroidManifest.xml`

## Problem

The manifest sets `android:allowBackup="true"` and declares neither
`android:dataExtractionRules` nor `android:fullBackupContent`. With no rules,
Android backs up the *whole* of `filesDir` and `databases/` — which is every
WAV the user has ever recorded and the entire Room database of transcripts —
to Google Drive under Auto Backup, and hands the same set to device-to-device
transfer.

[ADR-0011](../../docs/adr/0011-on-device-transcription.md) is built on the
claim that everything recorded stays on the phone. The onboarding copy and the
recording notification both say so. That claim is false as long as this
attribute is unqualified: transcripts of the user's meetings leave the device
without the user ever being asked.

This is a privacy defect, not a configuration preference.

## Fix

Either `android:allowBackup="false"`, or backup rules that exclude `files/` and
`databases/` explicitly (`dataExtractionRules` for API 31+ *and*
`fullBackupContent` for 26–30 — they are separate files and both are needed at
this minSdk).
