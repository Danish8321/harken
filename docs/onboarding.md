# Onboarding — zero to a verified phone recording

A condensed, ordered checklist for someone new to this repo. Each step links to the full
detail in [`setup.md`](setup.md) — read this first, go there when a step needs more than
one command.

There is no backend. Every step below happens on a workstation and a phone; nothing
listens on a port and nothing is uploaded. That was decided in
[ADR-0011](adr/0011-on-device-transcription.md) and the last of the server was deleted in
[ADR-0015](adr/0015-retire-the-dotnet-tier.md).

## 1. Prerequisites
- [ ] JDK 17 and the Android SDK (API 36). Android Studio installs both.
- [ ] The NDK and CMake, for the vendored whisper.cpp under
      `src/Harken.Android/app/src/main/cpp`. Android Studio's SDK Manager, *SDK Tools*
      tab, has both.
- [ ] `sdk.dir` in `src/Harken.Android/local.properties` (gitignored) pointing at your
      Android SDK.
- [ ] `adb` on `PATH` — `test-full.sh` refuses to run without it.
- [ ] A physical arm64 phone with 6 GB of RAM or more
      ([ADR-0014](adr/0014-minimum-supported-device.md)). The build is `arm64-v8a` only,
      so a stock x86 emulator image cannot install it.

See [`setup.md`](setup.md) §1–§2 for install commands and troubleshooting.

## 2. Prove the code builds and tests pass
```
bash .claude/scripts/check.sh
bash .claude/scripts/test-fast.sh
```
Both green before touching a device — a failure here is the SDK or the code, not anything
below. The first run compiles whisper.cpp and takes several minutes; later runs are
cached. `test-full.sh` adds the on-device tests and is §4.

## 3. Install it on the phone
- [ ] Phone: Settings → About phone → tap Build number 7× → Developer options unlocked.
- [ ] Developer options → USB debugging → on.
- [ ] Connect via USB-C. Accept the "Allow USB debugging?" prompt on the phone.
- [ ] USB mode set to File Transfer/PTP, not charging-only.
- [ ] `adb devices -l` shows the phone as `device`, not `unauthorized`.
- [ ] `cd src/Harken.Android && ./gradlew installDebug`, then launch Harken from the app
      drawer — `installDebug` installs but does not start it. Or open
      `src/Harken.Android` in Android Studio and hit Run for the same result plus a
      debugger and Logcat.

**Uninstall any previous build first when you are verifying behaviour by hand.** An app
that carries over a database, a downloaded model and a set of permissions from the last
install is not the app a new user gets, and first-run bugs hide there.

## 4. Run the on-device tests
```
bash .claude/scripts/test-full.sh
```
Everything `test-fast.sh` runs, plus `connectedDebugAndroidTest`. It uninstalls the debug
build first, so the run is against this build's state and not the last one's. **A schema
change must pass this gate**: `SessionDatabaseMigrationTest` is what asserts a migration
preserves the rows already on the device, and it runs against the schema Room exported to
`app/schemas`, not against a `CREATE TABLE` typed out by hand.

## 5. First launch — the onboarding wizard
Two steps, shown once:

1. **Meet Harken** — what the app is.
2. **Get the speech model** — a one-time ~140 MB download of `ggml-base.en.bin`. This is
   the only network call the app ever makes. **Skip for now** is a real option: recording
   works without it, and anything recorded meanwhile transcribes once the model lands.
   Settings → Speech model has the same download, plus an Update.

Then **Start recording** lands on the Record tab. Re-run onboarding by clearing app data.

## 6. Verify the vertical slice end to end
On a fresh install, with the model downloaded:

- [ ] Tap Record (grant the mic permission prompt). The waveform moves.
- [ ] Lock the screen mid-recording. Recording continues.
- [ ] Unlock, pull down the notification, tap **Stop**.
- [ ] The recording appears in Library, transcribes in the background behind a progress
      notification, and the card's state chip reaches "Transcribed".
- [ ] Open it: the sheet plays the audio and shows the transcript, with the playback
      cursor tracking the segment being spoken.
- [ ] Give it a title and a tag. Go to Library, search for that title, that tag, and a
      word from the transcript. All three find it.
- [ ] Settings → **Export everything**, pick a folder, and confirm the WAV and the
      transcript are both in it.
- [ ] Kill the app mid-transcription (swipe it from Recents). Re-open: the recording is
      still there and the transcription is re-queued rather than stuck.

If any step fails, check [`setup.md`](setup.md)'s Troubleshooting table before assuming
the code is wrong.
