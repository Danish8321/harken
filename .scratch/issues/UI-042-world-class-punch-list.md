# UI-042 — World-class UI/UX punch list (senior design audit, 2026-09-09)

- **Severity:** medium
- **Status:** open
- **Area:** `ui/RecordScreen.kt`, `ui/LibraryScreen.kt`, `ui/SettingsScreen.kt`,
  `ui/OnboardingScreen.kt`, `ui/theme/Theme.kt`, `ui/theme/Organic.kt`,
  `ui/components/HarkenSurfaces.kt`, `ui/components/HarkenStates.kt`

## Problem

Full senior UI/UX design review of the app (theme, four main screens,
shared components) against the intent stated in ADR-0010/ADR-0013.
Verdict: the individual pieces (motion tokens, ink role, the record
button's shape-morph, documented ADRs) are already above the bar of a
typical solid app. What's missing to read as distinctive/world-class is
**consolidation**, not new ideas — two parallel design systems (type,
shape) are still composed side by side rather than fully migrated, and
motion/haptics that exist as a rich vocabulary on RecordScreen don't
extend to the equivalent moments on Library/Settings/SessionSheet.

### 1. Two type systems coexist

`MaterialTheme.typography.*` tokens (SessionSheet, HarkenStates,
HarkenSurfaces) vs. raw `fontSize =` literals routed through
`ProtoColors`/`ProtoBodyFont` in RecordScreen/LibraryScreen/
SettingsScreen/OnboardingScreen. Fractional sp literals recur
(`14.5.sp`, `14.5f.sp` — two different syntaxes for the same value a
few lines apart in `OnboardingScreen.kt` lines 216/236) and read as
guesswork rather than scale steps. `Theme.kt`'s `titleMedium` is
17sp/Bold but `LibraryScreen.kt`'s `SessionCard` title hand-writes
`fontSize = 15.sp, fontWeight = Bold` instead of referencing it — same
role, drifting value, nothing enforces they stay in sync. That title is
also under-differentiated from its own secondary meta line (15sp/Bold
vs. body — only ~3sp/1 weight step apart) despite being the primary
scan target on a list screen.

### 2. Two shape systems coexist

`HarkenShapes.large` = `26.dp`, but the "primary card" role is
independently hardcoded as `RoundedCornerShape(24.dp)` at 4+ call sites
(`LibraryScreen.kt` SessionCard/SearchResultCard, `SettingsScreen.kt`
SettingsCard, `RecordScreen.kt` cap-warning row/MeterCard) — two
numbers a few px apart for what's visually one role, invisibly. Same
pattern for pills: `LibraryScreen.kt`'s `SearchField` hardcodes
`RoundedCornerShape(999.dp)` inline instead of the already-imported
`PillShape` used two lines below for `FilterChipProto`.

### 3. Motion is rich where it reacts, absent where it's ambient

`HarkenMotion`'s spatial/effects split is applied consistently
everywhere checked — genuinely good discipline. But: the
transcribing→transcribed chip swap in `LibraryScreen.kt`'s
`SessionCard` (~lines 515-534) is a hard `if/else`, not an
`AnimatedContent` — the one moment in Library that represents a
recording actually finishing has zero motion, while `RecordScreen`'s
`SaveStatusCard` gets a full icon-morph treatment for the equivalent
"did this work" moment. Beyond the once-per-launch splash trace,
there's no ambient/idle motion anywhere — the record button's
shape-morph is only ever a reaction to state change, never texture at
rest.

### 4. Haptics stop at RecordScreen

`RecordScreen` differentiates haptics meaningfully (`Confirm`/`Reject`
on save outcome, `LongPress` on start, `TextHandleMove` on pause/stop).
Delete-confirm (`SessionSheet`), transcription failure/retry
(`LibraryScreen`), and export failure (`SettingsScreen`'s
`BackupCard`) are equivalent "something happened" moments and are
silent.

### 5. Latent contrast-parity risk

`ProtoColors`' light vs. dark values were not audited directly in this
pass (out of scope of the files read) — the same bug class just fixed
in `InkSurface`/`SessionSheet` (a component eyeballed in one theme,
silently wrong in the other) could recur anywhere in the four Proto
screens, since they source color from `ProtoColors` rather than
`MaterialTheme.colorScheme`.

### 6. Smaller, standalone findings

- `PauseButton` (`RecordScreen.kt` ~669-689) is a bare
  `Box.clickable` with no explicit pressed-state feedback, unlike
  `RecordButton`'s deliberate 0.92f press-scale — sits on the same
  ink-dark card, unverified it reads correctly under a fast tap.
- `InkSurface` requires every caller to remember `LocalInk.current.onInk`
  by hand (this is exactly the bug already fixed once this session) —
  nothing stops the next component built on `LocalInk.current.ink`
  directly from repeating it.
- `SessionCard`'s duration bar
  (`barColor = if (transcribing) c.success else c.textSecondary`)
  carries the transcribing/not-transcribing signal through color alone
  on the bar itself — mitigated by the adjacent chip, but is the one
  place found that arguably doesn't follow `StatusChip`'s own stated
  house rule ("always a shape or icon plus a WORD — never colour
  alone").

## Fix

Prioritized punch list, in order:

**High — consistency debt that compounds:**
1. Unify card shape system: replace hardcoded `RoundedCornerShape(24.dp)`
   in `LibraryScreen.kt`, `SettingsScreen.kt`, `RecordScreen.kt` with
   `HarkenCard` or a reference to `MaterialTheme.shapes.large`.
2. Unify typography: migrate raw `fontSize =` literals in the four
   Proto screens onto `MaterialTheme.typography.*`, extending
   `HarkenTypography` with any missing steps instead of adding more
   fractional sp one-offs.
3. Fix `SearchField`'s inline `RoundedCornerShape(999.dp)` to use
   `PillShape`.

**Medium — motion gaps:**
4. `AnimatedContent` on `SessionCard`'s transcribing→transcribed chip
   swap, reusing the pattern already proven in `SaveStatusCard`.
5. One ambient/idle signature beyond the launch splash — candidate: a
   slow, near-imperceptible idle pulse on the record button's resting
   shape, gated by `LocalReducedMotion`.
6. Haptics parity on delete-confirm, transcription failure/retry, and
   export failure, reusing the `Confirm`/`Reject` vocabulary already
   established in `RecordScreen`.

**Lower — hardening:**
7. Diff `protoColors(light = true)` vs. `protoColors(light = false)`
   directly for contrast parity across every token.
8. Pressed-state feedback on `PauseButton` matching `RecordButton`'s
   press-scale.
9. Formalize "ink surface must set contentColor" — either a wrapper
   that threads `LocalContentColor` automatically for anything composed
   inside `InkSurface`, or a doc comment on `LocalInk`'s declaration
   warning direct callers.

## Verification

- `bash .claude/scripts/check.sh` (or repo-equivalent gradle
  `ktlintCheck` + `assembleDebug`, per this repo's actual gate)
- `installDebug` clean install; on-device side-by-side comparison of
  Library/Settings/Record card corner radii and type scale before/after
  item 1-2
- Exercise a transcription completing in Library to confirm item 4's
  `AnimatedContent` fires
- TalkBack/manual pass unaffected (no accessibility regressions from
  the shape/type consolidation)

## Resolution

Not yet started — filed from audit findings only.
