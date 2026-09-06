# ARC-014 — Every ViewModel builds its own dependencies

- **Severity:** medium
- **Status:** open
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
