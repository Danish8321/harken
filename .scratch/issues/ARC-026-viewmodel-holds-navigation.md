# ARC-026 — A ViewModel holds the navigation callbacks

- **Severity:** medium
- **Status:** closed
- **Area:** `ui/LibraryViewModel.kt`

## Problem

`LibraryViewModel` exposes mutable `var onNavigateToRecord` / `onNavigateToSettings`
lambda properties, assigned from the composable. The ViewModel outlives the
composition, so it holds a lambda capturing the `NavController` — a reference
from the layer that survives to the layer that does not, reassigned on every
recomposition that runs the assignment.

Navigation is a UI concern by definition; a ViewModel that knows about
destinations cannot be reasoned about or tested without one.

## Fix

Expose the intent as an event (a `Channel`/`SharedFlow` of "the user wants to
record"), or pass the navigation lambda to the composable, which is where it
already comes from.

## Resolution

`LibraryScreen` already received `onGoToRecord` as a parameter; the empty state
now calls it directly:

```kotlin
onAction = if (filter == LibraryFilter.All) onGoToRecord else null,
```

The `onNavigateToRecord` / `onNavigateToSettings` vars, the `LaunchedEffect` that
assigned one on every recomposition, and the `goToRecord`/`openSettings` methods
are gone — `openSettings` had no caller at all. The ViewModel no longer holds a
lambda capturing the `NavController`, which is a reference from a layer that
outlives the composition to one that does not.

## Evidence

`check.sh` OK, `test-fast.sh` OK.

## Device verification

Nothing Phone 2, fresh install: with the library empty, "Record something" in the
empty state navigates to the Record tab (`topResumedActivity=...MainActivity`,
Record tab selected and the capture card showing "tap to start").
