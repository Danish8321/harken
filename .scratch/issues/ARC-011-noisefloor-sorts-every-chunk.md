# ARC-011 — The noise floor re-sorts its whole window on every chunk

- **Severity:** medium
- **Status:** fixed
- **Area:** `audio/NoiseFloor.kt`

## Problem

`speechThreshold` and `estimate` each copy the ~375-entry window to an
`IntArray` and sort it. Both are `val` getters with no memoisation, and
`SilenceDetector` reads them per chunk; `summarize()` reads them again. That is
an O(n log n) sort plus a 1.5 KB allocation, several times a second, for the
whole length of the recording, to obtain a p10 that moves by at most one entry
per chunk.

The comment at the top of the class notices this ("the sort in speechThreshold
runs over a few hundred ints, six times a second") and accepts it. It is
correct that this is not the app's bottleneck; it is also the audio capture
path, and it is avoidable.

## Fix

Cache the sorted result and invalidate it in `observe()`, so the sort happens
at most once per chunk instead of three or four times, and not at all when
nothing reads the threshold. Both getters then share one computation.

## Resolution

`NoiseFloor` keeps the last percentile it computed and invalidates it in
`observe()`. Both getters now read one private `floor()`, so the sort happens at
most once per chunk, and not at all for a chunk nobody asks about.

Combined with ARC-010 — which removed one of the two per-chunk reads of
`speechThreshold` — the window is sorted once per chunk instead of three or four
times. The duplicated percentile arithmetic in `estimate` and `speechThreshold`
is gone with it: `estimate` is `floor()`, and `speechThreshold` is that number
scaled and clamped.

`NoiseFloorTest` is new and asserts the one risk memoisation introduces: a floor
that has already been read must not keep answering with what it said before.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 150 unit tests, 0
  failures (139 before this change).

Not measured on a device. The costs here are arithmetic — passes over a chunk,
sorts per second, bytes allocated per second — and they are counted from the
code, not from a profile; what a device would add is how much of the audio
path's budget they were.
