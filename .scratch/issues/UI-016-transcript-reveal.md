# UI-016 — SessionSheet transcript reveal

- **Severity:** low
- **Status:** fixed
- **Area:** `ui/SessionSheet.kt`

## Problem

Transcript/summary content in the session sheet appears all at once when
loaded — no reveal, doesn't match the reading rhythm of actually reading
a transcript.

## Fix

Paragraphs fade in individually as the sheet's content settles (not
per-character typewriter — too slow for anything but a short transcript,
and fights `HarkenMotion`'s spring-based system). Stagger by paragraph
index using `effectsFast()`, capped similarly to UI-015's list stagger.
Must collapse to instant under reduced motion.

## Verification

- `bash .claude/scripts/check.sh`
- `uninstallDebug` + `installDebug` clean install
- On-device: open a session with a transcript (backend reachability
  permitting; source-review fallback otherwise), confirm paragraph
  stagger on open.
- Reduced motion on: confirm instant, full content.

## Resolution

Same mechanism as UI-015's list stagger, applied to
`SessionSheet.kt`'s transcript `LazyColumn` (`items` -> `itemsIndexed`,
each `TranscriptRow` wrapped in `AnimatedVisibility` gated by a
per-segment `shown` flag flipped after `index.coerceAtMost(
TRANSCRIPT_STAGGER_CAP) * TRANSCRIPT_STAGGER_STEP_MS` — cap 10 rows,
30ms step). A `revealedSegmentIds` (`mutableStateSetOf<UUID>()`)
tracked above the `LazyColumn` prevents replay on scroll-away/back, and
`LocalReducedMotion.current` skips the artificial delay outright (row
shown immediately) since, same as UI-015, the delay itself isn't an
animation spec and doesn't auto-collapse the way `HarkenMotion`'s
tokens do.

**Verified:**
- `bash .claude/scripts/check.sh` -> `BUILD SUCCESSFUL` / `== check: OK ==`
- `uninstallDebug` + `installDebug` clean install
- On-device, foreground confirmed via `dumpsys window | grep
  mCurrentFocus`: app launches and runs without crashing with the new
  `SessionSheet.kt` logic compiled in.

**Not verified:** the actual paragraph-reveal stagger and its
reduced-motion collapse on-device — same blocking constraint as
UI-015: no reachable backend and no local sessions this pass means
there's no session to open a `SessionSheet` for at all. Verified by
source review and by direct comparison against UI-015's identical,
already-reasoned-through stagger/no-replay/reduced-motion logic.
