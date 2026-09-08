# ARC-050 — The waveform-bar animation loop bypasses ARC-025's lifecycle fix

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/RecordScreen.kt` (`LiveMeter`)

## Problem

`LiveMeter` (`RecordScreen.kt:611-620`):

```kotlin
val amplitude by RecordingState.amplitude.collectAsStateWithLifecycle()
LaunchedEffect(Unit) {
    while (true) {
        kotlinx.coroutines.delay(90)
        bars.removeAt(0)
        bars.add(amplitudeToBarHeight(amplitude))
    }
}
```

ARC-025 (fixed) specifically named this meter as "the one that matters most" and fixed it
by switching the flow read to `collectAsStateWithLifecycle` — which correctly freezes
`amplitude` while backgrounded. But this separate `LaunchedEffect(Unit){ while(true) }`
loop isn't gated by lifecycle at all: it keeps firing every 90ms for the whole length of a
recording (up to 3 hours) regardless of app visibility, mutating the `bars`
`mutableStateListOf` every tick and forcing `bars.forEach`'s `Row` to keep recomposing
off-screen. Same battery/recomposition cost ARC-025 was written to eliminate, reintroduced
through a different mechanism than the one it audited.

## Fix

Gate the loop on the lifecycle — e.g. wrap it in `repeatOnLifecycle(Lifecycle.State.STARTED)`
inside the effect — so it stops ticking while the app is backgrounded.

## Found by

Third fresh full-repo audit, 2026-09-08.

## Resolution, 2026-09-08

Wrapped the loop's body in `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)`
inside a `LaunchedEffect(lifecycleOwner, amplitude)`, using `LocalLifecycleOwner.current`.
`repeatOnLifecycle` cancels and re-launches the block across STARTED transitions on its
own, so backgrounding the app now stops the 90ms tick instead of leaving it running.

No dedicated test: this is a Compose animation loop gated on `ProcessLifecycleOwner`-style
state with no existing Compose UI test infrastructure in this repo (no
`createComposeRule` usage anywhere) — adding one is out of scope for this fix. Verified by
reading the `repeatOnLifecycle` contract (cancels its block below `STARTED`, relaunches
above it) against the exact call shape already proven correct for `collectAsStateWithLifecycle`
elsewhere in this same file.

Verified: `check.sh` OK, `test-fast.sh` OK, `test-full.sh` OK.
