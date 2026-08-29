# UI-032 — On-device pivot leftovers: hardcoded model claim, stale backend copy, dead upload states, undocumented ink system, illegible duration bar

- **Severity:** high
- **Status:** resolved
- **Area:** `ui/SessionSheetViewModel.kt`, `res/values/strings.xml`,
  `ui/RecordScreen.kt`, `ui/LibraryScreen.kt`, `ui/SessionSheet.kt`,
  `ui/theme/Theme.kt`, `ui/components/HarkenSurfaces.kt`,
  `docs/brand-guidelines.md`

## Problem

Full-screen on-device pass (Record/Library/Settings/Session sheet, idle +
live-recording + saved states) via `adb`, cross-checked against source,
after commit `80c463d` ("on-device-only transcription", ADR-0011 —
self-hosted-backend model retired). Findings below, not yet discussed or
scoped.

## Findings

1. **Session sheet fabricates the model used.**
   `SessionSheetViewModel.kt:113` — `buildMeta()` hardcodes
   `"whisper base.en"` into every session's meta line unconditionally.
   Verified on-device: a session with `0 segments` (never transcribed)
   still shows "· whisper base.en", while Settings shows the model as
   "Not downloaded yet." Same bug class as the hardcoded `"studio-mac"`
   host UI-023 fixed and `brand-guidelines.md` §4 calls *"a
   plausible-looking lie on every device."* This one wasn't caught by
   that fix.

2. **Library empty state describes a product that no longer exists.**
   `strings.xml:68` — `library_empty_body`: *"Recordings appear here the
   moment an upload lands. The phone keeps the file either way, so
   nothing is lost if the backend is asleep."* There is no backend and no
   upload post-ADR-0011 (`SettingsViewModel.kt:23`: *"no backend URL to
   configure"*). Confirmed on-device: Settings has no backend/host field
   at all, only a local "Speech Model / Download" row.

3. **Dead upload states still carry copy.** `strings.xml:40,42-43`
   (`record_upload_uploading_title` = "Uploading…",
   `record_upload_ok_title` = "Uploaded · transcribing",
   `record_upload_ok_body` = "Tap to follow the transcript") back
   `UploadStatus.Uploading` and the `Succeeded && !lastSavedLocally`
   branch in `RecordScreen.kt`. Verified on-device: a real recording
   goes straight to `Saved`/`lastSavedLocally = true`
   (`record_saved_local_title/body`) — the other three strings and their
   branches have no live caller under the on-device flow.

4. **`brand-guidelines.md` is stale on the same axis, under its own
   rule** (*"the code is what settles a disagreement"*):
   - Quick Reference / Positioning: *"A quiet, self-hosted recorder —
     audio never leaves your own network unless you send it there."*
     False now — audio never leaves the device, there is no network
     story.
   - §4's backend-pill paragraph (UI-023's fix, host label + neutral
     fill) describes UI that no longer exists — grepped `RecordScreen.kt`
     for `backend`/`host`/`pill`, zero matches.

5. **Duration bar is illegible with fewer than two differing
   recordings.** `LibraryScreen.kt:243-248` — bar width is
   `duration / longestSeconds`, so with one recording (or several of
   equal length) the bar is always 100% full. Confirmed on-device: first
   recording renders as a barely-visible dot at the far right of a
   low-contrast track (`c.textSecondary` fill on `c.cardBorder` track —
   both muted grays), no label identifies it as a relative-duration
   indicator. Intent is documented in the code comment ("honest data in
   the space the fake waveform used to fill") but that intent isn't
   legible to a first-time user with one recording, which is everyone's
   first session.

6. **Undocumented second color role: `LocalInk`/`Organic`.**
   `Theme.kt:97-99`, `HarkenSurfaces.kt:54` (`InkSurface`) — a
   theme-independent dark ink surface (deliberately stays dark regardless
   of app theme, per the `SessionSheet.kt:243` comment and ADR-0010),
   used for the "Recorded on-device; no audio file to play back" card.
   This is a real, intentional signature choice — same spirit as the
   accent doubling as the live/idle state — but has no mention anywhere
   in `brand-guidelines.md`. Distinct from UI-002 (which was about
   `MaterialTheme` vs `ProtoColors` duplication, now resolved) — `Ink` is
   a third, narrower, *intentionally* separate palette that was trimmed
   but kept during UI-002's resolution, not folded in.

## Not the problem

- UI-002 (two parallel design systems) — re-verified on-device: Session
  sheet, nav bar, and Record/Library/Settings all read as one consistent
  palette in the current build. That fix holds; not re-opening it.
- Record idle/live/saved copy and flow — matches the brand voice
  guidelines exactly, confirmed on-device tap-through (permission prompt
  timing, "Ready when you are.", live capture badge, "Saved" confirmation
  all correct and honest).
- Icon usage in Session sheet bottom bar (copy/share) — all
  `Icons.Filled.*`, consistent; initial suspicion of a filled/outline
  mismatch did not hold up against the source and was dropped rather
  than reported.

## Fix

- **1, 2, 3**: remove or correct every backend/upload/model string that
  no longer matches ADR-0011 reality — `buildMeta()` should report the
  actual model state (or omit the clause when nothing was transcribed),
  the Library empty-state copy should describe on-device behavior, and
  the three unreachable `UploadStatus` branches + their strings should be
  deleted, not left as dead weight for someone to "fix" later.
- **4**: pass over `brand-guidelines.md` removing the self-hosted/backend
  narrative (Quick Reference positioning line, §4 backend-pill
  paragraph), replacing with the actual on-device story.
- **5**: either add a visible unit/label (e.g. show the duration text
  directly instead of/alongside the bar until there's a second data
  point to compare against), or raise contrast between fill and track so
  a full bar reads as "this is the longest one" rather than as a stray
  line.
- **6**: add a short entry to `brand-guidelines.md` (§1 or §5) documenting
  `LocalInk`/`InkSurface` as the deliberately theme-fixed audio-surface
  treatment, so it isn't mistaken for a light-theme bug later.

## Verification

- `bash .claude/scripts/check.sh` and `.claude/scripts/test-fast.sh` clean
  after each fix.
- On-device re-check: fresh install, one recording through to Library →
  Session sheet, confirm no backend/upload/model copy remains that
  doesn't match ADR-0011; confirm duration bar or its replacement reads
  correctly with exactly one recording.

## Resolution

1. `SessionSheetViewModel.kt` `buildMeta()` now takes a `transcribed: Boolean`
   parameter (call site passes `rows.isNotEmpty()`); the "· whisper base.en"
   clause is gated on it instead of always showing.
2. `strings.xml` `library_empty_body` rewritten to describe the real
   on-device flow; `library_error_title/body/change_address` and
   `state_retry` deleted along with the `ErrorState` composable in
   `HarkenStates.kt` (its dead import removed from `LibraryScreen.kt`).
3. `CaptureViewModel.kt`'s `UploadStatus` enum dropped `Uploading`;
   `CaptureUiState.lastSavedLocally` removed (always `true` under
   ADR-0011). `RecordScreen.kt`'s `UploadStatusCard` updated to match —
   the `Uploading` icon/text branches removed, `Succeeded` now
   unconditionally shows `record_saved_local_title/body`. Dead strings
   `record_upload_uploading_title/body`, `record_upload_ok_title/body`
   deleted from `strings.xml`.
4. `docs/brand-guidelines.md` (now v2.3): Quick Reference positioning line,
   §4 voice/tone evidence, the tone-by-context examples, and the "BACKEND"
   eyebrow-label reference all replaced with on-device-accurate copy;
   old backend-pill note replaced with a note that the pill is gone.
5. `LibraryScreen.kt` `SessionCard` now takes `sessionCount`; the duration
   bar only renders when there's more than one session to compare
   against (`sessionCount > 1`), so a single/only recording no longer
   shows a meaningless always-full bar — it falls back to just the tag
   row (or nothing, if untagged).
6. `docs/brand-guidelines.md` §1 gained an "ink surface is a separate,
   deliberate palette" section documenting `Organic`/`LocalInk`/
   `InkSurface` as intentional, `ProtoColors`-independent, audio-only
   tokens.

Verified via `.claude/scripts/check.sh` and `.claude/scripts/test-fast.sh`
(both clean) after each finding's fix and once more at the end.
