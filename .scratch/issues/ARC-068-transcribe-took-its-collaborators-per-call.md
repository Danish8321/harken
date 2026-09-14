# ARC-068 — `TranscriptionCoordinator.transcribe` took its collaborators every call

- **Severity:** low
- **Area:** `speech/TranscriptionCoordinator.kt`, `speech/TranscriptionService.kt`
- **Status:** fixed

## Problem

Slice-09 review finding **S9**, the last one open. `transcribe(repository, modelDownloadManager,
onDeviceTranscriber, sessionId, filePath, …)` took its three collaborators as parameters,
even though `AppContainer` already builds exactly one of each app-wide (ARC-014) and hands
them to the coordinator's one real caller, `TranscriptionService`. In practice there was
only ever one call site assembling those three arguments; the finding's cost was the shape
of the API, not a proliferation of callers.

## The fix

`TranscriptionCoordinator.bind(repository, modelDownloadManager, onDeviceTranscriber)`, three
`lateinit var` fields, called once from `TranscriptionService.onCreate()` with what it
already reads off `container`. `transcribe()` drops those three parameters and keeps
`sessionId, filePath, messages, onProgress`.

`lateinit`, not nullable: a call to `transcribe()` before `bind()` is a startup-ordering
bug, and `UninitializedPropertyAccessException` says so where a silent no-op would look like
a decode that quietly never started.

**Not converted to a class owned by `AppContainer`**, the alternative this was weighed
against. `TranscriptionCoordinator` is read as a bare object from `LibraryViewModel`
(`activeSessionId`) and `TranscriptionService` (`cancel()`) with no container reference
threaded through either constructor; making it an instance would touch both of those and
their `ViewModelProvider.Factory` wiring for a class that only ever has one instance in
the first place — the same reasoning `AppContainer`'s own doc gives for `by lazy` fields
being enough without a DI framework.

Each test's fakes now go through `bind()` once, before its `transcribe()` call(s) — the same
grouping the old parameter list had, just split at the point where the real caller already
splits it: build once, decode as many times as needed.

## Evidence

`.claude/scripts/check.sh` and `.claude/scripts/test-fast.sh` both pass (260 tests). All
nine of `TranscriptionCoordinatorTest`'s existing tests pass unchanged in what they assert —
only the call shape changed — which is what a refactor's evidence looks like. No new test:
nothing about `transcribe`'s behaviour moved, only where its inputs come from.

## What this does not cover

- **A rebind while a decode is in flight is not guarded against.** The fields are read live
  inside the coroutine, not captured at `transcribe()`'s call time as the old parameters
  were, so a `bind()` between one call and the next changes what the *already-running*
  decode sees for anything it has not yet reached. Harmless in production, since
  `AppContainer`'s three fields are themselves `by lazy` singletons and every `bind()` call
  passes the same three objects — but it is a weaker guarantee than closing over the
  arguments gave, and this is only true because nothing else rebinds mid-decode today.
- `TranscriptionService` is an Android `Service` and untestable on the JVM either before or
  after this change; nothing here was run on a device.
