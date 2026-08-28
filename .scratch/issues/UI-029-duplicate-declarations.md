# UI-029 — Duplicated declarations left behind by the review

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/theme/Type.kt`, `ui/theme/Waveform.kt`, `ui/theme/ProtoColors.kt`,
  `ui/theme/Theme.kt`, `ui/RecordScreen.kt`, `ui/SplashScreen.kt`,
  `.scratch/issues/UI-009`, `UI-010`, `UI-011`

## Problem

Three of the four follow-ups UI-028 recorded but did not do. All three are
the same shape: something written twice, agreeing only until one copy is
edited.

1. **Waveform constants duplicated** between `SplashScreen.kt` and
   `RecordScreen.kt`. UI-023 was titled "match splash waveform" and did it
   by copy-paste — bar count, width, shape, min/travel height, travel
   speed and phase step all written out twice. Four rounds of tuning
   afterwards went through both files by hand.
2. **`ProtoColors.kt` owned the fonts and easings**, and `Theme.kt`
   declared its own identical copy of Space Grotesk and Figtree for the
   `Typography`. A colour file holding the type stack, and the type stack
   holding still another copy of itself.
3. **UI-009, UI-010, UI-011 assert states the code no longer has** —
   UI-009/010 describe a palette UI-020/UI-024 deleted, UI-011's splash
   was rewritten three times. All still read as current. This is the same
   failure mode that made `brand-guidelines.md` dangerous (UI-028 #4): a
   confident stale document.

Not done here: backfilling ticket files for UI-019..UI-027. Nine tickets
reverse-derived from their own diffs would look authoritative without
being so — the exact thing this ticket's item 3 is cleaning up.

## Fix

1. New `ui/theme/Waveform.kt`: `HarkenWaveform` object holding the shape
   and the phase clock, with `barHeight(t, i, moving)`. Both call sites
   rewired; `RecordScreen` passes `moving = !reduced`, `SplashScreen`
   applies only its own enter/exit envelope on top. The live meter is
   deliberately excluded — real input amplitude, not this clock.
2. New `ui/theme/Type.kt`: one declaration of `ProtoHeadingFont`,
   `ProtoBodyFont`, `ProtoMonoFont`, `ProtoEaseOut`, `ProtoOvershoot`.
   `ProtoColors.kt` is colour only; `Theme.kt` aliases the shared vals.
3. UI-009, UI-010, UI-011 each given a superseded note naming the commit
   that replaced them; statuses now `fixed, superseded` /
   `fixed, partly superseded`.

## Verification

- `bash .claude/scripts/check.sh` — the font split is a pure move, so any
  miss is a compile error.
- `installDebug --rerun-tasks`, on-device screenshots of splash and
  Record: the waveform extraction's success criterion is *no visual
  difference*, and both render the same crest positions and density as
  before.
