# Native SIGSEGV in ggml_vec_dot_f16 during on-device transcription

Status: open, root cause unknown, **deprioritised**. Opened 2026-08-28.

Scope note (2026-08-29, [ADR-0015](../docs/adr/0015-target-device-floor.md)): the SM-E625F
is *below* the supported device floor (Snapdragon 888-class and newer). This crash has
only ever been seen on out-of-scope hardware, so it does not block release. It is not
closed either — it is an unexplained memory fault in vendored native code that runs on
every device. Closing it requires a root cause, or a clean reproduction attempt on an
in-scope device.

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

## Not yet tried

- Hypothesis 2: worker pthread stack overflow (ggml spawns raw pthreads via
  `ggml-threading.cpp`, not coroutines — default stack size may be too small for this tensor
  shape/model).
- Hypothesis 3: memory pressure / partial mmap unmap of the model file on a low-RAM device
  under load.
- Re-attempt repro with the *exact* original conditions: ~5s clip, immediate Stop-then-crash
  timing (~1s), rather than the longer/different clips used in the contrast tests above — the
  original bug may be timing- or length-sensitive in a way not yet reproduced.
- Stress/loop the repro many times (diagnosing-bugs Phase 1 "non-deterministic bugs" guidance)
  since a clean run now doesn't rule out a low-rate flake.

## Current mitigation (shipped, does not fix root cause)

`6300f16` — transcription is now explicit (user taps Transcribe in Library, not auto-triggered
on Stop) and `TranscriptionCoordinator` enforces at most one on-device transcription running
app-wide. Reduces exposure (no concurrent/untriggered native inference) but the underlying
SIGSEGV cause in `ggml_vec_dot_f16` is still unknown and unfixed.

## Secondary gap noted alongside this bug

Two Room sessions can get stuck in "Transcribing" status permanently if a crash happens
mid-inference — no crash recovery marks them Failed. Not yet actioned.
