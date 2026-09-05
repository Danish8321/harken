# UI-035 — Nothing tells a below-minimum device why transcription dies

- **Severity:** medium
- **Status:** closed
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

## Resolution

Warn, never refuse, and say it in the two places the user actually asks.

`device/DeviceCapability.kt` reads `ActivityManager.MemoryInfo.totalMem` once
and answers one question: is this device *known* to be under the bar. It never
gates a transcription. 610 MB is a peak and not a floor, so a 4 GB phone with
nothing else running may well finish, and refusing would take away a
transcription that would have worked.

The bar is not a literal 6 GB. `totalMem` is RAM minus what the kernel reserved
and never reaches the nominal figure — the reference device is an 8 GB phone
reporting 7,444,948 kB, or 89% — so comparing against 6 GB would fail every 6 GB
phone on the market. `MinimumTotalMemBytes` is 4.5 GB, midway between what a
4 GB device reports (~3.6 GB) and what a 6 GB device reports (~5.3 GB), where
the gap is 1.7 GB wide and no real device lands.

Three surfaces, in the order the user meets them:

- **Launch telemetry.** `event=device_capability totalMemMb=… belowMinimum=…`,
  so "my transcriptions keep disappearing" is answerable from the log instead of
  by asking what phone they have. A magnitude, like every other field — it says
  nothing about what was recorded.
- **A permanent note under SPEECH MODEL in Settings**, under the model because
  the model is the thing that will not finish. Permanent rather than a one-time
  dialog: the device does not change, and a warning dismissed six weeks ago is
  not there when the transcription dies.
- **The interrupted-transcription message.** This is the ticket's actual
  complaint — "true and useless" — so on a below-minimum device
  `failInterruptedTranscriptions` is given a different string. "Tap to try
  again" on its own invites the user to lose the same twenty minutes twice.

A device that cannot be read reports zero and is treated as fine. Warning on a
failed system-service lookup would tell a 12 GB phone it is underpowered, which
is worse than the silence this replaces.

The warning names the bar and not the device's own figure. Turning a reported
total back into the number on the box needs a table of the sizes phones are
actually sold in; the first attempt rounded up to the nearest GB and called a
12 GB device an 11 GB one. The exact figure is in the telemetry, where it does
not have to be pretty.

## Evidence

`check.sh` (dotnet build, assembleDebug, assembleRelease) and `test-fast.sh`
(14 + 32 .NET, 92 Android JVM tests) both pass on the final tree.

`DeviceCapabilityTest` covers the two device classes at the reference device's
89% reporting rate, the reference device at its exact `MemTotal`, the bar
itself and one byte under it, and the unreadable device.

## Device verification

Nothing Phone 2 (AIN065), Android 16, 7,444,948 kB RAM (`totalMemMb=7270`).
Debug build, fresh install before each run (uninstall + install, no reused app
state).

The reference device is *above* the bar, so the below-minimum path was exercised
by a throwaway build with `MinimumTotalMemBytes` raised to 8 GB — the constant
only, reverted before the gates and the final run, and both gates were re-run
after reverting.

| build | telemetry | Settings note | message after a killed decode |
|---|---|---|---|
| bar at 8 GB (throwaway) | `belowMinimum=true` | shown | "Transcription stopped part-way. This phone has less memory than Harken needs, so it may not finish. Tap to try again." |
| shipping | `belowMinimum=false` | absent | "Transcription stopped when the app closed. Tap to try again." |

Both message rows are a real interruption, not a simulated one: 54 seconds of
the AMI meeting played into the room from the desktop, transcribed, and the
process force-stopped mid-decode (`transcribe_prepared spans=1
peakSpanSeconds=53` and no `transcribe_finished`), then relaunched.

Cold start on a fresh install 584 ms. Jank over five passes of
Settings/Library/Record with scrolling: 59 janky frames of 938 (6.29%), p50
7 ms, p90 16 ms, p95 29 ms — a debug build, and in line with the debug-vs-release
gap already recorded in `.scratch/perf-regression-2026-09-04.md`.

## Related

[UI-034](UI-034-recorder-silence-threshold.md) was the other open item from the
same measurement pass, and the same kind of change: a constant that needed a
small spec rather than a value.
[ADR-0014](../../docs/adr/0014-minimum-supported-device.md) sets the bar this
implements.

## Status: closed
