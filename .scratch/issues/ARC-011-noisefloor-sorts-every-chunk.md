# ARC-011 — The noise floor re-sorts its whole window on every chunk

- **Severity:** medium
- **Status:** open
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
