# Native SIGSEGV in ggml_vec_dot_f16 during on-device transcription

Status: closed by decision, 2026-09-14. Root cause was never found — closed as
out-of-scope hardware, not fixed. See "Closing decision" below.

## Symptom

Tombstone on Samsung SM-E625F (Exynos 850, arm64-v8a, Android 13, TP1A.220624.014):

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000000012c080
Cmdline: com.harken.android
pid: 25289, tid: 26558, name: DefaultDispatch (kotlinx.coroutines Dispatchers.Default)
backtrace:
  #00 pc 00000000002bf514 libharken_whisper_jni.so (ggml_vec_dot_f16+312)
  #01 pc 0000000000293648 libharken_whisper_jni.so (no symbol - inlined ggml frame)
  #02 pc 000000000029210c libharken_whisper_jni.so (no symbol)
  #03 pc 0000000000291384 libharken_whisper_jni.so (no symbol)
  #04 pc 0000000000290620 libharken_whisper_jni.so (no symbol)
  #05 pc 0000000000290d68 libharken_whisper_jni.so (no symbol)
  #06 pc 00000000000c28b0 libc.so (__pthread_start)
  #07 pc 0000000000054e20 libc.so (__start_thread)
```

Original repro: ~5s recording, tapped Stop, crash ~1s later during native inference.
Model: ggml-base.en.bin (whisper base.en, non-quantized).

## Investigation so far (diagnosing-bugs, Phases 1-4)

- Ruled out: heterogeneous-core / big.LITTLE fp16 SIMD mismatch.
  - Compile flags in `CMakeLists.txt` don't define `__ARM_FEATURE_FP16_VECTOR_ARITHMETIC`
    (no `-march=armv8.2-a+fp16`), so ggml falls back to the safe `__ARM_NEON` fp32-conversion
    path (`simd-mappings.h`), not native fp16 NEON ops.
  - No runtime hwcap dispatch exists in vendored `ggml-cpu.c` (grepped for
    `hwcap|GGML_CPU_ALL_VARIANTS|getauxval|HWCAP_ASIMDHP` — no matches). Kernel selection is
    compile-time only.
  - SM-E625F/Exynos 850 is a homogeneous octa-core Cortex-A55 SoC anyway — no big.LITTLE
    split to mismatch.
- Tested and NOT confirmed: thread-split race hypothesis (`wparams.n_threads` in
  `harken_whisper_jni.cpp`, currently hardcoded to 4).
  - Forced `n_threads=1` on the actual crash device (SM-E625F, serial RZ8R20CRB9T), fresh
    install: full clean transcription, zero SIGSEGV/FATAL EXCEPTION across ~14 logcat polls,
    stable pid throughout.
  - Reverted to `n_threads=4` (historical value) on the same device for contrast, same short
    recording duration: also completed clean, zero crashes, stable pid, ended in "Transcribed"
    state. Diagnostic edit has been reverted; `harken_whisper_jni.cpp` is back to its committed
    state (`n_threads=4`, no diagnostic comment).
  - Conclusion: crash did not reproduce at either thread count on the crash device with the
    current build. Hypothesis 1 is weakened/dead as the sole cause. Possible confound: repo has
    had other changes land since the original crash report (see commit history on
    `feat/on-device-transcription`), so this may already be partially masked rather than fixed.

## Also tested, not confirmed: automated stress loop on a different device

180 automated iterations (AIN065 "Pong", Snapdragon `taro`, arm64-v8a, Android 16 —
not the original SM-E625F/Exynos 850) of the tight original-timing repro (launch, tap
Record, 5s, tap Stop, watch logcat 1.5s for `SIGSEGV|ggml_vec_dot_f16|Fatal signal|
tombstone`): zero crashes. Different chipset from the crash report, so this narrows
"does it reproduce readily on arm64 in general" without bearing on the original
device specifically — consistent with a device/SoC-specific cause, not proof of
one. Script: throwaway, not committed (scratchpad-only harness, per
diagnosing-bugs discipline — kept locally if the investigation continues).

## Not tried, and not being pursued further (see closing decision)

- Hypothesis 2: worker pthread stack overflow (ggml spawns raw pthreads via
  `ggml-threading.cpp`, not coroutines — default stack size may be too small for this tensor
  shape/model).
- Hypothesis 3: memory pressure / partial mmap unmap of the model file on a low-RAM device
  under load.

## Closing decision (2026-09-14)

The crash device (SM-E625F, Exynos 850) is a budget-tier chip below the hardware class
the app now targets ("mid+ processor phones" — product decision, this conversation).
Closed as out-of-scope rather than fixed: the native cause in `ggml_vec_dot_f16` was
never identified, so if a mid-tier device ever shows the same tombstone this doc's
Phase 1-4 work (thread-count test, the two ruled-out hypotheses, and the automated
repro loop below) is the starting point, not a dead end.

Not verified as part of this decision: SM-E625F's actual RAM. ADR-0014 already sets a
6 GB floor by RAM, independent of CPU tier — if this device is also under 6 GB, it was
arguably already unsupported under that ADR and this closure doesn't add a new rule,
just confirms an existing one from the CPU side.

180 automated iterations on a different, mid/high-tier arm64 phone (Snapdragon `taro`)
showed zero repro at the original tight timing — see below — consistent with, not
proof of, this being specific to the low-tier device being dropped.

## Current mitigation (shipped, does not fix root cause)

`6300f16` — transcription is now explicit (user taps Transcribe in Library, not auto-triggered
on Stop) and `TranscriptionCoordinator` enforces at most one on-device transcription running
app-wide. Reduces exposure (no concurrent/untriggered native inference) but the underlying
SIGSEGV cause in `ggml_vec_dot_f16` is still unknown and unfixed.

## Secondary gap noted alongside this bug

Two Room sessions can get stuck in "Transcribing" status permanently if a crash happens
mid-inference — no crash recovery marks them Failed. Fixed in `2a07c85` (see
`slice-09-followups.md` item 5): `Running` rows are settled as `Failed` at launch, and
`Failed` rows now offer Transcribe again.
