# ARC-031 — A failed `nativeFreeModel` leaks the handle permanently

- **Severity:** low
- **Status:** closed
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

## Resolution

```kotlin
val handle = modelHandle ?: return
modelHandle = null
nativeFreeModel(handle)
```

The field is cleared before the free, so a throwing `nativeFreeModel` cannot
leave a reachable pointer for the next `transcribe` to reuse. A handle that leaks
is a bounded loss; a handle freed twice is a tombstone in the 480 MB allocation
the app is sized around.

## Evidence

`check.sh` OK, `test-fast.sh` OK.

## Device verification

Nothing Phone 2, fresh install. Two decodes in one process — one that found no
speech and one that decoded 33 seconds into 8 segments — each loading a model
(`modelLoadMs=177`, then `142`, both `modelCached=false`, so each was really
loaded and really freed). No SIGSEGV and no tombstone.
