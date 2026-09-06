# ARC-030 — The WAV writer seeks before every write

- **Severity:** low
- **Status:** fixed
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

## Resolution

`WavWriter.write` no longer seeks. The file pointer is left at the end of the
data by the placeholder header and by every write, and the only thing that ever
moves it is `patchLengths()`, which runs once, on close — so the seek was a
syscall per chunk to arrive where the pointer already was.

The invariant that replaces it is now stated in a comment and guarded by a test
(`successive writes append in order`), which also covers the offset/length case
ARC-013 made load-bearing.

`patchLengths()` narrows through a `require` that names the format's own limit:
both header fields are unsigned 32-bit, so a WAV cannot describe more than 4 GB.
The app's three-hour cap is 345 MB, a hundredth of that, so this cannot fire
today — it is there because a silent narrowing would write a negative length
into a file the user believes they still have.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 150 unit tests, 0
  failures (139 before this change).

Not measured on a device. The costs here are arithmetic — passes over a chunk,
sorts per second, bytes allocated per second — and they are counted from the
code, not from a profile; what a device would add is how much of the audio
path's budget they were.
