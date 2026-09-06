# ARC-030 — The WAV writer seeks before every write

- **Severity:** low
- **Status:** open
- **Area:** `audio/WavWriter.kt`

## Problem

`write()` calls `file.seek(HeaderLength + dataLength)` on every chunk, 6.25
times a second, when the file pointer is already exactly there — nothing else
moves it between writes except `patchLengths()`, which runs once on close.

Also in the same file: `patchLengths()` narrows `dataLength.toInt()` with no
check. A WAV cannot exceed 4 GB by its own format, and three hours at 32 kB/s
is 345 MB, so this cannot bite today — but it is an unguarded narrowing on the
one value that determines whether a recording is readable.

## Fix

Seek only after a `patchLengths`, and make the narrowing explicit with a
`require` that names the format's limit.
