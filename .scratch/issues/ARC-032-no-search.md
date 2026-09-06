# ARC-032 — There is no way to find anything

- **Severity:** high
- **Status:** open
- **Area:** `ui/LibraryScreen.kt`, `data/local/SessionDao.kt`

## Problem

Library offers four filters (All / Meetings / Field / Ideas) and no search. The
entire value of transcribing a meeting is being able to find the sentence
later; without search, a transcript is something you scroll.

This is the largest gap between what the app does and what a daily driver does.
It is also nearly free: the transcript segments are already rows in SQLite, and
Room supports FTS4 through `@Fts4` with a content table, so the query is a
schema change and a `WHERE ... MATCH ?`, not a new subsystem.

## Fix

An FTS-backed search over segment text and titles, surfaced as a field at the
top of Library, with the matching line shown on the result row and the
transcript opened scrolled to it. Schema change goes through
`.claude/scripts/schema.sh`; show the migration before applying.
