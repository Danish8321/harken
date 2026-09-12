# ARC-062 — A transcript row arriving can undo the pause tap and swallow a toast

- **Severity:** medium
- **Area:** `ui/SessionSheetViewModel.kt`
- **Status:** fixed

## Problem

`SessionSheetViewModel.load` builds the next UI state by copying the current one, and does it
on a different thread from the one that writes it:

```kotlin
combine(
    repository.observeSession(id),
    repository.observeSegments(id),
) { session, segments ->
    ...
    _uiState.value.copy(
        title = ...,
        ...
        playbackDurationMs = _uiState.value.playbackDurationMs.takeIf { it > 0 } ?: (duration * 1000),
    )
}.flowOn(Dispatchers.Default)
    ...
    .collect { _uiState.value = it }
```

`flowOn(Dispatchers.Default)` puts the transform — and therefore both reads of
`_uiState.value` — on a worker thread. The `collect` runs in `viewModelScope`, which is
`Dispatchers.Main.immediate`. So this is a read-modify-write straddling two threads, and the
window between the read and the write is however long the transform takes: mapping every
segment row of the recording, running `SpeakerHeuristic.voiceCount` over them, and three
resource lookups.

`copy` carries forward every field the transform does not name. Three of them belong to the
main thread and to nothing else:

- `isPlaying` and `positionMs`, written by `togglePlayback`, `seekTo`, `stopPlayback`, the
  `MediaPlayer` completion listener and the 200 ms ticker;
- `toast`, written by `confirm`, by every action's failure branch, and cleared by
  `toastShown`.

Whatever any of those wrote after the transform read `_uiState.value` is overwritten with the
older value when the emission lands.

## Reachability

`observeSegments` emits on every segment insert, so the sheet repaints continuously while the
recording it is showing is being transcribed — which is the state a user is most likely to sit
in, watching the transcript fill in, with the audio playing.

- **The pause that does not stick.** Tap pause: `MediaPlayer.pause()` runs, the ticker is
  cancelled, `isPlaying = false` is written. A row that was already in flight lands and puts
  `isPlaying = true` back. The player is paused and the button now says it is playing, and
  nothing will correct it — the ticker that used to repaint `positionMs` was cancelled, and
  every later emission reads the wrong value and carries it forward. The next tap on the
  button starts playback, which is the opposite of what its icon offered.
- **The toast that never appears.** `rename` fails, `confirm` writes the message; an emission
  in flight restores `toast = null` and the Snackbar never shows. The user is told nothing
  about a rename that did not happen.
- **The toast that appears twice.** `toastShown()` clears it on Main; an emission carrying the
  older non-null value puts it back and the Snackbar replays.

`positionMs` is the benign one: the ticker repaints it 200 ms later, if it is running.

## Related but not this

UI-040 is the `playbackDurationMs` line in that same `copy` — reopening the sheet on a
different session showed the previous one's duration. It was fixed by having
`stopPlayback` zero the field and the transform only replace a nonzero value. That rule is
correct and stays; what is wrong is that it reads the field from the wrong thread, so a
duration the decoder had just reported could be read as still zero and overwritten with the
session row's whole-second figure.

ARC-061, in the same package and the same tick, is the other half of "a ViewModel's state
writes are not safe against what else is writing them" — there it was a cancelled coroutine
publishing, here it is a stale snapshot.

## The fix

Split the state by who owns it. The transform now produces a `SessionContent` — only the
fields a database emission owns — and cannot read `_uiState` at all, because the type it
returns has nowhere to put playback or a toast. The collector applies it:

```kotlin
fun MutableStateFlow<SessionSheetUiState>.applyContent(content: SessionContent) =
    update { it.withContent(content) }
```

`update` is a compare-and-set retry, so an emission whose read is invalidated by a main-thread
write between the read and the set retries against the newer value rather than overwriting it.
UI-040's rule moves inside `withContent`, where `playbackDurationMs` is read off the state
being written rather than one captured on the worker thread.

## Evidence

Fix applied as designed: `combine` now produces a `SessionContent` (only the fields a database
emission owns — no `isPlaying`/`positionMs`/`toast`) and the collector applies it via
`_uiState.update { it.withContent(content) }`, a compare-and-set retry against whatever Main
last wrote. UI-040's rule moved into `withContent`, reading `playbackDurationMs` off the state
under CAS rather than a worker-thread snapshot.

`.claude/scripts/check.sh` (ktlintCheck, compileDebugKotlin/compileReleaseKotlin, lint) — OK.
`.claude/scripts/test-fast.sh` (testDebugUnitTest) — OK.
