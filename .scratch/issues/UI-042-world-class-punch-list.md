# UI-042 — World-class UI/UX punch list (senior design audit, 2026-09-09)

- **Severity:** medium
- **Status:** resolved
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

All nine items done. Item 7 turned up six real contrast failures, which are their own
ticket (UI-044) rather than a palette change made in passing here.

**1 and 3. One shape system.** Every hardcoded `RoundedCornerShape(24.dp)`
standing in for the primary-card role now reads `MaterialTheme.shapes.large`
(`LibraryScreen`'s SessionCard and SearchResultCard, `SettingsScreen`'s
SettingsCard, `RecordScreen`'s cap-warning row and SaveStatusCard), and the
meter card's `30.dp` reads `shapes.extraLarge`. The three inline
`RoundedCornerShape(999.dp)` pills in `LibraryScreen` (SearchField,
FilterChipProto, the status chip) use `PillShape`. `RecordScreen` no longer
imports `RoundedCornerShape` at all. The 2.dp progress-bar clips in
`SettingsScreen` are deliberately left alone — a 4dp-tall bar's radius is not
the card role.

**2. One type system.** Every raw `fontSize =` in the app is gone —
`RecordScreen`, `LibraryScreen`, `SettingsScreen`, `OnboardingScreen`,
`AppNav`, `SplashScreen` and `HarkenStates` all name
`MaterialTheme.typography.*` instead, and the `ProtoBodyFont` /
`ProtoHeadingFont` / `FontWeight` / `sp` imports they only needed for that
went with them. `Theme.kt` already pointed `HeadingFont`/`BodyFont` at the
Proto families, so this changes no typeface.

Three decisions worth recording:

- `headlineSmall` moved 24sp -> 26sp. It is the screen-title step, and the
  titles migrating onto it were hand-written at 26sp (Library, Settings) and
  28sp (each onboarding page, the splash-to-record wordmark). One value
  between the two beats three literals. `HarkenStates`' empty-state title
  rides along.
- The `SessionCard`/`SearchResultCard` title takes `titleMedium` (17sp/Bold),
  not the 15sp/Bold it hand-wrote — which also closes the "under-
  differentiated from its own meta line" half of finding 1.
- Where a family or weight is load-bearing the ramp supplies only the size,
  via `.copy()`: the mono elapsed-time numeral and meter footers
  (`ProtoMonoFont`), and the tab label's `includeFontPadding = false`.

Fractional sp (`14.5.sp`, `14.5f.sp`, `13.5.sp`, `12.5.sp`) is now absent
outside `Theme.kt`'s own `letterSpacing`, and so is every size below 12sp —
`Theme.kt:67` claimed "nothing below 12sp" while `LibraryScreen` rendered
10sp match counts and tags.

Verified: gradle `ktlintCheck` + `assembleDebug` clean. Not yet installed —
the device disconnected before `installDebug` ("No connected devices!"), so
the on-device side-by-side in the checklist below is still outstanding.

**4. The chip swap is a transition now.** `SessionCard`'s trailing slot ran
through four booleans and a `Triple` of chip colours, and swapped between a
Transcribe button and a chip with a hard `if/else`. It is one `CardAction`
value (Transcribe / Transcribing / Transcribed) through `AnimatedContent`,
on the vocabulary `SaveStatusCard` already proved: `scaleIn`/`scaleOut` on
the spatial spring, fade on effects, `EnterTransition.None` under reduced
motion, and a `SizeTransform` because the button and the chips are
different widths — without it the row snaps to its new width under a fade.
The two chips are one `StatusPill`.

`R.string.library_chip_kept_on_device` went with it. Its branch was already
unreachable: a failed session takes the Transcribe button, so the chip that
said "Kept on device" could not render, and the string had no other reader.

**5. One ambient signature.** At rest the record button breathes — 1.5% of
88dp over a four-second cycle, `rememberInfiniteTransition` reversing a
2000ms tween, multiplied into the existing press-scale. Gated on the shape
resting rather than on `recording`: once the morph starts, it and the meter
carry the motion and a second rhythm underneath them competes. Frozen under
reduced motion, following `LiveDot`'s pattern of building the transition
unconditionally and reading its value only when motion is allowed.

**6. Haptics reach the other three screens.** Delete-confirm in
`SessionSheet` fires `Confirm` as the recording is purged — the sheet leaves
with it, so nothing on screen can report the outcome afterwards. Export in
`SettingsScreen` fires `Confirm` on `Finished` and `Reject` on `Failed`.
Transcription in `LibraryScreen` fires `Confirm` when a session settles to
`Succeeded` and `Reject` when it settles to `Failed`, plus the same
`LongPress` that acknowledges a recording starting when Transcribe is
tapped.

Both of the state-watching ones compare against the previous value and seed
that value from the current one, so arriving at a screen that already holds
a finished export or a failed row is silent — only a transition fires. The
Library additionally requires the previous status to have been `Running` or
`Pending`, so a delete or a rename cannot read as a completion.

**7. The palettes were diffed, and six pairs fail.** Every
foreground/background pair the app actually paints, measured in both
palettes: `ProtoContrastParityTest`. The worst is `stateError` on `card` at
2.69:1 in dark — the failure reason under a recording's title, which is the
one place the app explains what went wrong. Full table and the options for
fixing it are UI-044; the palette is not re-picked here, because every
option is a visible design decision and this palette has recorded
provenance (UI-020, UI-024).

The test holds each failing pair at its measured floor rather than at AA, so
a re-palette that makes one worse fails the build. Those floors are the
ticket's, not a licence: raising one to hide a regression is the same as
deleting the assertion.

**8. `PauseButton` has the press-scale `RecordButton` has.** Same 0.92f on
`spatialFast`, off its own `MutableInteractionSource`. It sits on the
ink-dark card where the ripple barely reads, and its icon only swaps once
the recorder has actually paused, so until now a tap that landed and a tap
that missed looked identical.

**9. The ink contract is written down.** `LocalInk` now carries it: painting
`ink` obliges the same composable to set `onInk` as the content colour,
because `ink` is outside `colorScheme` and `Surface` derives nothing from
it — which is how an invisible play button on a dark card got written
twice. `InkSurface` remains the thing to compose instead. Reading a colour
off the local for a single `tint` is explicitly fine; it is painting the
surface that carries the obligation.

Verified: `bash .claude/scripts/check.sh` -> `== check: OK ==` and
`bash .claude/scripts/test-fast.sh` -> `== test-fast: OK ==` (five new
contrast assertions among them).

## On-device pass

Device 'AIN065' (1080x2412, density override 375, so 1dp = 2.34375px), debug
build installed, app forced into Dark from Settings. Every number below is
measured off a screenshot rather than eyeballed, because "the animation looks
about right" is the claim this section exists to avoid.

**1. Card radius is one value.** Least-squares fit of the arc along a
`SessionCard`'s top-left corner: r = 61.4px = **26.2dp**, i.e.
`MaterialTheme.shapes.large` (26dp), not the 24dp literal it replaced.

**2. Type scale.** The card title's ascender-to-descender band measures 37px
against titleMedium's 17sp em box of 39.8px; the meta line under it measures
26px. The two are a clear step apart, which is the half of finding 1 the
migration was meant to close.

**4. The chip swap fires.** Tapped Transcribe with
`animator_duration_scale 10` and sampled 14 frames: frame 1 the Transcribe
button fading and scaling out, frame 2 a small "Transcribing" pill with its
spinner scaling in, frame 3 both chips crossfading mid-scale, frames 4-5 the
"Transcribed" pill growing to full width, then settled. The width change is
carried, not snapped — that is the `SizeTransform` doing its job.

**5. The breath is real and is the right size.** Sampled the record FAB across
a full cycle at `animator_duration_scale 3`. Its measured body oscillates
between 201.7px and 204.5px with one turning point per direction — **1.37%
peak to peak** against the 1.5% the code asks for, the gap being the strict
colour threshold clipping the anti-aliased rim. 88dp on this device is
206.25px, which is what the resting frames measure.

**6. Haptics.** `dumpsys vibrator_manager`'s history names the constant each
request carried, so each one is identifiable: LONG_PRESS is 0, CONFIRM 16,
REJECT 17.

- Transcribe tapped -> `constant=0` at 20:26:36.342, then `constant=16` at
  20:26:36.681 when the session settled to Succeeded. Reproduced at
  20:50:05.445 / 20:50:05.767.
- Delete confirmed in `SessionSheet` -> `constant=16` at 20:33:29.224.
- Export finished (12 recordings, 1.4 MB) -> `constant=16` at 20:39:20.591.
- The seeding holds: entering the Library while a row already read Failed
  fired nothing, and the second Transcribe (Failed -> Running -> Succeeded)
  fired exactly one CONFIRM, not one per recomposition.

Not verified: the two REJECT paths (transcription failed, export failed).
Neither can be provoked from the UI on a device where the model works and the
export succeeds, and the two ways to force them — renaming a session's `.wav`
aside under `run-as`, or removing the model — were not taken (the first was
refused by the permission classifier; the second would leave the phone without
a working speech model). Their sibling CONFIRM paths in the same
`LaunchedEffect` both fire, so what is untested is the branch, not the wiring.

**8. The pause press.** With the press held, `PauseButton`'s circle measures
131px against 141px at rest — **0.929**, sampled mid-spring against the 0.92
target.

**errorInk in dark, on hardware.** Forced a Failed row honestly: started a
transcription, force-stopped the app mid-run, and let `SessionDao`'s orphan
sweep flip the leftover 'Running' row on the next launch. The failure reason
under the title reads **#FF9E93** on the card's #3C414A — the dark `errorInk`
from UI-044, exactly as the palette declares it, at the 5.16:1 that replaced
2.69:1. `SessionSheet`'s delete icon and the delete dialog's filled button
(Material's `error` role) measure the same value. The row was transcribed
again afterwards and is back to Succeeded.

`logcat` across the whole pass: no crash, no app-tagged error. The two
entries mentioning the package are the framework's own
(`dispatchAppVisibility` on the window I force-stopped, and a notification
preference lookup for the stopped package).
