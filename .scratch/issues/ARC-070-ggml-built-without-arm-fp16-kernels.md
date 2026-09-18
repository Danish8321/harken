# ARC-070: ggml was compiled for baseline armv8-a, disabling its ARM fp16 kernels

- **Severity:** high
- **Area:** `src/main/cpp/CMakeLists.txt`, `src/main/cpp/harken_whisper_jni.cpp`
- **Status:** fixed in `2441ebd` — flag plus runtime guard. The guard's refusal path
  and its user-facing message were the two follow-ups; both are now closed (see
  **Guard follow-ups, closed** below), as is the static-init question the guard's
  placement depended on. What stays open is the multi-variant `dlopen` decision,
  which is now waiting on `device_unsupported` telemetry rather than on argument.

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

**Static-init audit, 2026-09-18: the guard's placement holds.** The worry was that
`nativeLoadModel` is too late — a ggml global constructor emitting an ARMv8.2
instruction would run at `System.loadLibrary`, before the guard can speak, and no
test on ARMv8.2 hardware can reach that. Audited on the built library instead:

`.init_array` is 32 bytes — four entries — in both the Debug and RelWithDebInfo
`arm64-v8a` builds:

| Initializer | What it is |
|---|---|
| `init_have_lse_atomics` | compiler-rt; baseline by construction |
| `__init_cpu_features` | compiler-rt's own feature detection; likewise |
| `_GLOBAL__sub_I_ggml_threading.cpp` | six instructions, registers one `__cxa_atexit` destructor |
| `_GLOBAL__sub_I_whisper.cpp` | builds the `asr_tensor` name maps; `memcpy` and libc++ `std::map` |

Then the reachability question, since "the constructor itself is clean" is not the
same as "nothing it calls is". Disassembling the whole library and walking direct
calls from those four roots: **129 functions reachable, 109 functions in the
library contain ARMv8.2-only instructions, and the intersection is empty.** Every
one of the 109 is a compute kernel — `ggml_vec_dot_*`, `ggml_compute_forward_*` —
reached from `ggml_graph_compute`, which only runs long after the guard.

Method, reproducible: `llvm-objdump -d` the `.so`, flag `udot`/`sdot`, any
`f`-mnemonic on a `.8h`/`.4h` arrangement, and any `f`-mnemonic on an `h`
register; parse `bl` targets into a call graph; BFS from the `.init_array`
entries resolved via `llvm-readelf -r`.

**What this does not cover.** Direct calls only. Twelve reachable functions contain
indirect branches (`blr`/`br`) that a static walk cannot follow — all of them
libunwind, `std::terminate` and `operator new`, none of them ggml, all NDK
prebuilts compiled at baseline. And it is an audit of today's vendored whisper.cpp
at `a8d002c`: a future bump has to be re-run, not assumed.

## Guard follow-ups, closed

**The refusal path is tested, in both directions.** It could not be, as written:
`HasRequiredCpuFeatures()` read the CPU it was running on, so every device the
suite has ever had took the accepting branch and the refusal was dead code from
the tests' point of view. An inverted comparison or the wrong HWCAP constant
would have passed here and SIGILL'd on a user's phone.

Split into a pure `CpuFeaturesSatisfied(unsigned long hwcap)` plus a thin caller
that supplies `getauxval(AT_HWCAP)`. `CpuFeatureGuardTest` (instrumented — the
predicate is native) drives it with synthetic values: neither bit, each bit
alone, both, and an everything-except mask that catches a truthiness test. Five
cases, all passing on `AIN065`.

Still not proven, and cannot be without the hardware: that a real ARMv8.0 device
reaches the guard at all. That is the static-init risk above, not the predicate.

**A refusal no longer reads as a retryable failure.** `nativeLoadModel` returned
`0` both for "this CPU can never run the kernels" and for "the model file would
not load", and the second is what the user saw: *"Harken couldn't transcribe this
recording. Tap to try again."* — an invitation to retry a permanent hardware
fact, forever.

Now it returns `kUnsupportedCpu` (`-1`), which `OnDeviceTranscriber` turns into
`UnsupportedDeviceException`, which the coordinator maps to its own message
ahead of both retry-shaped branches: *"This phone's processor is too old to run
Harken's speech model, so transcription isn't available on it. Your recordings
are still saved."*

It also emits `device_unsupported reason=cpu_features`. The `LOGE` it had before
went to logcat only, so it never reached `FileLogSink` and would have been
invisible in a monitoring log. That event is the evidence the multi-variant
`dlopen` decision below is waiting on.

## Open decisions

1. ~~**Ship the flag + guard as-is.**~~ **Taken.** 3.2x, refuses cleanly, says why,
   and reports it. A below-ARMv8.2 device gets no transcription where it
   previously got a slow one — accepted, and now measurable rather than assumed.
2. **Build both variants and dlopen.** What ggml does upstream via
   `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, using the `cpu-feats.cpp` already
   vendored at `ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp`. No device loses
   support. Costs: two copies of the kernels in the APK and a real change to a
   hand-rolled static build.
3. **Re-baseline the perf record.** ADR-0014/0017 and
   `perf-regression-2026-09-04.md` all quote pre-fix numbers. At minimum ADR-0017's
   RTF consequence has to be corrected; the span-length findings deserve a
   re-measure before anything else is decided on them.

   ADR-0017 is corrected. `perf-regression-2026-09-04.md` is not: lines 24, 135,
   137, 163-167, 177, 227, 230 and 238 still quote pre-fix RTF, and line 238
   states *"`0.21–0.25` is now a shipping number, not a debug number"* — false as
   written, and the most misleading sentence of the set. Its **memory** findings
   are unaffected; only timings were measured against the wrong kernels.

4. ~~**Audit ggml's static-init path.**~~ **Done 2026-09-18** — nothing ARMv8.2
   runs before the guard, on either build variant. See the static-init audit
   above. Re-run it on the next whisper.cpp bump.
