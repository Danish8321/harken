# ADR-0017: 8 GB of RAM is the minimum supported device

## Status
Accepted. Supersedes [ADR-0014](0014-minimum-supported-device.md).

## Context
[ADR-0014](0014-minimum-supported-device.md) set a 6 GB bar from a measured ~610 MB peak
PSS, and closed with the rule that made this ADR necessary: *"a change that raises it
materially raises the minimum device with it."*

ARC-069 swapped `ggml-base.en.bin` for `ggml-small.en.bin` (commit `3edcb7c`). Measured on
the same reference device ADR-0014 used — Nothing Phone 2, AIN065, Android 16,
`MemTotal: 7444948 kB` — on the same 120-second AMI ES2002a fixture:

| | small.en | base.en (2026-09-04) |
|---|---|---|
| Peak total PSS | **1.15 GB** | 594–610 MB |
| Peak native heap | 1.00 GB | 451–482 MB |
| `realtimeFactor` | **1.06–1.22** | 0.24–0.27 |
| Model load | 834 ms | 157–280 ms |
| PSS after finish | 141 MB | ~195 MB |

> **Correction, 2026-09-18.** Both `realtimeFactor` columns above were measured with ggml
> compiled for baseline `armv8-a`, which silently disabled its ARM fp16 kernels (ARC-070).
> With the correct `-march`, small.en decodes this fixture at 0.33.
>
> The **memory** figures survive, and have since been re-measured properly rather than
> assumed: a ~170 ms sampler puts the post-fix peak at 1,139,193 and 1,143,886 kB of PSS
> across two clean-install runs, against the 1,149,650 kB recorded below — under 1% apart,
> i.e. unchanged — corroborated by the kernel's own `VmHWM` high-water mark at 1,253,932
> and 1,252,812 kB. The faster kernels bought time, not memory.
> **The 8 GB bar is confirmed, on a replicated measurement rather than a sampling
> artifact.**

The peak nearly doubled, and the reference device — which is *above* ADR-0014's bar — did
not absorb it quietly. During one decode, sampling `/proc/meminfo` every 3 seconds:

- `MemAvailable` fell from 2,645,280 kB to 1,821,128 kB — 824 MB given up.
- `SwapFree` fell 222 MB: the system was swapping, not just dropping cache.
- Logcat recorded 20 `lowmemorykiller: Kill` lines (LinkedIn, Play background, two Nothing
  system apps) plus a critical-pressure event the killer chose to ignore.

## Decision
**8 GB of RAM is the minimum supported device.** Below it, Harken is not supported.

ADR-0014's own arithmetic decides this. It put 4 GB below the bar because 610 MB was "a
large fraction of what is actually free after the system and the launcher, held by a
foreground service for minutes at a stretch — squarely in low-memory-killer range." Apply
that unchanged to the measurement above:

- On this 8 GB device, 1.15 GB is 43% of `MemAvailable` at rest (2.59 GB), and paying it
  cost 20 background processes and 222 MB of swap.
- A 6 GB device reports ~5.3 GB of `totalMem` and has proportionally ~1.9 GB available.
  1.15 GB is **60%** of that — a worse ratio than the one ADR-0014 refused to ship to a
  4 GB phone, and with less headroom than the device that was already killing things.

So the same rule that put 4 GB below the bar at 610 MB puts 6 GB below it at 1.15 GB. The
bar moves with the model, exactly as ADR-0014 said it would.

**The reference device is the bar, not a comfortable example of clearing it.** Every
measurement here was taken on the minimum supported device, and at the minimum a decode
still costs 20 background processes and 222 MB of swap. It finishes, correctly, every
time — but there is no headroom left over, and a reader should not read "8 GB" as "8 GB is
fine." A supported device is one where transcription completes, not one where nothing else
notices.

`DeviceCapability.MINIMUM_TOTAL_MEM_BYTES` moves 4.5 GB → **6.2 GB**, still compared
against reported `totalMem` rather than the nominal figure: the reference 8 GB device
reports 7.10 GiB (89%) and a 6 GB device reports ~5.34 GiB, so 6.2 GB sits in a 1.8 GB gap
where no real device lands.

## Consequences
- **Play Console device catalog:** exclude devices under 8 GB of RAM. Store configuration,
  not repository state — it has to be set on the listing, and it narrows the addressable
  market substantially.
- **Existing 6 GB installs are warned, not broken.** The warning is unchanged machinery
  (`device_capability` telemetry, the SPEECH MODEL note in Settings, the message a killed
  decode leaves on the Library card); only the number it names moves. Users on a 6 GB
  phone who were unwarned yesterday are warned today, which is the honest outcome: their
  next transcription really is more likely to be killed than their last one was.
- ~~**Transcription is now slower than real time.**~~ **Withdrawn 2026-09-18 — this was a
  build defect, not a property of the model.** The 1.06–1.22 figures above were measured
  with ggml compiled for baseline `armv8-a`, which disabled its ARM fp16 kernels on an fp16
  model. Supplying `-march=armv8.2-a+fp16+dotprod` decodes the same fixture at
  `realtimeFactor` **0.32–0.33**, comfortably faster than real time: a 3-hour recording
  takes ~1 hour. See ARC-070. Every RTF number in this ADR's context table is a pre-fix
  measurement and should be read as a floor on what the hardware can do, not a ceiling.
- **The 1.15 GB peak is the new regression number**, and ADR-0014's rule still applies to
  it: raise it materially and the minimum device rises again. There is no tier above 8 GB
  worth shipping to — 12 GB is flagship-only — so this is the last time the bar can absorb
  a peak increase. The next such change has to reduce the peak instead.
- **The reference device is now the floor, which makes it the right thing to test on.**
  Measurements taken here are worst-supported-case, not typical-case: anything that fails
  on `eece2e35` fails for every supported user, and anything that merely gets tight here
  has no margin anywhere.
- **`MaxSpanSeconds` stays at 300.** Span length was measured flat past ~150 seconds
  (ADR-0014, Finding 4) and nothing in this swap changes that; the growth is weights and
  compute buffers, not span.

## Alternatives considered
- **Keep the 6 GB bar and accept the kills.** Rejected: the user experiences a killed
  decode as "the app loses my meetings", and shipping a known-to-fail configuration while
  claiming support is the failure ADR-0014 was written to prevent.
- **Ship `small.en-q5_1` (~182 MB) to hold the 6 GB bar.** Not rejected — untested. It
  plausibly returns memory to roughly base.en levels, and it is the live option in ARC-069
  if the 8 GB bar proves too narrow. It is not a reason to delay this ADR: the model that
  ships today is fp16 small.en, and the bar has to describe what ships.
- **Revert to base.en.** Rejected by the user on 2026-09-17 ("Let's go for the best"),
  accuracy being the point of the swap.

## Related
[ADR-0014](0014-minimum-supported-device.md) (superseded),
[ADR-0011](0011-on-device-transcription.md),
`.scratch/issues/ARC-069-evaluate-small-en-whisper-model.md`,
measurements in `.scratch/perf-regression-2026-09-04.md`.
