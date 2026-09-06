# ARC-014 — Every ViewModel builds its own dependencies

- **Severity:** medium
- **Status:** closed
- **Area:** `ui/*ViewModel.kt`, `MainActivity.kt`

## Problem

`SessionRepository(HarkenDatabase.get(context))`, `ModelDownloadManager(...)`
and `OnDeviceTranscriber(...)` are constructed independently inside
`LibraryViewModel`, `CaptureViewModel`, `SessionSheetViewModel`,
`SettingsViewModel` and `MainActivity`. There is no composition root: the graph
is assembled five times, in five places, from concrete types.

Consequences, in order of how much they cost:

- **`downloadInFlight` is a companion `AtomicBoolean` because there is no
  single `ModelDownloadManager`** — the guard exists to paper over the
  duplication (see ARC-019).
- Constructing `OnDeviceTranscriber` triggers `System.loadLibrary` in its
  companion `init`, so opening Library pulls the whisper JNI library in on
  whatever thread got there first.
- Nothing can be substituted in a test without reaching for a real `Context`
  and a real database, which is why the unit tests stop at the pure classes.

This is the Dependency Inversion violation with the widest blast radius in the
app: the interfaces (`Transcriber`, `ModelProvider`, `TranscriptionSink`)
already exist and are already used by `TranscriptionCoordinator`. Only the
wiring is missing.

## Fix

A single `AppContainer` created in `Application.onCreate`, holding one database,
one repository, one download manager, one transcriber, handed to ViewModels via
a `ViewModelProvider.Factory`. No new dependency — this is the
`Application`-owned-container pattern, not a DI framework.

## Resolution

`HarkenApplication` owns one `AppContainer`, and everything else asks it for what
it needs:

```kotlin
class AppContainer(context: Context) {
    val database: HarkenDatabase by lazy { HarkenDatabase.get(appContext) }
    val repository: SessionRepository by lazy { SessionRepository(db = database) }
    val settings: AppSettings by lazy { AppSettings(appContext) }
    val modelDownloadManager: ModelDownloadManager by lazy { ModelDownloadManager(appContext) }
    val decodeBreadcrumb: NativeDecodeBreadcrumb by lazy { NativeDecodeBreadcrumb(appContext.filesDir) }
    val transcriber: OnDeviceTranscriber by lazy { OnDeviceTranscriber(decodeBreadcrumb) }
}
```

Every ViewModel takes its dependencies as constructor parameters and is built by a
`companion object Factory` using `viewModelFactory { initializer { ... } }`;
`MainActivity`, `RecordingForegroundService` and `TranscriptionService` read the
same container off `application`. No DI framework — the container pattern from
the Android architecture guide, which is a class with some properties.

Everything is `by lazy`, so process start still does no database or JNI work, and
`System.loadLibrary` now happens when a decode is about to run rather than when
someone opens the Library.

Two dead fields went with it: `LibraryViewModel` built a `ModelDownloadManager`
and an `OnDeviceTranscriber` it never used — the second of which is what pulled
the whisper library in on the Library thread.

## Evidence

`check.sh` OK (debug, release and lint), `test-fast.sh` OK.

## Device verification

Nothing Phone 2, fresh install, every screen exercised on the container build:
onboarding downloaded and verified the model
(`model_download_finished ... elapsedMs=14618`, `model_verified ... elapsedMs=146`),
a 30-second capture was recorded and stopped, transcribed to 14 segments across
2 voices (`transcribe_finished outcome=succeeded segments=14 elapsedMs=10534`),
opened in the session sheet, deleted from it, and Settings rendered with the
model card reading "Ready". No `FATAL`/`AndroidRuntime` lines.
