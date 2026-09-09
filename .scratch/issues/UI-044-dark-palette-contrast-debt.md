# UI-044 — Six colour roles sit below WCAG AA, five of them only in dark

- **Severity:** medium
- **Status:** open
- **Area:** `ui/theme/ProtoColors.kt`

## Problem

UI-042 item 7 asked for the two Proto palettes to be diffed directly for contrast parity,
on the reasoning that they are two independent lists of hex values and nothing compares
them — so a component eyeballed in one theme can be silently illegible in the other. The
diff was run over every foreground/background pair the app actually paints, in both
palettes, and is now a unit test (`ProtoContrastParityTest`). Six pairs are below the
4.5:1 WCAG AA asks of normal text:

| pair | dark | light | where it shows |
| --- | --- | --- | --- |
| `stateError` on `card` | **2.69** | 5.18 | the failure reason under a recording's title |
| `stateError` on `screenBg` | **3.42** | 4.77 | same text, on a screen without a card |
| `textSecondary` on `pillTrack` | **3.48** | 4.80 | the "Transcribed" chip's own label |
| `textSecondary` on `card` / `navBg` | **4.18** | 5.83 | every card meta line, every nav label |
| `accent` on `card` | **4.45** | **4.49** | accent-coloured text and icons on a card |
| `onAccent` on `accent` | 6.89 | **4.12** | any label on an accent fill |

Everything else clears AA in both themes, including all three status fills with their own
foregrounds, `success` as ink, and the ink surface.

`stateError` on `card` in dark is the real defect: 2.69:1 is the one place the app
explains why a transcription failed, and in dark theme it is barely there. The rest is
debt — 4.18 and 4.45 are close enough that they are invisible until measured, and 3.48
clears the 3:1 a UI component needs but not the 4.5:1 its text size asks for.

Two of the numbers were reasoned about before, on the wrong background. UI-024 records
`textSecondary` (#A0A6AD) at 5.3:1 — which is true on `screenBg`, and the value it
replaced #828A94 for. Cards are lighter than the ground, so the same token reads 4.18 on
the surface most of the app's secondary text actually sits on.

## Fix

Not attempted here, because every option is a palette decision with a visible result, and
the palette has recorded provenance (UI-020's single accent, UI-024's four-swatch
reference strip):

1. `stateError` splits into a fill and an ink, the way `stateDone`/`success` already do —
   a lighter red for text on a surface, the current one kept for fills with `stateErrorFg`
   on them. Fixes the worst pair without touching what any filled error card looks like.
2. `textSecondary` in dark lifts from #A0A6AD toward #B4BAC1 (4.18 -> ~5.1 on `card`),
   which moves every meta line and nav label in the app a step brighter.
3. `accent` is left alone. 4.45/4.49 is a rounding from AA, and it is the brand primitive.

## Verification

- `ProtoContrastParityTest` holds each of the six at its measured floor today, so a
  re-palette that makes one worse fails `test-fast.sh`. Those floors may only be raised as
  this ticket is closed — raising one to hide a regression is the same as deleting the
  assertion.
- `bash .claude/scripts/test-fast.sh` after any palette change.
- On-device dark-theme pass over a failed transcription in the Library, which is what
  pair 1 is about.
