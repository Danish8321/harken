# ARC-027 — Two tickets share the ID UI-032

- **Severity:** low
- **Status:** open
- **Area:** `.scratch/issues/`

## Problem

`UI-032-on-device-pivot-leftovers.md` and
`UI-032-stuck-transcription-and-repair-header.md` are different tickets with
the same number. The index links one of them; commit messages referring to
"UI-032" are ambiguous.

## Fix

Renumber the later one and fix the index row and any commit-message references
in the log going forward.
