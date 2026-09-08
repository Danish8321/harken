# ARC-046 — Finishing one transcription can kill the foreground service of the next

- **Severity:** high
- **Status:** fixed
- **Area:** `speech/TranscriptionCoordinator.kt`, `speech/TranscriptionService.kt`

## Problem

`TranscriptionCoordinator`'s cleanup (`TranscriptionCoordinator.kt:151-154`)
clears two separate pieces of state, not atomically:

    active.set(null)
    _activeSessionId.value = null

`TranscriptionService` watches `activeSessionId` and, per
`TranscriptionService.kt:142-147`, calls `stopForeground`/`stopSelf()` for
its own session as soon as that value stops matching its own id — that
behaviour exists so the service goes away once its work is done.

If session A finishes and session B's `transcribe()` call lands on another
thread between the two lines above, B's `active.compareAndSet(null, B)`
succeeds and `_activeSessionId.value = B` is set — then A's finishing
coroutine resumes and overwrites `_activeSessionId` back to `null`, even
though `active` now correctly holds B. B's foreground service sees a value
that isn't its own id and tears itself down while B's decode is genuinely
still running in `TranscriptionCoordinator`'s scope — the exact
"decode dies once the foreground service goes away" failure ARC-003 was
written to prevent, reopened through a narrower race window.

## Fix

Make the two updates atomic with respect to a new `transcribe()` call: e.g.
only clear `_activeSessionId` if it still equals the finishing session's id,
or fold `active` and `_activeSessionId` into one state object updated under
a single `compareAndSet`.

## Found by

Fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

`active.set(null)` stays unconditional — it's this session's own turn to release the
slot, and nothing else can touch `active` until that write lands. `_activeSessionId.value
= null` became `_activeSessionId.compareAndSet(sessionId, null)`: it only clears the flow
if it still holds this session's own id, so a session that already claimed it in the
interleaving window survives.

No new test: the window is two back-to-back non-suspending statements with no yield
between them, so it isn't something a JVM test can reliably force without adding
production-code test hooks purely to make an instruction-level race schedulable — out of
scope for this fix. Reasoned through both possible interleavings by hand (documented in
the ticket's Problem section) and confirmed both resolve to the correct final state.

Verified: `check.sh` OK, `test-fast.sh` OK (existing `TranscriptionCoordinatorTest` suite
unaffected), `test-full.sh` OK (device `AIN065 - 16`, fresh install, full instrumented
suite).
