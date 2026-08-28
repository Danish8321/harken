# UI-016 — SessionSheet transcript reveal

- **Severity:** low
- **Status:** open
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
