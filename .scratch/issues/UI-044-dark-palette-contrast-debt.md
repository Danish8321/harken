# UI-044 — Six colour roles sit below WCAG AA, five of them only in dark

- **Severity:** medium
- **Status:** fixed
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
| ~~`stateError` on `card`~~ | ~~**2.69**~~ | ~~5.18~~ | fixed below — now `errorInk`, 5.16 / 6.50 |
| ~~`stateError` on `screenBg`~~ | ~~**3.42**~~ | ~~4.77~~ | fixed below — now `errorInk`, 6.57 / 5.98 |
| ~~`textSecondary` on `pillTrack`~~ | ~~**3.48**~~ | ~~4.80~~ | fixed below — `pillTrack` #414851, 4.73 / 4.80 |
| ~~`textSecondary` on `card` / `navBg`~~ | ~~**4.18**~~ | ~~5.83~~ | fixed below — now `#B4BAC1`, 5.24 / 5.83 |
| ~~`accent` on `card`~~ | ~~**4.45**~~ | ~~**4.49**~~ | fixed below — light `accent` 4.90, dark split into `accentInk`, 4.80 |
| ~~`onAccent` on `accent`~~ | ~~6.89~~ | ~~**4.12**~~ | fixed below — light `accent` #836E46, 4.50 |

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

## Resolution so far

Options 1, 2 and 3 are done, and option 3 was reversed on the way: the accent was left
alone deliberately, and measuring it properly showed that leaving it alone was what kept
three pairs short. Five of the six rows are fixed. One remains, so this stays open.

`errorInk` is the split: "failed" as ink on a neutral surface, the counterpart `success`
already is for `stateDone`. Dark #FF9E93 (5.16:1 on `card`, 6.57:1 on the ground, against
2.69 and 3.42), light #AE2F24 (6.50 and 5.98). `stateError` keeps both its values and
stays the fill, so `SaveStatusCard`'s failed state and every other filled error surface
look exactly as they did.

Taking it are the five places error was painted as text or icon — the Library's failure
reason, the onboarding error, and Settings' model-download, backup-cancel and export-failed
lines — plus Material's `error` role, which is read both ways: as a fill under `onError`
and as ink for an icon on a plain surface. `onError` stays `stateErrorFg` and still pairs
with it (9.15:1 dark, 6.50:1 light).

One more pair surfaced while measuring, and is fixed with it: `onErrorContainer` was
`stateErrorFg` over `errorContainer` — the fill at 18% over the surface — which is 2.03:1
in dark and 1.31:1 in light. Reading the ink there instead gives 4.52 and 4.96. The test
now composites the container rather than measuring against the fill, which is what hid it.

Option 2 is the second: dark `textSecondary` lifts #A0A6AD -> #B4BAC1, which takes the card
and nav pair from 4.18 to 5.24 and the chip label from 3.48 to 4.37. It costs a step of the
ink hierarchy and the ticket did not say so: `text` (#D1C9BE) to `textSecondary` was 1.50:1
and is now 1.19:1, so primary and secondary separate on hue and weight more than on
brightness. Both are recorded on the token itself. `#A0A6AD` came off UI-024's reference
strip, so the strip and the palette now disagree by one swatch — the comment above the dark
palette says which and why.

Option 3 is the third, and it is the option the ticket had originally ruled out. Dark
`pillTrack` goes #464D56 -> #414851. It was the same hex as `cardBorder`, which is why the
fix reads as a shade rather than a colour: four roles paint `textSecondary` on it — the
Library search field, the "Transcribed" chip, the inactive segmented-button label and the
unchecked switch thumb — and all four read 4.37:1. They now read 4.73:1.

The fill pays for that, and the ticket is the place to say so: the track drops from 1.20:1
to 1.11:1 against `card` and 1.53:1 to 1.41:1 against the ground, which is not enough to
hold a shape. So the shape moved to the edge. Every one of those four now draws a 1dp
`cardBorder` outline — the FilterChip and the segmented button already did — and
`cardBorder` on `card` is the same 1.20:1 the fill used to carry. The definition is
conserved; it just lives on the outline now. `StatusPill` took a `border` parameter
defaulting to transparent, so the sage "Transcribing" chip is unchanged.

The fourth is the light accent: #8A744A -> #836E46. Three pairs at once, and no foreground
fixed any of them, because two of the three are the accent reading *as text on a surface*
rather than something reading on the accent. `onAccent` on `accent` 4.12 -> 4.50, `accent`
on `card` 4.49 -> 4.90, `accent` on `screenBg` 4.12 -> 4.51. Pure white as `onAccent` was
measured and rejected: 4.49, and it leaves the other two untouched. Dark's #BFA789 is not
touched, so the two lightnesses of the one tan still read as one hue.

Option 5 closes the last one, and it is option 1's shape a second time. `accent` on `card`
in dark is 4.45:1 — but the pair was mis-stated in this ticket twice over. It is not one row
across both themes (the light half closed with option 4), and it is not one reading: `accent`
in dark is overwhelmingly a *fill* — the record button, the waveform bars, the active nav
tab, the selected chip — all large surfaces, all well past the 3:1 a UI component needs.
4.45 is only a problem where the accent is *text*.

The first survey of that said one site, the Library's bold search-match highlight, and that
was wrong: Material renders every `TextButton` label in `colorScheme.primary`, and the app
has eight — the import 500 MB confirmation and its cancel, the delete dialog's Keep, Add
tag, the error-state dismiss and retry. All of them are label-sized accent text on a card
or a dialog surface.

So `accent` splits into a fill and an ink, exactly as `stateError` split into
`stateError`/`errorInk` in option 1. `accentInk` is #C5AE91 in dark — 4.80:1 on card,
6.12:1 on the ground, against the fill's 4.45 and 5.66 — and the same #836E46 as the fill
in light, where option 4 already fixed the ink reading. Material's `primary` takes the ink
for the reason `error` does: the role is read both ways and the ink is legible in both
(`onPrimary` on it is 7.45:1). `primaryContainer` stays the fill.

Moving `accent` itself was the obvious alternative and is the one thing that could not be
done. The same hex is painted into `res/drawable/ic_launcher_foreground.xml` and
`LiveUpdateNotification.RECORDING_ACCENT`, neither of which this palette reaches, so a
shift here would have left the launcher mark and the recording notification on the old tan
— the brand split across three surfaces to fix a label. It would also have put the palette
a second swatch away from UI-024's reference strip.

The visible cost is that a filled Material `Button`'s container is now `accentInk` while
the record button beside it stays `accent`. That is a six-step lift on one channel and is
the thing the on-device pass should look at.

## Verification

- `ProtoContrastParityTest` holds every text pair at the full 4.5 now. One assertion is
  still below it — `accent` on `card`, at its measured 4.45 — and it is deliberately left
  there rather than raised or deleted: that pair is a fill, whose real bar is 3:1, and the
  floor stops a future re-palette dropping it further. Two assertions were added for the
  new role (`accentInk` on `card` and on `screenBg`, both at 4.5) and one for the Button
  reading (`onAccent` on `accentInk`).
- Option 2, falsified: putting #A0A6AD back fails `secondary text is legible on every
  surface it is painted on` and nothing else (157 tests completed, 1 failed).
- Option 3, falsified separately from the accent so each change answers for itself:
  putting #464D56 back fails `secondary text is legible on every surface it is painted on`
  and nothing else (5 tests completed, 1 failed). Putting #8A744A back fails `every status
  fill carries a foreground that survives on it` and `status colours used as ink, not as
  fill, survive on the surfaces under them`, and nothing else (5 tests completed, 2
  failed) — two tests because the accent is asserted in both roles.
- `bash .claude/scripts/test-fast.sh` green with both in place.
- Option 5, falsified: pointing `accentInk` back at the fill's #BFA789 fails `status
  colours used as ink, not as fill, survive on the surfaces under them` and nothing else
  (5 tests completed, 1 failed).
- `bash .claude/scripts/test-fast.sh` after any palette change.
- On-device dark-theme pass over a failed transcription in the Library, which is what
  pair 1 is about. Done: the failure reason measures #FF9E93 on the card's #3C414A on
  device 'AIN065' in Dark, and so do `SessionSheet`'s delete icon and the delete
  dialog's filled button, which read the same role through Material's `error`. See
  UI-042's on-device pass.
- **Owed: an on-device pass for options 2, 3 and 4**, deferred because phone work is off
  for now. Each is the kind of change a measurement can call fine and an eye can call
  wrong, and each has a specific thing to look at:
  - option 2 — every meta line and nav label in dark, for whether the 1.19:1 step to
    `text` reads as flat;
  - option 3 — the Library search field and the unchecked Settings switches in dark, for
    whether the `cardBorder` outline really does carry the shape the fill gave up;
  - option 4 — the light theme generally, for whether the deeper tan still reads as the
    same brand as dark's;
  - option 5 — a screen with a filled `Button` and the record button in view at once, for
    whether `accentInk` and `accent` read as one tan; and any `TextButton` label in dark.

**This ticket is fixed, not verified.** All six rows are closed and `test-fast.sh` is
green, but every one of those four on-device passes is still owed.
