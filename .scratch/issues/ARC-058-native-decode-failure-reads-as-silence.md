# ARC-058 — A failed native decode is recorded as silence, and the transcription reports success

- **Severity:** high
- **Status:** fixed
- **Area:** `cpp/harken_whisper_jni.cpp`, `speech/OnDeviceTranscriber.kt`

## Problem

`nativeTranscribe` returns a JSON array of segments. It returns `"[]"` on three different
paths, and only one of them means "whisper found nothing to say":

```cpp
if (handle == 0) { LOGE(...); return env->NewStringUTF("[]"); }
...
jshort* samples = env->GetShortArrayElements(pcm16, nullptr);
if (samples == nullptr) { return env->NewStringUTF("[]"); }
...
const int result = whisper_full(ctx, wparams, pcmf32.data(), ...);
if (result != 0) { LOGE("whisper_full failed with code %d", result); return env->NewStringUTF("[]"); }
```

Nothing above the JNI boundary can tell those apart from a genuinely empty span. In
`OnDeviceTranscriber` the empty list flows through `flatMapIndexed`, contributes no
segments, and the loop moves to the next span. In `TranscriptionCoordinator` the only route
to `failLocal` is an exception, so the decode finishes normally and calls:

```kotlin
repository.completeLocal(sessionId, segments, audioSeconds)
```

The session is marked `Succeeded`. What the user gets is a transcript with a hole in it —
one span of their recording, up to 300 seconds, silently missing — and no error anywhere in
the app. The audio is still on disk, so nothing is destroyed; what is lost is any indication
that the Transcript, which this product calls its hero, is incomplete.

The reachable one of the three is `whisper_full != 0`. `handle == 0` cannot happen today
(`transcribe` raises if `nativeLoadModel` returns 0) and `GetShortArrayElements` returning
null is an OOM, but both are the same mistake and would fail the same silent way.

Telemetry sees it and no one else does: `span_decoded` carries `segments=0` for that span
and `transcribe_finished` says `outcome=succeeded`. A logcat line — `whisper_full failed
with code %d` — exists but is only reachable with a cable attached.

## Related but not this

ARC-042 fixed the app showing `Throwable.message` to the user, and the messages layer here
is unchanged: the fix must not put a native error string on a Library card.

The `abort` path is deliberately not an exception in C++ and must stay that way. A cancelled
`whisper_full` also returns an empty result, and `OnDeviceTranscriber` already turns that
into a `CancellationException` on the Kotlin side via `decodeJob.ensureActive()` — which the
coordinator handles as "cancelled", not "failed". Throwing from the native side would reach
the same catch through a worse route.

## The fix

Throw from JNI on all three, and let the existing failure path do what it already does:

```cpp
jstring ThrowDecodeFailure(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
    return nullptr;
}
```

`java.lang.IllegalStateException` rather than a Kotlin exception of our own. A class
referenced only from C++ has no Java-side reference for R8 to see, so it is exactly the
shape R8 renames or removes — and this file already carries the scar of that failure mode
(`parseNativeSegments`' doc records R8 breaking a reflective mapper on release builds only).
A platform class cannot be stripped.

The coordinator needs no change. An `IllegalStateException` out of `transcribe` lands in its
general `catch (e: Exception)`, which logs the cause, emits `outcome=failed`, and calls
`failLocal(sessionId, messages.failed)` — the localized string, never the exception's own
message.

One more thing on the same lines, found while reading them. `GetShortArrayElements` pins the
array and `ReleaseShortArrayElements` is called after `ToWhisperPcm` returns:

```cpp
std::vector<float> pcmf32 = ToWhisperPcm(...);
env->ReleaseShortArrayElements(pcm16, samples, JNI_ABORT);
```

`ToWhisperPcm` allocates a `std::vector<float>` — 19 MB for a 300-second span — so it can
throw `std::bad_alloc`, and if it does the array stays pinned forever and a C++ exception
unwinds through a JNI frame, which is undefined. The release belongs in a scope guard so it
happens on every path.

## Evidence

`NativeTranscribeFailureTest` (instrumented — `OnDeviceTranscriber`'s companion loads the
native library in its initializer, so these externals cannot be touched off a device). It
drives the null-handle path, which is the only one of the three reachable from a test
without a model or an out-of-memory device, and asserts an `IllegalStateException` carrying
that path's message. What it proves is the mechanism end to end: `FindClass` resolves under
the build's naming, the exception crosses the JNI boundary, and Kotlin sees a throw instead
of `"[]"`.

`bash .claude/scripts/test-full.sh` on 'AIN065': **16 tests, 0 failures** (15 before).
`check.sh` OK, `test-fast.sh` OK.

Falsified by putting `return env->NewStringUTF("[]")` back on the null-handle path: exactly
`aDecodeWithNoModelHandleThrowsRatherThanReturningAnEmptyTranscript` fails and nothing else
(16 tests, 1 failure).

The other half of the path needed no new test and got none.
`TranscriptionCoordinatorTest`'s `a transcription failure marks the session failed and still
releases the transcriber` already drives an `IllegalStateException` out of the transcriber —
the same type the bridge now throws — and asserts `failLocal` with the localized message and
a released handle. A second test would assert the same thing twice.

**Not reproduced on a device as the user would meet it.** `whisper_full` returning non-zero
is the reachable path and there is no way to make it fail on demand without the model, which
this phone does not have and cannot get over its metered LTE (same blocker as ARC-056). What
has been proven is that a failure now raises and that a raise is reported as a failed
transcription; what has not is a real `whisper_full` fault end to end. Worth doing in the
same Wi-Fi session that clears ARC-056.
