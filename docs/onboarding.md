# Onboarding — zero to a verified phone recording

A condensed, ordered checklist for someone new to this repo. Each step links to the full
detail in [`setup.md`](setup.md) — read this first, go there when a step needs more than
one command.

## 1. Prerequisites
- [ ] .NET 10 SDK installed (`dotnet --version` matches `global.json`'s `10.0.302`+).
- [ ] A Whisper model file downloaded, path configured.
- [ ] Ollama installed, `ollama pull gemma3:4b` done.
- [ ] For the phone client (`src/Harken.Android`, native Kotlin + Jetpack Compose):
      Android SDK + JDK 17. `sdk.dir` in `src/Harken.Android/local.properties`
      (gitignored) points at your Android SDK.

See [`setup.md`](setup.md) §1–3 for install commands and troubleshooting.

## 2. Prove the code builds and tests pass
```
bash .claude/scripts/check.sh
bash .claude/scripts/test-fast.sh
```
Both green before touching a device — a failure here is the SDK or the code, not
anything below.

## 3. Run the backend
```
dotnet run --project src/Harken.Api --urls http://0.0.0.0:5057
```
Binding `0.0.0.0`, not `localhost`, is required if a phone will reach it over LAN.
Verify: `curl http://localhost:5057/health` → `{"status":"ok"}`.

## 4. Prove transcription and summary work (console client)
Run the console client, record a short clip, let it transcribe, then summarize.
Text back at both steps means Whisper, the model file, Ollama, and Gemma are all wired.
Do this **before** the phone — it isolates pipeline problems from mobile problems.

## 5. Deploy to a phone
- [ ] Phone: Settings → About phone → tap Build number 7× → Developer options unlocked.
- [ ] Developer options → USB debugging → on.
- [ ] Connect via USB-C. Accept the "Allow USB debugging?" prompt on the phone.
- [ ] USB mode set to File Transfer/PTP, not charging-only.
- [ ] `adb devices -l` shows the phone as `device` (not `unauthorized`).
- [ ] `cd src/Harken.Android && ./gradlew installDebug` — builds and installs the
      debug APK; launch it from the phone's app drawer (Gradle's `installDebug` does
      not auto-launch). Or open `src/Harken.Android` in Android Studio and hit Run for
      the same result plus a debugger and Logcat. First launch runs the 3-step
      onboarding described in §6.
  - Unit tests only (no device needed): `./gradlew testDebugUnitTest`. Instrumented
    tests (foreground service, notification Stop button, permission flow — written
    but excluded from `test-fast.sh`) need a connected device or emulator:
    `./gradlew connectedDebugAndroidTest`.

## 5b. No LAN reachability between PC and phone?

If the phone can't reach the PC over Wi-Fi (router AP/client isolation is a common
cause on shared or ISP-default routers, and shows up as the browser test on the phone
to `http://<PC LAN IP>:5057/health` also failing, not just the app) and you have no
router admin access to disable isolation, use a USB reverse tunnel instead of the LAN
IP:

```
adb reverse tcp:5057 tcp:5057
adb reverse --list
```

Confirms `UsbFfs tcp:5057 tcp:5057`. Then in step 6 below, enter `http://localhost:5057`
as the backend URL instead of the LAN IP — traffic goes phone → USB → PC, bypassing
Wi-Fi and the router entirely. The tunnel does not survive USB disconnect, `adb
kill-server`, or a phone reboot; re-run the command above each session. It also does
not prove the LAN/Wi-Fi path works, only that app-to-backend logic is correct.

## 6. First launch — in-app onboarding wizard
The app shows a 3-step wizard on first launch (`OnboardingPage`), skipped on every launch
after:
1. **Backend URL** — enter `http://<PC's LAN IP>:5057` and tap **Test connection**
   (find the LAN IP with `ipconfig`, the `IPv4 Address` under your Wi-Fi adapter — not
   `localhost`, the phone is a different device). A green "Connected." confirms the URL is
   saved to Settings.
2. **Microphone** — explains why Harken needs it. No permission prompt here; Android asks
   the first time you actually tap Record, per this slice's design.
3. **How recording works** — record/lock/stop-from-notification/upload/transcript flow,
   and the two auto-stop limits.

Tap **Get started** to land on the Capture page. Re-run onboarding any time by clearing
app data, or by resetting `OnboardingComplete` in Settings' backing preferences.

## 7. Verify the vertical slice end to end
- [ ] Tap Record on the Capture page (grant the mic permission prompt).
- [ ] Lock the screen mid-recording.
- [ ] Unlock, pull down the notification, tap Stop.
- [ ] Recording finalizes, uploads, polls, and shows a transcript.
- [ ] Tap Summarize, confirm a summary comes back. Needs Ollama installed and running
      (`docs/setup.md` §3) — a `502` here almost always means Ollama isn't installed or
      the model isn't pulled, not an app bug.
- [ ] Stop the backend, record again, confirm the app keeps the file and shows its path
      rather than losing it.
- [ ] Tap Recordings, tap Refresh, confirm the list re-fetches. Tap
      Delete on a row — it disappears from the list; confirm via `sqlite3 harken.db
      "select deleted from Sessions where id='<id>'"` that the row and its WAV file
      still exist on disk. Tap Delete permanently on another row, confirm the dialog,
      confirm the row is gone from the database and the WAV file removed from disk.

If any step fails, check [`setup.md`](setup.md)'s Troubleshooting table before assuming
the code is wrong — most first-run failures are `localhost` vs. LAN IP, firewall, or a
missing Ollama model.
