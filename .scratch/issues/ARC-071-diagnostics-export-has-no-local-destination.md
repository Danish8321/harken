# ARC-071: Diagnostics "Export logs" can only leave the phone through a third party

- **Severity:** medium
- **Area:** `ui/SettingsViewModel.kt` (`exportLogs`), `telemetry/LogExport.kt`
- **Status:** open

## What is wrong

`SettingsViewModel.exportLogs()` zips `FileLogSink`'s files into
`filesDir/harken-logs.zip`, wraps it in a `FileProvider` URI and fires
`ACTION_SEND` through a chooser. `ACTION_SEND` is the only route out.

The zip lives in app-private internal storage, so nothing else can reach it:
`adb pull`, MTP and a file manager all see `/sdcard` and never
`/data/data/com.harken.android/files`. A release build is not debuggable, so
`run-as` is refused too. Whatever the chooser is pointed at *is* the export.

On the reference device the chooser resolves to Bluetooth, Drive, Gmail,
OneDrive, ChatGPT, WhatsApp and contact shortcuts. There is no file manager
installed, and `ACTION_SEND` is not what a "save to a folder" flow answers
anyway — `DocumentsUI` does not handle it. Measured, not assumed:

```
$ adb shell cmd package query-activities -a android.intent.action.SEND -t application/zip
com.android.bluetooth   com.anthropic.claude      com.google.android.apps.docs
com.google.android.gm   com.google.android.gms    com.microsoft.skydrive
com.openai.chatgpt      com.whatsapp              com.whatsapp.w4b
org.videolan.vlc
```

So reading a monitoring build's own diagnostics means sending them to a cloud or
messaging service. The Diagnostics card says *"Event and crash logs kept on the
phone"*, and ADR-0011 is the reason it says that. The one mechanism for getting at
them contradicts both.

Bluetooth is the sole local option, and it needs a paired receiver — not a route
for the person who just wants to hand over a log.

## Why it matters

This is what the feature is *for*. The logs exist so a build running unattended
for days can be examined afterwards (`fc1feec`), and the examination step is the
one that does not work without publishing the file. It stayed invisible because
nobody had tried to retrieve them until 2026-09-18.

`ExportService` already solves this correctly for recordings:
`ACTION_OPEN_DOCUMENT_TREE`, a folder the user picks, files written into it. The
logs never got the same treatment.

## Fix

`ACTION_CREATE_DOCUMENT` with `application/zip`, defaulting to a dated name, then
copy the zip into the returned URI. Keep the share action as well — mailing a log
to someone is a legitimate thing to want — but stop making it the only door.

## How the logs were actually retrieved on 2026-09-18

Recorded because the workaround is worth knowing and worth not needing.

No `keystore.properties` in the tree, so `assembleRelease` falls back to the debug
signing config, and the installed monitoring build's signer matched the debug
build's byte for byte (`d74b8574`). A rebuild with `isDebuggable = true` therefore
installed over it with `adb install -r` — an upgrade, data preserved, no uninstall
— which made `run-as` work long enough to read
`files/logs/current.log`. Reverted immediately: the build file restored, a
non-debuggable release rebuilt and reinstalled, `run-as` confirmed refused again.

A signature mismatch would have failed the install harmlessly rather than wiping
anything, which is what made it safe to attempt. It is still a workaround that
needs the signing key and a build, and it is not something a user can do.

## Related

`.scratch/issues/ARC-069-evaluate-small-en-whisper-model.md` (the monitoring run
this was needed for), `docs/adr/0011-on-device-transcription.md`.
