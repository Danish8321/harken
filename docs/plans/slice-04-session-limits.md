# Plan — Slice 04: Session limits, cost control, and the recording notification

**Goal:** Bound what a Session can cost. Server-enforced Silence Timeout and Session Cap,
a hub contract that keeps the client in sync, and a recording notification that shows
elapsed time and can actually stop the recording.

**Driver:** ADR-0006, and the consequence added to ADR-0003 — a foreground service removes
the only natural stop signal, so nothing currently bounds a Session's duration or cost.

**Not in scope:** multi-language recognition, per-user monthly quotas, billing dashboards,
moving to S0 (an Azure portal action, not code), reconnect/resume after a dropped client.

---

## Task 1 — Explicit recognition language
- **Files:** `src/Harken.Api/Speech/AzureSpeechTranscriber.cs`, `appsettings.json`.
- **Change:** Set `SpeechConfig.SpeechRecognitionLanguage` explicitly from config, default
  `en-US`. Today it is unset, so the SDK default applies and English is inherited rather
  than chosen. Same class of gap as SQLite: nobody decided it.
- **Verify:** `check.sh`; a unit test asserting the configured value reaches the
  `SpeechConfig`. No live Azure call.

## Task 2 — Session limits in the domain
- **Files:** `src/Harken.Core/Session.cs`, `src/Harken.Core/SessionEndReason.cs` (new),
  `HarkenDbContext`, new EF migration.
- **Change:** `Session.EndReason` (`UserStopped` / `SilenceTimeout` / `SessionCap`,
  nullable while running) and `Session.CapDuration` (nullable — `none` is a valid choice).
  **Read the generated migration before applying**; both columns are additive and
  nullable, so no existing row needs a value.
- **Verify:** migration quoted in the task review, zero destructive operations;
  `dotnet ef database update` succeeds; `check.sh`.

## Task 3 — Hub contract for limits and sync
- **Files:** `src/Harken.Core/Contracts/ICaptionClient.cs`,
  `src/Harken.Core/Contracts/SessionLimits.cs` (new), `CaptionHub.StreamAudio`.
- **Change:** `StreamAudio` takes the caller's chosen Session Cap alongside `AudioSource`.
  Server→client additions: limits echoed at start as **absolute UTC deadlines**, an
  updated silence deadline on each Final Result, an `AutoStopWarning`, and
  `SessionEnded(reason)`. Validate the incoming cap against an allowed set — it arrives
  from a client, so an arbitrary or negative value must be rejected like the `AudioSource`
  enum already is.
- **Verify:** `check.sh`; integration test asserting the echoed deadlines match the limits
  actually applied, and that an out-of-range cap is rejected with no Session persisted.

## Task 4 — Server-side enforcement
- **Files:** `CaptionHub.StreamAudio`, new timer/enforcement helper in `src/Harken.Api/`.
- **Change:** The server tracks time since the last Final Result and absolute session
  start, ends the Session when either limit trips, records the `EndReason`, sends the
  warning 30s before a silence stop, and disposes the transcriber. Must compose with the
  existing pending-save drain: the tail segments still have to land before disposal.
- **Verify:** integration tests with a fake transcriber and compressed limits —
  (a) silence with no Final Results ends the Session with `SilenceTimeout`; (b) a
  Session producing Final Results past its cap ends with `SessionCap`; (c) a normal stop
  still records `UserStopped`; (d) segments recognized just before an auto-stop are still
  persisted.

## Task 5 — Client sync: console
- **Files:** `src/Harken.Console/Program.cs`.
- **Change:** Prompt for a Session Cap before starting, render the countdown, print the
  warning, and report the end reason distinctly from a connection failure.
- **Verify:** `check.sh`; manual run noted as pending — the console has never been run
  against live Azure.

## Task 6 — Recording notification: elapsed time and Stop
- **Files:** `src/Harken.Mobile/Platforms/Android/RecordingForegroundService.cs`,
  `AndroidRecordingService.cs`, `Pages/CapturePage.xaml.cs`.
- **Change:** Notification gains a **Stop action** and **live elapsed time**, updated on a
  timer while recording. While the screen is locked this is the only surface the user can
  see or act on. Also surfaces the auto-stop warning.
- **Verify:** `check.sh`; device-verified only — flag explicitly as not build-provable.

## Task 7 — `StartCommandResult.Sticky` → `NotSticky`
- **Files:** `src/Harken.Mobile/Platforms/Android/RecordingForegroundService.cs`.
- **Change:** Sticky asks Android to restart the service after a low-memory kill, with a
  null intent — `OnStartCommand` would then call `StartCapture()` again with no hub
  connection and no Session. That is the microphone reopening on its own, streaming
  nowhere. Recording is user-initiated, so a system kill should end it.
- **Verify:** `check.sh`; device-verified. Explicitly a behaviour change, not a refactor.

## Task 8 — Mobile Session Cap selection and sync
- **Files:** `src/Harken.Mobile/Pages/CapturePage.xaml(.cs)`.
- **Change:** Cap picker (1h / 2h / 4h / none, default 2h) before Start; countdown and
  end-reason display in the UI as well as the notification.
- **Verify:** `check.sh`; device-verified.

## Task 9 — Docs
- **Files:** `README.md`, `docs/setup.md`.
- **Change:** Document the limits, their defaults, and what each end reason means. Setup
  guide gains the S0 move and the budget alert as rollout steps.
- **Verify:** fresh-eyes read; no doc claims a Session runs indefinitely.

---

## Exit criteria
`check.sh` and `test-fast.sh` green. Integration tests prove both limits fire, both are
attributed correctly, a normal stop is still `UserStopped`, and no transcript tail is lost
to an auto-stop. Client-side and device work is flagged device-pending, not claimed.

## Carried, unresolved
- Nothing in this project has run against live Azure Speech, Ollama, or a device. Slice 04
  adds timing behaviour that automated tests can only prove with compressed limits and
  fakes; real-world timing is unproven until it runs.
- Reconnect/resume after a dropped client is still deferred. Silence Timeout now bounds
  the cost of that case, which is the part that was actually urgent.
