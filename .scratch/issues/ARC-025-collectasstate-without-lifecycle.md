# ARC-025 — Flows keep collecting while the app is in the background

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/` (7 call sites)

## Problem

Every flow in the UI is read with `collectAsState()`. `collectAsStateWithLifecycle`
appears nowhere. `collectAsState` binds collection to the *composition*, which
survives the app going to the background, so the amplitude meter's
`StateFlow<Int>` — updated 6.25 times a second by the recording service —
continues to push recompositions to a screen nobody is looking at, for the
whole recording.

This is the recomposition budget being spent on nothing, on the one code path
where the app is already doing continuous work, on battery.

## Fix

`collectAsStateWithLifecycle()` throughout (already available via
`lifecycle-runtime-compose`, which the project depends on).

## Resolution

All nine call sites across six files now use `collectAsStateWithLifecycle`,
including `AppNav`'s cold-flow read, which takes `initialValue` instead of
`initial`. The one that matters most is `RecordScreen`'s amplitude meter: a
`StateFlow<Int>` written 6.25 times a second by the recording service, which was
recomposing a screen nobody was looking at for the whole length of a recording.

### Correction to the finding

The ticket said `lifecycle-runtime-compose` was already a dependency. It was not
declared — it was arriving transitively at 2.9.0 through material3 1.4.0, which
pulls the whole `androidx.lifecycle` group up. So the API compiled without any
build change, which is exactly the fragile case: a version bump elsewhere could
have taken it away.

It is now declared in the version catalog and in `app/build.gradle.kts`, against
the same `lifecycle` version ref as the two entries beside it. No new artifact
enters the build — this names one that was already there.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`, 151 unit tests, 0
  failures.

Not seen on a device. What a phone would show that a JVM cannot: the notification
itself, and whether backgrounding the app actually stops the meter recomposing.
