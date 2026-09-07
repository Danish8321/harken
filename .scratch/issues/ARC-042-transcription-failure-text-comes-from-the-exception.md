# ARC-042 — A failed transcription showed the user the exception's message

- **Severity:** high
- **Status:** done
- **Area:** `speech/TranscriptionCoordinator.kt`, `speech/TranscriptionService.kt`,
  `telemetry/Telemetry.kt`

## Problem

```kotlin
repository.failLocal(sessionId, e.message ?: "On-device transcription failed")
```

`failureReason` is stored on the session and rendered verbatim on the Library
card. So whatever a `Throwable` happened to say became the app's explanation to
its user. Three separate problems in one line:

1. **It is not the reader's language.** Every other writer of this column passes
   a `strings.xml` string — the cancel path in this very function does. This one
   path built English into a layer with no `Context`, which is the case ARC-017
   named.
2. **It leaks an internal path.** A model that fails to load throws
   `error("Failed to load whisper model at $modelPath")`, so the card would read
   `Failed to load whisper model at /data/user/0/com.harken.android/files/models/…`.
3. **It threw away a classification the app already had.**
   `modelDownloadManager.ensureModel()` returns a `Result`, and
   `ModelDownloadFailure.of` already sorts those into no-connection / server /
   out-of-space / corrupt, with wording Settings and onboarding both use. The
   coordinator called `.getOrThrow()` and flattened all of it into whatever
   socket text came out. "Software caused connection abort" is the exact string
   ARC-006 removed from the download screens; it came straight back through this
   path.

Worse, the leak had become load-bearing: `Telemetry.describe` justified logging
`e.message` on the grounds that it "is already shown to the user on the Library
card", so one exposure was the argument for the other.

## The fix

Same shape as the recording-failure fix: the layer that knows the cause names
it, the layer that knows the reader writes the sentence.

- `TranscriptionMessages` — cancelled, failed, and a `modelUnavailable`
  function taking the `ModelDownloadFailure` the download manager already
  produces. Supplied by `TranscriptionService`, which has a `Context`; the
  defaults exist only for the JVM tests.
- A private `ModelUnavailableException` wrapper so the catch can tell "we never
  got a model" — actionable — from "the decode failed" — not. `Result`s from
  `runCatchingDownload` never hold a `CancellationException`, so the wrapper
  cannot swallow one.
- Logs and telemetry describe the unwrapped cause, so nothing diagnostic is
  lost. `Telemetry.describe`'s doc now says what it is: an engineer's
  diagnostic, carrying no speech, read over `adb` — not the user's sentence.

Two tests: the general path asserts the exception's own message (a stand-in
path) does **not** reach the session, and a new one asserts a
`UnknownHostException` from the model provider is reported as `NoConnection`
rather than as a decode failure.

## Deliberately not done

The column still stores a sentence, so a reason written in one language stays in
it after the user changes language. Storing a cause code instead is a schema
change, and schema changes wait for `schema.sh` (ARC-015). Every writer has the
same property today, so this is a shape to fix once, not per-caller.

## Evidence

`check.sh` and `test-fast.sh` green.
