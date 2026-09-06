# UI-036 — The "Voice 1" label is painted in the on-accent colour, so it is invisible

- **Severity:** medium
- **Status:** fixed
- **Area:** `ui/SessionSheet.kt`

## Problem

Each transcript row labels its speaker above the text. The colour is picked by
voice:

```kotlin
color = if (segment.voiceIndex == 0) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
```

`onPrimaryContainer` is `ProtoColors.onAccent` — the ink for text sitting *on*
the accent fill, near-white in the light theme. Painted on the sheet's card
instead, "VOICE 1" is white on white: on a device it reads as a ghost behind the
segment text, and the odd rows of a two-voice transcript look broken.

Voice 2 escapes it by accident: `onSecondaryContainer` is `stateDoneFg`, which is
documented as "legible directly on card and screenBg".

Found while verifying ARC-014 on a Nothing Phone 2 — a 14-segment, 2-voice
transcript, where every voice-1 label was unreadable.

## Fix

Take the label ink from the tone that is legible on the card, per voice: the
accent itself (`primary`) for voice 1, `stateDoneFg` (`onSecondaryContainer`) for
voice 2. The filled `StatusChip` beside it keeps its container/content pair —
that one really is text on the accent.

## Resolution

The label takes the ink that is legible on the card, per voice — the accent
itself for voice 1, `stateDoneFg` (`onSecondaryContainer`) for voice 2. The
`StatusChip` beside it is unchanged: that one really is text on the accent.

## Evidence

`check.sh` OK, `test-fast.sh` OK.

## Device verification

Nothing Phone 2, fresh install, an 11-segment two-voice transcript. Light theme:
"VOICE 1" reads in the accent brown against the white card. Dark theme (Settings
→ Dark, same sheet reopened): both labels read against the dark card. Before the
fix, every voice-1 label was a white ghost behind its own segment text.
