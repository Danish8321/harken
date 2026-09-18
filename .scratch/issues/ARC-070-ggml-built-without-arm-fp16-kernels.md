# ARC-070: ggml was compiled for baseline armv8-a, disabling its ARM fp16 kernels

- **Severity:** high
- **Area:** `src/main/cpp/CMakeLists.txt`, `src/main/cpp/harken_whisper_jni.cpp`
- **Status:** fixed in `2441ebd` — flag plus runtime guard. Two follow-ups stay open:
  the ARMv8.0 refusal path is unverified, and the repo's perf record was measured
  before this fix (see **Open decisions** below).

## What was wrong

`src/main/cpp/CMakeLists.txt` set the vendored kernels' compile options to:

```cmake
set(HARKEN_KERNEL_OPTIONS -O3)
```

No `-march`. The NDK's default for `arm64-v8a` is baseline `armv8-a`, so
`__ARM_FEATURE_FP16_VECTOR_ARITHMETIC` was never defined and ggml took the
`#else` branch throughout `ggml-cpu/simd-mappings.h`: every f16 value converted
to f32 one element at a time, then processed on `float32x4_t` — four lanes
instead of the eight `float16x8_t` gives, plus the conversion.

The app ships an **fp16 model**, so that is the hot loop of every matmul in the
decode. `-O3` was there (added after the `CMAKE_BUILD_TYPE=Debug`/`-O0` finding
recorded in the same file), which made this easy to miss: the kernels *were*
optimized, just compiled for the wrong ISA.

## Measured

Nothing Phone 2 (`eece2e35`, AIN065, Android 16), debug build, 120 s AMI ES2002a
fixture, `ggml-small.en.bin`. Same device, same fixture, same build otherwise —
only `HARKEN_KERNEL_OPTIONS` differs.

| | baseline `-O3` | `-O3 -march=armv8.2-a+fp16+dotprod` |
|---|---|---|
| decodeMs, 118 s span | 127,543 | **39,602** |
| `realtimeFactor` | 1.06 | **0.33** |
| `decodedRealtimeFactor` | 1.08 | 0.34 |
| Model load | 834 ms | 605–772 ms |
| Peak total PSS | 1,149,650 kB | 1,019,526 kB |
| Peak native heap | 1,023,809 kB | 894,092 kB |
| `MemAvailable` low-water | 1,821,128 kB | 1,744,832 kB |
| LMK kills during run | 20 | 12 |
| Segments | 33 | 33 |

**3.2x faster.** Transcript spot-checked in the UI: coherent AMI meeting text,
33 segments, 2 voices, no degradation from fp16 accumulation.

The memory column above was wrong, and it was wrong in the direction that would
have mattered. Re-measured 2026-09-18 with a ~170 ms on-device sampler reading
`/proc/<pid>/smaps_rollup` (580 samples, no adb round-trip per sample):

| | 3 s sampler | 170 ms sampler |
|---|---|---|
| Peak total PSS | 1,019,526 kB | **1,139,193 kB** |
| Peak RSS | — | 1,252,732 kB |

Replicated 2026-09-18 on a clean install with the model re-pushed over USB and
md5-verified against `.scratch/ggml-small.en.bin`:

| | run A | run B | spread |
|---|---|---|---|
| Peak PSS | 1,139,193 kB | 1,143,886 kB | 0.41% |
| `VmHWM` | 1,253,932 kB | 1,252,812 kB | 0.09% |
| decodeMs | 39,572 | 39,399 | 0.44% |
| `realtimeFactor` | 0.33 | 0.33 | — |

Cross-check the sampler against `VmHWM`, the kernel's own RSS high-water mark,
which needs no sampling and cannot be missed. They track to ~0.3% — but they are
*not* the same counter (`smaps_rollup` Rss and `status` VmRSS are accounted at
different points), and in run B the sampled figure came out 3.9 MB *above*
`VmHWM`. So treat `VmHWM` as the floor on the true peak and the sampler as
confirmation, not as two readings of one number.

So **the 3 s figure of 1,019,526 kB was a sampling artifact** — the decode had
become 3.2x shorter, giving a fixed-interval poller proportionally fewer chances
at the peak. The true post-fix peak is 1.14 GB against a pre-fix 1.15 GB: a 0.9%
difference, i.e. unchanged.

**ADR-0017's 8 GB bar is confirmed, not weakened.** Compile flags bought time, not
memory. Use `VmHWM` for any future peak claim in this repo; it cannot be missed
by a sampler.

## Why it matters beyond the speed

Every performance figure in this repo's history was measured on these
unoptimized kernels, including the `base.en` RTF 0.24 that ADR-0014 and
`perf-regression-2026-09-04.md` were built around. The conclusions drawn from
them (span-length tuning, `MaxSpanSeconds` at 300, model-size tradeoffs) were
all measured on a handicapped decoder.

It also makes ADR-0017's "transcription is now slower than real time"
consequence false as written. small.en at RTF 0.33 is comfortably faster than
real time — a 3-hour recording transcribes in ~1 hour, not ~3.2.

## The catch: this is an ISA requirement, not a free win

`-march=armv8.2-a+fp16+dotprod` makes the kernels *illegal instructions* on an
ARMv8.0 core. An affected device would `SIGILL` inside the first matmul — not a
degraded decode, a native crash with no app-level failure path.

ARMv8.2 FP16 arrived with Cortex-A55/A75 (2017), so in practice every device
above ADR-0017's 8 GB memory bar has it. But RAM does not imply an ISA, so the
fix adds a runtime guard in `harken_whisper_jni.cpp`:

- `HasRequiredCpuFeatures()` reads `getauxval(AT_HWCAP)` and requires
  `HWCAP_ASIMDHP` (vector fp16) and `HWCAP_ASIMDDP` (dot product).
- `nativeLoadModel` refuses and returns 0 rather than calling into whisper.
- That translation unit is deliberately left at the NDK's baseline `-march`
  (`HARKEN_KERNEL_OPTIONS` is applied to the `ggml` and `whisper` targets only),
  so the check itself is safe to run on a CPU that cannot execute what it guards.

Verified on hardware: `modelLoadMs=605`, zero refusals logged, and the guarded
build decodes the same fixture in 38,576 ms (`realtimeFactor=0.32`) — the guard
itself costs nothing measurable.

**Residual risk, unverified.** No ARMv8.0 arm64 device was available to test the
refusal path, so the guard is proven to allow a good CPU but not proven to catch
a bad one. And the guard only covers the path through `nativeLoadModel` — if any
ggml global constructor emits an ARMv8.2 instruction it would run at
`System.loadLibrary` time, before the guard. That is unlikely (ggml builds its
f16 tables inside `ggml_init`, not at static-init) but was not audited.

## Open decisions

1. **Ship the flag + guard as-is.** Simplest, 3.2x, refuses cleanly on old
   hardware. Costs: a below-ARMv8.2 device gets no transcription at all where it
   previously got a slow one, and the refusal currently surfaces only as a failed
   model load, not as a message that explains why.
2. **Build both variants and dlopen.** What ggml does upstream via
   `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, using the `cpu-feats.cpp` already
   vendored at `ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp`. No device loses
   support. Costs: two copies of the kernels in the APK and a real change to a
   hand-rolled static build.
3. **Re-baseline the perf record.** ADR-0014/0017 and
   `perf-regression-2026-09-04.md` all quote pre-fix numbers. At minimum ADR-0017's
   RTF consequence has to be corrected; the span-length findings deserve a
   re-measure before anything else is decided on them.

Nothing here is committed yet beyond the working tree.
