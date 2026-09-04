# Device regression — Nothing Phone 2, 2026-09-04

Status: **all checks pass**. No open defects from this run.

Device `eece2e35` (Nothing Phone 2, `AIN065`, Android 15). Build: `app-debug.apk`
at `3656c35`. **Fresh install** (uninstall + install, no reused app state), model
re-downloaded, onboarding walked from step 1.

Gates at the time of the run: `check.sh` → `== check: OK ==`,
`test-fast.sh` → `== test-fast: OK ==`. There is no `test-full.sh`,
`contract.sh` or `e2e.sh` in `.claude/scripts/` — only `check.sh` and
`test-fast.sh` exist.

## What was fixed in this session

| Commit | Change | Device evidence |
|---|---|---|
| `af07292` | Stop reason reaches the save card; delete-dialog copy corrected | auto-stop card read "Stopped after 5 minutes of silence" |
| `907f64d` | Save card cleared when a new recording starts | card gone during the second capture |
| `ed7223e` | `SpeechSpans` — silence is never handed to whisper | 5 min of silence: **13.5 min → 13 s** |
| `3656c35` | Playback: play/pause, scrub, tap-a-segment | all four transport paths verified |

Two earlier fixes carried in from before this session and re-verified here:
`f3188be` (silence judged by chunk level, run decays) and `daf4d8a` (delete
takes the audio file with the row).

## Results

### 1. Silence auto-stop (`f3188be`, `af07292`)

System mic blocked (`cmd sensor_privacy enable 0 microphone`) so the app
receives digital silence.

- Auto-stopped on its own; WAV froze at **9,605,164 bytes = 300.16 s** against a
  300 s timeout. No ANR, no dropped frames.
- Save card read **"Stopped after 5 minutes of silence"**, not the generic
  "Saved".
- Library row: "5m 00s".

Monitor reported the stop at t=273 s because it polls every 20 s and the screen
wake costs time — the byte count, not the poll clock, is the measurement.

### 2. Transcription of silence (`ed7223e`) — the headline number

| | Before (`ab01efa`) | After (`ed7223e`) |
|---|---|---|
| Wall clock, 5 min of silence | ~13.5 min | **13 s** |
| CPU | ~75 min | not re-measured (too short to matter) |
| Segments produced | 11 × " you" across "2 voices" | **0** |
| Transcript shown | eleven fabricated lines | "No speech in this recording — nothing to transcribe." |

~62× faster, and the hallucinated segments are gone because whisper is no longer
asked about silence at all. Decoder parameters were not touched, so accuracy on
speech is unchanged by construction.

### 3. Span splitting and offset rebasing (`ed7223e`)

Deterministic fixture rather than a live recording: a 41.04 s WAV built as
**8.2 s speech → 25 s silence → 7.8 s speech** (Windows TTS at 16 kHz mono
16-bit), dropped into the app's `files/` and adopted by `RecordingRecovery`
(`Recovered 46619c3b… (41s, header repaired=false)`).

Transcript came back as **3 segments · 2 voices**:

| Offset | Text |
|---|---|
| 0:00 | " This is the first part of the regression test recording." |
| 0:04 | " The quick brown fox jumps over the lazy dog." |
| **0:32** | " This is the second part, after a long silence. Playback should seek to this line when tapped." |

The 0:32 offset is the proof that matters. The second span starts at 32 s
(33.2 s of real speech onset, minus 1 s padding, floored to the 1 s window
grid); whisper timed that segment from the start of the span it was given, so
without the rebasing in `OnDeviceTranscriber` it would have read ~0:01. The
25 s gap exceeds `MinSkippableSilenceSeconds` (10), so it split — as designed.

"2 voices" is `SpeakerHeuristic` reacting to the 25 s gap. Expected: it is a gap
heuristic, not diarization.

### 4. Playback (`3656c35`)

| Check | Result |
|---|---|
| Card renders before first tap | `Play`, `0:00`, `Playback position`, `5:00` — duration falls back to the session's own length |
| Play | position advanced to 0:06, button became `Pause` |
| Pause | froze at 0:15 and stayed there across 4 s |
| Scrub to 75 % | landed at **3:42 of 5:00** (74 %), stayed paused — seeking does not start playback |
| Audio attributes | `usage=USAGE_MEDIA content=CONTENT_TYPE_SPEECH`, `sampleRate=16000` in `dumpsys audio` |
| Tap a transcript line | tapped the 0:32 segment → playing at 0:35 three seconds later |
| End of file | rewound to 0:00, button back to `Play`, player released |
| Close the sheet | `piid` disappeared from `dumpsys audio players:` — audio stops, decoder released |

Not verified: the active-row highlight colour. `uiautomator` does not expose
composable colours, so it needs an eye on the screen or a screenshot diff.

### 5. Regression sweep

| Area | Result |
|---|---|
| Fresh install + onboarding | 2 steps, model download reached "Model ready" |
| `RecordingRecovery` | adopted the orphan WAV on next launch, dated from its mtime |
| Library counts | 2 recordings → 1 after delete, statuses correct |
| Tag add | "Field" added, `Remove the Field tag` content-desc present |
| Filter | Field → "1 recording" + FIELD chip; Ideas → "0 recordings" + "Nothing tagged Ideas" |
| Rename | "Regression test 41s" persisted and survived reopening |
| Delete | row gone, count 2 → 1, **WAV removed from disk**, dialog copy correct |
| Manual stop | plain "Saved" card, no auto-stop wording |
| Stale card | gone as soon as the next capture started |
| Live mic | 10.08 s captured, peak 3033, RMS 448 — mic unblocked and capturing |

## Device state left behind

- Microphone privacy **disabled** (mic works normally).
- `svc power stayon usb` set.
- App installed with one leftover recording (the 5 min silence WAV) plus two
  short mic tests. Harmless; wipe with an uninstall.

## Techniques worth reusing

- **Git Bash mangles `/sdcard` and `/data`** into `C:/Program Files/Git/...`.
  Use `//sdcard/...` and `//data/...` for every `adb shell`/`exec-out` path.
  A stale `u.xml` from a failed dump is what made an old ANR dialog look live
  earlier in the session — always check the dump actually rewrote.
- **`adb exec-out`, never `adb shell cat`**, for binary pulls: text-mode
  translation corrupted a 148 MB model file by ~387 KB.
- **`dumpsys audio | sed -n '/players:/,/^$/p'`** is the live player state.
  The `new player` lines above it are an event log and stay there after release.
- **`cmd sensor_privacy enable 0 microphone`** feeds an app digital silence
  without touching its code. The app then shows an "Unblock device microphone?"
  system prompt on record — dismiss with Cancel.
- **Windows TTS makes deterministic speech fixtures**:
  `System.Speech.Synthesis.SpeechSynthesizer` with
  `SpeechAudioFormatInfo(16000, Sixteen, Mono)` is exactly `WavWriter`'s format.
  Combined with `RecordingRecovery` adopting any UUID-named WAV in `files/`,
  that gives a repeatable transcription fixture with no microphone involved.
- **The screen locks mid-run** and every `uiautomator` dump then comes back
  empty, which reads as "condition met" to a naive filter. Any polling loop must
  require a known-good marker (e.g. `text="Library"`) in the dump before it
  judges anything.

## Open items for next session

1. **3-hour session cap** — closed by decision as unit-tested only (1 Kotlin +
   3 Core tests, wired at `RecordingForegroundService.kt:74`). It shares the
   stop-and-save path that the silence timeout now proves on device. If it ever
   needs a real device run, the cheap route is a debug-only `buildConfigField`
   override rather than three hours of wall clock.
2. **Active-row highlight** during playback is unverified visually.
3. **Playback does not survive the sheet closing** — by design in this slice.
   A background player (media session, notification, audio focus, headset
   buttons) was the "full player" option and was not chosen.
4. **`SpeechSpans` constants are untuned against real meetings.** 10 s minimum
   skippable silence and 1 s padding were chosen from reasoning about whisper's
   30 s window, not from measurement on long multi-speaker audio. Worth
   revisiting with a real recording before trusting them on a 3-hour capture.
5. **No `SpeechSpans` mirror in `Harken.Core`.** `SilenceDetector` exists in both
   tiers; this one is Android-only because transcription is on-device only
   (ADR-0011). If Core ever grows a transcription path, the split rule has to be
   shared rather than reimplemented.
