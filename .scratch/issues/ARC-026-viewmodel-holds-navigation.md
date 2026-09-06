# ARC-026 — A ViewModel holds the navigation callbacks

- **Severity:** medium
- **Status:** open
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
