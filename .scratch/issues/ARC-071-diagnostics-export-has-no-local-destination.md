# ARC-071: Diagnostics "Export logs" can only leave the phone through a third party

- **Severity:** medium
- **Area:** `ui/SettingsViewModel.kt` (`exportLogs`), `telemetry/LogExport.kt`
- **Status:** fixed — `ACTION_CREATE_DOCUMENT` "Save logs" added beside the share
  action, gates green, round-trip driven by hand on the emulator and repeated on
  the reference phone (2026-09-19).

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

## Fixed 2026-09-18

`SettingsViewModel.saveLogs(Uri)` writes the same zip `exportLogs()` builds into a
URI from `ActivityResultContracts.CreateDocument("application/zip")`, launched
from a new "Save" button that sits ahead of the share button in the Diagnostics
card. `suggestedLogFileName()` seeds the picker with `harken-logs-<yyyy-MM-dd>.zip`
— two monitoring runs saved into the same downloads folder are otherwise
indistinguishable. The old `"harken-logs.zip"` literal is now `LOG_ZIP_NAME`,
shared by both paths so they cannot drift apart.

The share action stays, relabelled "Share logs" so the pair reads as two
destinations rather than one action and a mystery.

**Gates:** `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK (21 instrumented
tests, 0 failures, on AVD `Android12` — the result XML was read rather than the
build's green trusted, since every task reported `UP-TO-DATE`).

**Driven by hand on the emulator**, because no gate covers a picker round-trip.
Settings → Diagnostics shows "Save logs" beside "Share logs"; the tap opens
`com.google.android.documentsui/…picker.PickActivity` with
`harken-logs-2026-09-18.zip` already in the name field; SAVE writes to
`/sdcard/Download/`. The file read 0 bytes immediately after the tap and 668
bytes a moment later — the copy is on `Dispatchers.IO`, so a check that races it
sees an empty file the picker has created but nothing has filled yet. Pulled over
`adb` and opened: a valid zip, `testzip()` clean, one entry `current.log`, 1,454
bytes of real `device_capability` / `model_download_*` / `transcribe_*` lines.

That is the exact thing this ticket says is impossible — the logs out of
app-private storage and onto a PC without a cloud service in the path.

### Repeated on the reference phone, 2026-09-19

Nothing Phone 2 (`eece2e35`, AIN065), debug build at `a41310f`. Same flow, same
result: the picker opens pre-filled — `harken-logs-2026-09-19.zip`, the date
having rolled over since the emulator run, which is the dated name doing its job
— and SAVE writes a valid zip. Pulled over `adb`: `testzip()` clean, one entry
`current.log`, carrying a `device_capability` line stamped `gitSha=a41310f`.

The roots drawer on this phone offers *Nothing Phone (2)*, *Downloads* and
*Drive*. Saved successfully into two of them, including a plain local folder —
which is the whole point of this ticket, on the device whose share sheet started
it.

Two false alarms along the way, both mine rather than the app's: a tap at the
centre of SAVE's reported bounds landed on the gesture-nav strip and went home,
and a `find /sdcard -name A -o -name B -maxdepth 3` silently missed the file it
was looking for, because `-maxdepth` after `-o` does not apply the way it reads.
The save it "proved" had failed had in fact succeeded.

Still not covered: a cancelled picker, and a write that fails.

**Known weakness, deliberate:** a failed write is logged and never shown. The user
sees the picker close and nothing happen. That matches `exportLogs()`, and both
deserve a visible failure — not fixed here.

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
