# Slice 10: Organic design system adoption

Implements [ADR-0013](../adr/0013-organic-design-system-adoption.md). Branch: new,
off `master`, started only after `feat/on-device-transcription` (slice-09) merges —
both touch `SessionSheet.kt`, `RecordScreen.kt`, and Library.

Reference: `docs/design/claude-design-modernization/` (pulled mocks + token sheet).

## Tasks

### Task 1 — Spacing + elevation tokens
**Files:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/theme/Spacing.kt` (new),
`src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/theme/Elevation.kt` (new),
`src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/theme/Theme.kt`.
**Change:** `Spacing` object with `space1`–`space8` (4, 9, 13, 18, 26, 35.dp —
rounded from the CSS px values). `Elevation` object with `shadowSm/Md/Lg` as
`androidx.compose.ui.graphics.Shadow` or elevation `dp` values matching the CSS
ink-tinted shadow intent. Add named radius aliases (`RadiusTokens.sm/md/lg`) in
`Theme.kt` mapping onto existing `HarkenShapes` steps (`sm` → `extraSmall`, `md` →
`medium`, `lg` → `extraLarge`) — no change to `HarkenShapes` values themselves.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds.

### Task 2 — Room: isArchived + userTitle columns
**Files:** `LocalModels.kt`, `SessionDao.kt`, `HarkenDatabase` migration list.
**Change:** add `val isArchived: Boolean = false` and `val userTitle: String? = null`
to `SessionRow`. Bump database version, add `MIGRATION_x_y` with two additive
`ALTER TABLE` statements — read the generated migration before applying, per
`.claude/scripts/schema.sh` discipline, same pattern as slice-09's `isLocalOnly`
column. Add `SessionDao.setArchived(id, archived: Boolean)` and
`SessionDao.renameSession(id, title: String)`. Confirm during this task whether the
existing upload/transcription status enum already distinguishes queued/retrying/
failed for the upload-queue UI in Task 4, or needs an additive field — if it does,
add it here in the same migration.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Migration test asserting
both columns exist post-migration.

### Task 3 — Provisional title logic
**Files:** `SessionRepository.kt`, `SessionSheetViewModel.kt`.
**Change:** title resolution becomes `userTitle ?: firstTranscriptLine ?: "Untitled
recording"`. Rename action in the session sheet calls `setUserTitle`, which sets
`userTitle` explicitly (never re-derives after that).
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Unit test on the resolution
function covering all three fallback branches.

### Task 4 — UploadQueueCard component + Library wiring
**Files:** `ui/components/UploadQueueCard.kt` (new), `LibraryScreen.kt` (or
equivalent), `LibraryViewModel.kt`.
**Change:** `UploadQueueCard` renders per-item queued/retrying/failed state with a
"Retry now" action, matching the mock's Library queue card. View model exposes the
queue list derived from session upload status (Task 2 confirms the source field).
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: force an unreachable
backend, confirm queued items render and "Retry now" re-triggers upload.

### Task 5 — StorageWarningBanner component + capture wiring
**Files:** `ui/components/StorageWarningBanner.kt` (new), `RecordScreen.kt`,
`CaptureViewModel.kt`.
**Change:** banner renders during active recording when approaching the 3-hour cap
(mirrors mock's "2h 42m recorded... approaching the 3-hour cap" copy) — non-alarming
tone per the mock's caption.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: verify banner
appears near the cap threshold (can shorten the cap in debug builds to test without
waiting 3 hours).

### Task 6 — SoftArchiveSwipeRow component + Library wiring
**Files:** `ui/components/SoftArchiveSwipeRow.kt` (new), `LibraryScreen.kt`.
**Change:** swipe-to-archive (calls `SessionDao.setArchived(true)`), with undo
snackbar. Hard delete remains the existing typed-confirmation flow, unchanged and
still reachable separately — archive is additive, not a replacement.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: swipe archives (file
kept, session hidden from default Library filter), undo restores, existing delete
flow still requires typed confirmation.

### Task 7 — Predictive back on session detail sheet
**Files:** `SessionSheet.kt`.
**Change:** wire `PredictiveBackHandler` (or `BackHandler` with predictive-back
support per current AndroidX version) on the session sheet's dismiss.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: on Android 14+,
system back gesture shows the predictive-back preview when dismissing the sheet.

### Task 8 — PermissionSheet component
**Files:** `ui/components/PermissionSheet.kt` (new), wherever mic permission is
currently requested (`RecordScreen.kt` or `OnboardingScreen.kt`).
**Change:** One UI-style bottom sheet replaces the current permission request UI
(system dialog trigger unchanged — this wraps the rationale/explanation step before
it, matching the mock).
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: fresh install,
confirm sheet renders before the system permission dialog, mic recording still works
after granting.

### Task 9 — GroupedSettingsList component + Settings wiring
**Files:** `ui/components/GroupedSettingsList.kt` (new), `SettingsScreen.kt`.
**Change:** icon-forward, grouped rows per the mock, replacing the current flat
settings list. No change to what each setting does — visual/structural only.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: every existing
setting still reachable and functional in the regrouped layout.

### Task 10 — Live Update notification
**Files:** `notifications/LiveUpdateNotifier.kt` (new, alongside existing
foreground-service code per ADR-0003), `CaptureViewModel.kt` or the recording
foreground service.
**Change:** promoted lock-screen progress notification during active recording,
using the platform's progress-centric notification API (Android 14+ promoted
notifications / Android 16 Live Updates where available; graceful plain-notification
fallback on older versions).
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: start a recording,
confirm the notification appears on the lock screen and updates with elapsed time.

### Task 11 — Existing screens migrate onto Spacing tokens (lowest priority)
**Files:** all screens under `ui/` with hardcoded `dp` padding/spacing literals
(`RecordScreen.kt`, `SessionSheet.kt`, `LibraryScreen.kt`, `SettingsScreen.kt`,
`OnboardingScreen.kt`).
**Change:** replace magic-number `dp` literals with `Spacing.space*` from Task 1.
Purely mechanical — no visual change (values chosen to match existing layout).
Sequenced last and explicitly non-blocking: if time-constrained, this task can be
dropped without affecting Tasks 1–10.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Visual diff/manual check
that no screen's layout shifted.

## Full gate
After all tasks: `.claude/scripts/check.sh` green, plus manual checks listed per task
run on a real device (notification/permission-sheet/predictive-back behavior can't be
fully verified by compile checks alone).
