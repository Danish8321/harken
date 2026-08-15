# Plan — Slice 02: Mobile (Android)

**Goal:** Same pipeline as slice one (record → live captions → stop → summarize),
in a .NET MAUI Android app, mic capture surviving screen-lock via a foreground
service. Reuses `Harken.Core` contracts and the backend from slice one unchanged.

**Not in scope:** iOS, session-history screen, cloud deployment, auth.

> **Superseded in part by slice 03.** This slice's "no authentication, no ownership"
> state is no longer how Harken works. Per ADR-0004, the mobile app now opens on a
> login page, stores its token in `SecureStorage`, and sends it with the hub connection
> and every HTTP call; sessions belong to the account that recorded them. See
> `docs/plans/slice-03-identity-and-ownership.md` and the README for the current setup.
> The task history below is left as recorded.
>
> **Superseded in part by ADR-0007.** Live captions over a SignalR hub connection are
> gone from `CapturePage` — deleted in [slice 04](slice-04-record-then-transcribe.md)
> Task 1. The Capture tab currently lists and summarizes sessions only; it cannot create
> one. On-device recording returns in slice 05, recording to a local file and uploading
> it rather than streaming. The foreground service and mic-capture code from this slice
> (`RecordingForegroundService`, `AndroidAudioCapture`) are kept unreferenced today and
> get wired back in by slice 05 rather than rebuilt.

**Decisions locked:** ADR-0003 (foreground service). Android-only. LAN + editable
base-URL setting. See `CONTEXT.md`, ADR-0001..0003.

---

## Task 1 — MAUI project scaffold
- **Files:** `src/Harken.Mobile/Harken.Mobile.csproj`, `MauiProgram.cs`,
  `App.xaml(.cs)`, `AppShell.xaml(.cs)`, `Platforms/Android/*` (default template),
  `Harken.sln` (add project), `Directory.Packages.props` (add any new package
  versions the template needs, CPM-managed).
- **Change:** `dotnet new maui -o src/Harken.Mobile` targeting `net10.0-android`
  only (strip other TFMs from the multi-targeted csproj — Android-only per Q3).
  Reference `Harken.Core`. Fold TargetFramework/Nullable overrides needed for MAUI
  into the csproj itself (MAUI projects commonly need per-project TFM even under a
  shared Directory.Build.props — confirm and document the override in the csproj if
  Directory.Build.props' `net10.0` conflicts with `net10.0-android`).
- **Verify:** `dotnet build src/Harken.Mobile -f net10.0-android` succeeds.

## Task 2 — Android manifest, permissions, foreground service skeleton
- **Files:** `Platforms/Android/AndroidManifest.xml`,
  `Platforms/Android/RecordingForegroundService.cs`, notification channel setup.
- **Change:** Add `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE` permissions. Empty foreground service class
  (`Service` subclass) with a persistent "Recording" notification, started/stopped
  via a platform-service interface (`IRecordingService`) exposed to shared code via
  MAUI's dependency-injection pattern (`Platforms/Android` implementation registered
  in `MauiProgram.cs` via `#if ANDROID` or a partial-service pattern).
- **Verify:** `dotnet build -f net10.0-android`; app installs and requesting
  `RECORD_AUDIO` triggers Android's permission prompt (manual check on device/emulator).

## Task 3 — Mic capture (Android AudioRecord) feeding a PCM stream
- **Files:** `Platforms/Android/AndroidAudioCapture.cs`, shared
  `Services/IAudioCapture.cs` in Harken.Mobile.
- **Change:** Wrap `Android.Media.AudioRecord`, 16kHz/16-bit/mono, matching the
  backend's expected format exactly (same as console/NAudio). Expose chunks via an
  `IAsyncEnumerable<byte[]>` or a `Channel<byte[]>`, started/stopped from the
  foreground service lifecycle (Task 2).
- **Verify:** manual — capture a few seconds, log chunk sizes/count to Debug output,
  confirm non-zero data flowing while service is foregrounded and screen locks.

## Task 4 — Settings: backend base URL
- **Files:** `Pages/SettingsPage.xaml(.cs)`, `Services/AppSettings.cs`.
- **Change:** One text field for base URL (e.g. `http://192.168.x.x:5057`), persisted
  via MAUI `Preferences`. Validate it's a well-formed absolute URL before saving.
- **Verify:** enter a URL, restart app, confirm it persists (manual) — or a small
  unit test around the validation function if one exists (no device needed for that
  slice of logic).

## Task 5 — Capture page: SignalR stream + live captions
- **Files:** `Pages/CapturePage.xaml(.cs)`.
- **Change:** Reuse `Harken.Core.Contracts` (`CaptionUpdate`, `ICaptionClient`,
  `SessionSummary`). `HubConnection` to `{baseUrl}/hub/captions`, same
  `SessionStarted`/`ReceiveCaption` handlers as the console (partial rewrites current
  line/label, final appends). Start button: request permission if not granted, start
  foreground service + audio capture, invoke `StreamAudio` with the capture channel's
  reader. Stop button: stop capture/service, await stream completion.
- **Verify:** manual end-to-end on device/emulator against the running backend on the
  same LAN: start, speak, see live captions update, stop.

## Task 6 — Summarize button
- **Files:** `Pages/CapturePage.xaml(.cs)` (extend), `Services/ApiClient.cs`.
- **Change:** After stop, if a session id was received, enable "Summarize" button ->
  `POST {baseUrl}/sessions/{id}/summary`, display the returned summary in a text view.
- **Verify:** manual — same session as Task 5's run, tap Summarize, see result (needs
  Ollama running on the backend host).

## Task 7 — README + config docs
- **Files:** `README.md` (extend).
- **Change:** Add Android/MAUI prerequisites (`dotnet workload install maui-android`,
  device/emulator setup), how to set the base-URL setting, note the persistent
  recording notification is intentional (ADR-0003).
- **Verify:** fresh-eyes read-through catches any missing step.

---

## Exit criteria
All 7 tasks build (where applicable) and pass their verify step. One recorded
end-to-end run on a real Android device or emulator: grant mic permission, start
recording, lock screen, speak, unlock, see captions caught up, stop, summarize.

## Note on verification limits
Tasks 2, 3, 5, 6 need an Android emulator or physical device to truly verify —
`check.sh`/`test-fast.sh` can confirm compilation but not on-device behavior. Flag
each such task's manual step explicitly in its review; don't let "it builds" stand in
for "it works" on this slice (per the verification contract).

---

## Status

- [x] **T1** — MAUI Android scaffold. Proof: `dotnet build -f net10.0-android` 0/0,
      `check.sh` green (whole sln resolves the Android TFM fine).
- [x] **T2** — manifest/permissions/foreground-service skeleton. Proof: build green;
      reviewed manifest (4 permissions, service registered) and
      `RecordingForegroundService` (channel creation, `StartForeground`,
      `StopForeground` with correct API-level branching). On-device unverified.
- [x] **T3** — AudioRecord mic capture. Proof: build green; reviewed
      `AndroidAudioCapture` — correct 16kHz/16-bit/mono constants, `GetMinBufferSize`
      used not hardcoded, chunks trimmed to actual bytes read, idempotent
      start/stop, `IDisposable` for the native handle. On-device unverified.
- [x] **T4** — settings (base URL). Proof: build green; `TryValidate` confirmed pure
      (no MAUI/Android types), `IPreferences` injectable for testability. On-device
      persistence unverified.
- [x] **T5** — capture page (SignalR + captions). Proof: build green; reviewed
      `CapturePage.xaml.cs` — mirrors console's connection pattern, chunk-handler
      subscribed before `StartRecording()` (no dropped audio), disposal unsubscribes
      defensively. Live streaming unverified.
- [x] **T6** — summarize button. Proof: build green; reviewed — gated on `SessionId`,
      disabled in-flight, error via `DisplayAlert`, re-enabled in `finally`. Live call
      unverified.
- [x] **T7** — README. Proof: prerequisites/configure/run/use sections added,
      persistent-notification behavior explained (ADR-0003).

## Still open before this slice is genuinely "done"

**Nothing in this slice has run on a real device yet.** Every task above is
build-verified and code-reviewed only. You have Visual Studio + a physical phone —
the actual proof is: enable USB debugging, deploy, grant permissions, set the base
URL to your PC's LAN IP, and run the record → caption → stop → summarize flow for
real, screen-locked mid-recording per ADR-0003's whole reason for existing.
