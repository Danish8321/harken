# ARC-027 — Two tickets share the ID UI-032

- **Severity:** low
- **Status:** fixed
- **Area:** `.scratch/issues/`

## Problem

`UI-032-on-device-pivot-leftovers.md` and
`UI-032-stuck-transcription-and-repair-header.md` are different tickets with
the same number. The index links one of them; commit messages referring to
"UI-032" are ambiguous.

## Fix

Renumber the later one and fix the index row and any commit-message references
in the log going forward.

## Resolution

`UI-032-stuck-transcription-and-repair-header.md` is now
`UI-037-stuck-transcription-and-repair-header.md`, and it has an index row,
which it never had. It is the one that moved because it is the one nothing
points at: the index row, `docs/brand-guidelines.md` (twice) and
`ui/components/HarkenStates.kt` all cite UI-032 meaning the pivot-leftovers
ticket.

UI-033 was left alone even though no file uses it — the renumbered ticket's own
text says it was "found during the error/warning audit for UI-033", so that
number belongs to something. UI-037 is the next free one.

A note at the top of the renumbered file says what it used to be called and when
the ambiguity ends. Commit messages already written are not rewritten: history
that has been committed is a record, and a `git filter-branch` over a naming
mistake would cost more than the ambiguity does.

## Evidence

`git log --diff-filter=A` on both files, and a grep for `UI-032` across the
repository, which is what established which of the two the rest of the project
believed it was referring to. No build gate is involved — nothing here compiles.
