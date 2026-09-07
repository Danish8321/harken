# Environment setup

The go-to doc for getting a machine ready to build Harken. The README covers *using* the
app once this is done; this covers what the build depends on and how to prove each piece
works before blaming the code.

**Harken needs no cloud account of any kind, and no server.** Transcription runs on the
phone ([ADR-0011](adr/0011-on-device-transcription.md)) against a model the app downloads
once. Storage is a Room database on the device
([ADR-0005](adr/0005-sqlite-for-family-scope.md) chose SQLite; the phone's copy is that
decision's only surviving instance). The .NET tier that used to sit behind all of this was
deleted in [ADR-0015](adr/0015-retire-the-dotnet-tier.md).

So there are three required pieces — the **Android SDK**, a **JDK**, and the **NDK and
CMake** — plus one physical **phone**.

---

## 1. JDK 17 and the Android SDK

Android Studio bundles a JDK and installs the SDK, and is the path of least resistance.
CI uses Temurin 17; anything newer that AGP 8.12 accepts will also work locally, but 17 is
what the gates are proven against.

The build needs **API 36** (`compileSdk` and `targetSdk`). `minSdk` is 26, so the app runs
on Android 8.0 and up, but it is compiled against 36.

Point the build at your SDK by creating `src/Harken.Android/local.properties` — it is
gitignored, and it is per-machine:

```
sdk.dir=C\:\Users\<you>\AppData\Local\Android\Sdk
```

Verify:

```
cd src/Harken.Android && ./gradlew --version
```

## 2. The NDK and CMake

`src/Harken.Android/app/src/main/cpp` holds a vendored copy of whisper.cpp, built by
CMake through Gradle's `externalNativeBuild`. Without the NDK the build fails at
configuration time, before any Kotlin is compiled.

Android Studio → SDK Manager → **SDK Tools** tab → tick **NDK (Side by side)** and
**CMake**.

The native build is `arm64-v8a` only. That is deliberate: it halves native build time,
and every device the app supports is arm64 anyway. The consequence is that the **stock
x86_64 emulator image cannot install this app** — you need a physical phone, or an arm64
system image.

The first `assembleDebug` compiles whisper.cpp and takes several minutes. After that
Gradle caches it, and it only rebuilds when the vendored C++ changes.

## 3. A phone

- Physical, arm64, **6 GB of RAM or more**
  ([ADR-0014](adr/0014-minimum-supported-device.md) — a transcription peaks at ~610 MB
  PSS held for the whole decode, and a smaller phone gets it killed part-way).
- Developer options and USB debugging on
  ([`onboarding.md`](onboarding.md) §3 has the tap-Build-number dance).
- `adb` on `PATH`. `test-full.sh` exits rather than pretending it ran.

## 4. The speech model

Not a setup step on the workstation — the **app** downloads it, once, on first launch or
from Settings → Speech model. `ggml-base.en.bin`, ~140 MB, from this repository's own
GitHub release (`models-v1`). `ModelDownloadManager` pins its SHA-256, so a truncated or
swapped file is rejected rather than fed to the decoder.

This download is the only network call the application makes. Everything after it works
in airplane mode.

## 5. Disk space

- Workstation: the Android SDK, the NDK and a Gradle cache — budget ~15 GB, most of it
  the SDK.
- Phone: ~140 MB for the model, once, plus **~115 MB per recorded hour** as 16 kHz mono
  WAV. Nothing is uploaded and nothing is deleted automatically, so device storage grows
  with every hour ever recorded. A 3-hour session is ~345 MB.

Audio is kept after transcription on purpose: it cannot be recreated, and re-transcribing
with a better model needs it. Settings → **Export everything** is how that data gets off
the phone, and it is the only backup there is — the app opts out of Android's cloud backup
deliberately, because an encrypted-at-rest transcript in a Google backup is exactly the
thing ADR-0011 says does not happen.

## 6. Prove the environment works

In order, so a failure points at one thing:

1. **Build** — `bash .claude/scripts/check.sh`. Touches no device and no network beyond
   the Gradle dependency fetch. A failure here is the SDK, the NDK or the code.
2. **Unit tests** — `bash .claude/scripts/test-fast.sh`. Same, plus the JVM tests.
3. **Install** — `cd src/Harken.Android && ./gradlew installDebug`, then launch it from
   the app drawer.
4. **On-device tests** — `bash .claude/scripts/test-full.sh` with the phone attached.
   This is the gate a schema change must pass.
5. **End to end by hand** — [`onboarding.md`](onboarding.md) §6, from a fresh install.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Gradle fails at configuration with a CMake or NDK error | NDK and CMake not installed — SDK Manager → SDK Tools |
| `SDK location not found` | no `local.properties`, or `sdk.dir` points somewhere that is not an SDK |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | an x86 emulator. The build is `arm64-v8a` only — use a phone or an arm64 system image |
| `checkDebugAarMetadata` rejects an androidx artifact by name | that release needs `compileSdk` 37. Raising it means AGP 9 first — one job, not a version bump |
| `test-full.sh` says no device attached | `adb devices` lists it as `unauthorized` (accept the prompt on the phone) or `offline` (replug), not as `device` |
| Transcription never starts | no model. Settings → Speech model → Download. A failed download names why; it does not fail silently |
| Transcription is killed part-way | not enough RAM (ADR-0014). Settings warns about this on a phone below the threshold |
| Transcript is nonsense or repeats a phrase | Whisper hallucinating on silence or noise — the recording is probably near-silent |
| Recording stops on its own | by design — 5 minutes below the adaptive noise floor, or the 3-hour session cap. Both save what was captured |
| No Stop button on the notification | notification permission denied (Android 13+). Recording still works; grant it in Settings → Apps → Harken → Notifications |
| A behaviour reproduces for you and not on a fresh install | you are testing carried-over app state. Uninstall, reinstall, try again |

## Cost

Nothing. There is no metered service in the product. `cost-model.md` is kept as the record
of the arithmetic that retired the cloud transcription option.
