# ADR-0014: 6 GB of RAM is the minimum supported device

## Status
Accepted

## Context
[ADR-0011](0011-on-device-transcription.md) moved transcription onto the phone, which
means whisper.cpp's working set is now the app's working set. Measured on a Nothing
Phone 2 (`MemTotal: 7444948 kB`), a transcription peaks at **~610 MB of total PSS**,
~480 MB of it native heap, held for the entire decode — 597 seconds for a 21-minute
meeting, and proportionally longer for a longer one.

That peak is not a tuning failure. It was measured across a 15x range of span lengths
(`.scratch/perf-regression-2026-09-04.md`, Finding 4 and the 2026-09-05 sections):

- Peak PSS rises steeply to a ~150-second span and is flat after it. `MaxSpanSeconds`
  at 300 costs ~15 MB over 150; the saving only becomes real below 60 seconds, which
  severs speaker context every minute.
- A smaller model does not fix it. `base.en-q5_1` removes a flat ~90 MB, which is the
  weights; `tiny.en` at a 284-second span peaks within 5 MB of `q5_1`, because by then
  whisper's compute buffers dominate and those scale with the span, not the model.

So the ceiling is a property of decoding real speech on this device class, and the
question is which devices are in the class.

## Decision
**6 GB of RAM is the minimum supported device.** Below it, Harken is not supported.

On the reference device 610 MB is about a fifth of `MemAvailable` (~2.7 GB of 7.4 GB
total). On a 4 GB device the same 610 MB is a large fraction of what is actually free
after the system and the launcher, held by a foreground service for minutes at a
stretch — squarely in low-memory-killer range. The failure is not a slow transcription
but a killed process, and the user loses the decode every time they switch apps.

Shipping `q5_1` instead of `base.en` buys 50 MB, which does not change that answer.
Supporting 4 GB would need a smaller model **and** a shorter `MaxSpanSeconds` — a
second supported configuration with its own accuracy and seam behaviour, not a constant
to lower.

## Alternatives considered
- **Support 4 GB with `q5_1` and a shorter span.** Rejected for now: two configurations
  to test, and the quality evidence on `q5_1` is one real recording, on which it
  hallucinated two sentences into the opening span that `base.en` did not produce.
  Revisit if a second real recording clears it.
- **Support everything and let the OS decide.** Rejected: the user experiences it as
  "the app loses my meetings", with no signal that the device is the reason.
- **Bundle a device allowlist.** Rejected: RAM is the variable that matters and it is
  readable at runtime; a model list is stale the month it ships.

## Consequences
- **Play Console device catalog:** exclude devices under 6 GB of RAM. This is store
  configuration, not repository state, and has to be set on the listing.
- **A sideloaded install below the bar is warned, never refused.**
  `device/DeviceCapability` reads `ActivityManager.MemoryInfo.totalMem` at launch and
  says so in three places: a `device_capability` telemetry event, a permanent note
  under SPEECH MODEL in Settings, and the message a killed transcription leaves on the
  Library card. It does not gate anything — 610 MB is a peak, not a floor, and a 4 GB
  phone with nothing else running may finish. The comparison is against 4.5 GB rather
  than 6: `totalMem` excludes kernel-reserved memory, so this 8 GB device reports
  7,444,948 kB and a 6 GB device would report ~5.3 GB. See UI-035.
- **`MaxSpanSeconds` stays at 300** and is now a supported-device decision rather than
  an open tuning question.
- Peak memory becomes a number worth regressing: a change that raises it materially
  raises the minimum device with it.

## Related
[ADR-0011](0011-on-device-transcription.md),
[ADR-0007](0007-record-then-transcribe.md),
measurements in `.scratch/perf-regression-2026-09-04.md`.
