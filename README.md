# Harken

An Android voice recorder that transcribes on the phone. No account, no server, no
network call to anything but the one-time speech-model download. Audio, transcripts and
the database never leave the device ([ADR-0011](docs/adr/0011-on-device-transcription.md)).

See `CONTEXT.md` for the glossary, `docs/adr/` for the decisions behind the shape of this
thing, and `docs/plans/` for the slice history.

> **Record-then-transcribe, not live captions.** The live captioning path was deleted in
> [ADR-0007](docs/adr/0007-record-then-transcribe.md). Harken records to a WAV file, then
> transcribes it in the background — there is no word-by-word caption stream.
> Transcription is a JNI build of whisper.cpp running `ggml-base.en.bin` on the phone's
> own CPU.

## What is this

Point a phone at a meeting, a lecture or a thought, hit record, and read what was said
instead of re-listening to it.

- **Record** through a foreground service, so it keeps going with the screen locked. A
  live notification carries the elapsed time and a Stop button.
- **Transcribe** on the device. The first run downloads a ~140 MB model once; after that
  the app works in airplane mode.
- **Read** the transcript in a session sheet with a player, and title and tag the
  recording. Search the library by title, tag or transcript text.
- **Export** every recording and its transcript to a folder you choose — the only copy of
  this data is the one on the phone, so the export is the backup.

There is no sign-in, no cloud, and nothing to pay for. Not because those were cut for
later, but because [ADR-0009](docs/adr/0009-remove-auth-for-mvp1.md) removed the account
model and [ADR-0011](docs/adr/0011-on-device-transcription.md) removed the server that
would have needed one.

## Build it

Full detail, including troubleshooting, is in [`docs/setup.md`](docs/setup.md). The
short version:

- Android SDK (API 36) and JDK 17.
- `src/Harken.Android/local.properties` (gitignored) pointing at the SDK, e.g.
  `sdk.dir=C\:\Users\<you>\AppData\Local\Android\Sdk`.
- The NDK and CMake, for the vendored whisper.cpp. Android Studio installs both.
- A physical arm64 phone with **6 GB of RAM or more**
  ([ADR-0014](docs/adr/0014-minimum-supported-device.md)). The app builds `arm64-v8a`
  only, so the default x86 emulator image will not run it.

```
cd src/Harken.Android
./gradlew installDebug
```

Then launch it from the app drawer — `installDebug` installs but does not start.

## Use it

First launch runs a 2-step onboarding: what the app is, then the model download. The
download can be skipped and done later from **Settings → Speech model**; recording works
without it, and those recordings transcribe once the model arrives.

Three tabs: **Record**, **Library**, **Settings**.

| | Value | Why |
| --- | --- | --- |
| Format | 16 kHz / 16-bit / mono WAV | What whisper.cpp wants natively. No encoder dependency. |
| Storage | ~115 MB per hour | The cost of uncompressed WAV. Opus would be ~10 MB/hour; revisit when device storage actually hurts. |
| Silence timeout | 5 minutes | Below an adaptive noise floor for that long ends the recording. |
| Session cap | 3 hours | Hard bound on any one recording. |

Both limits end the recording **and save it**, so a forgotten session becomes a finished
one rather than running all day.

The microphone permission is requested the first time you tap Record, not at launch — a
prompt means something to someone who just tapped Record and nothing to someone who just
opened the app. Notification permission is asked for too but never blocks recording.

## Verification gates

- `.claude/scripts/check.sh` — `assembleDebug`, `assembleDebugAndroidTest`,
  `assembleRelease` and the full Lint pass. No device needed.
- `.claude/scripts/test-fast.sh` — the above plus `testDebugUnitTest`. No device needed.
- `.claude/scripts/test-full.sh` — the above plus `connectedDebugAndroidTest`. **Needs a
  phone attached**, and it uninstalls the previous build first so the run is against this
  build's state and not the last one's. A schema change must pass this gate:
  `SessionDatabaseMigrationTest` is what asserts a migration preserves the rows already on
  the device.

All three are Android-only. The .NET tier they used to build was deleted in
[ADR-0015](docs/adr/0015-retire-the-dotnet-tier.md).
