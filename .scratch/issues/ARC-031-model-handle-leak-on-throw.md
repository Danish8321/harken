# ARC-031 — A failed `nativeFreeModel` leaks the handle permanently

- **Severity:** low
- **Status:** open
- **Area:** `speech/OnDeviceTranscriber.kt`

## Problem

`release()` calls `nativeFreeModel(modelHandle)` and then nulls `modelHandle`.
If the native call throws, the field keeps a pointer to memory that may or may
not have been freed, and the next `transcribe` reuses it — a double-free or a
use-after-free, in the 480 MB allocation the whole app is sized around.

`release()` is called from `TranscriptionCoordinator`'s `finally`, so this is
on the failure path of the failure path.

## Fix

Null the field first, or free in a `finally`, so the handle is never
reachable after an attempted free.
