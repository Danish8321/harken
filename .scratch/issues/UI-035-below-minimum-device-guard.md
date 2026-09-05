# UI-035 — Nothing tells a below-minimum device why transcription dies

- **Severity:** medium
- **Status:** open
- **Area:** `speech/TranscriptionCoordinator.kt`, `ui/SettingsScreen.kt`,
  new `DeviceCapability`

## Problem

[ADR-0014](../../docs/adr/0014-minimum-supported-device.md) sets 6 GB of RAM as
the minimum supported device: a transcription peaks at ~610 MB of PSS held for
the whole decode, and on a 4 GB device that is low-memory-killer range.

The Play Console catalog can exclude those devices from the listing. Nothing
stops a sideloaded install, and on one the app looks like it works — it records,
it downloads the model, it starts a transcription — and then the process is
killed mid-decode. `RecordingRecovery` and `failInterruptedTranscriptions` will
tidy up at the next launch and report *"Transcription stopped when the app
closed. Tap to try again."*, which is true and useless: the retry is killed the
same way.

The app already reads its own memory ceiling in telemetry and knows the number.
It has never asked the device for its.

## Why it was deferred

The decision (6 GB) is settled; the surface is not. Whether this warns or
refuses, and where it appears, is a product question with three plausible
answers and no obvious default — see below. Guessing at UI in an ADR's
consequences section is how a number in a document becomes a dialog nobody
agreed to.

## Suggested shape (not yet designed)

- `DeviceCapability` reading `ActivityManager.MemoryInfo.totalMem` once, with the
  6 GB bar as a named constant traceable to ADR-0014.
- Emit it as telemetry at launch (a magnitude, no user content), so a report of
  "my transcriptions keep failing" is answerable from the log.
- Then one of: a persistent note under SPEECH MODEL in Settings; a one-time
  warning before the first transcription; or refusing to transcribe at all.
  Refusal is the honest option for a device that cannot do the job, and the
  worst one for a device that can *sometimes* — 610 MB is a peak, not a floor,
  and a 4 GB phone with nothing else running may well finish.

## Related

[UI-034](UI-034-recorder-silence-threshold.md) is the other open item from the
same measurement pass, and the same kind of change: a constant that needs a
small spec rather than a value.
