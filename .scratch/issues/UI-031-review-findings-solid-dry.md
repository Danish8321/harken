# UI-031 — Standards/Spec review findings: SOLID/DRY/KISS/YAGNI pass

- **Severity:** high
- **Status:** partially fixed — Standards 1,2,3,5,6,7,8,9,10 and Spec 1-7
  are fixed (see Resolution). Standards-4 (`LocalProtoColors` refactor)
  deferred as its own ticket.
- **Area:** `ui/RecordScreen.kt`, `ui/AppNav.kt`, `ui/LibraryScreen.kt`,
  `ui/SplashScreen.kt`, `ui/SettingsScreen.kt`, `ui/theme/*`,
  `recording/LiveUpdateNotification.kt`, `res/values/strings.xml`,
  `docs/brand-guidelines.md`

## Problem

Two-axis review of `master...HEAD` (38 commits), against
`~/.claude/CLAUDE.md`, `docs/brand-guidelines.md` v2.0, and
`.scratch/issues/*`, with the Standards axis weighted toward SOLID / DRY
/ KISS / YAGNI. Findings below, not yet discussed or scoped.

## Standards axis

### Hard violations

1. **Hardcoded host, contradicts brand-guidelines.md §4.** `strings.xml:13`
   `"Point it at your studio Mac"`, `strings.xml:33`
   `"Sending the recording to studio-mac"`. The guideline names this exact
   string as *"a plausible-looking lie on every device that wasn't the
   author's."* `backendLabel` is already resolved at `RecordScreen.kt:110`.

2. **Touch target below 48dp.** `LibraryScreen.kt:172` `FilterChipProto`
   has no `heightIn`; Material `FilterChip` defaults to 32dp.
   `brand-guidelines.md` §5: *"Every interactive control clears 48dp and
   carries a semantic role."*

3. **Duplicated easing — UI-029's own defect.** `SplashScreen.kt:55`
   `EnterExit = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)` is byte-identical
   to `theme/Type.kt:44 ProtoEaseOut`, which UI-029 created to hold this
   exact declaration once. `ProtoEaseOut` has no other caller.

### Judgement calls

4. **`ProtoColors` threaded as positional `c` param through ~12
   composables** (Data Clump), and 8 composables each call
   `rememberProtoColors()` independently, each constructing its own
   `AppSettings` and collecting DataStore. Theme resolution is written
   three times: `MainActivity.kt:24-28`, `ProtoColors.kt:165-169`,
   `Theme.kt:113`. Candidate fix: one `LocalProtoColors` provided by
   `HarkenTheme`.

5. **Stagger-reveal block duplicated** — `LibraryScreen.kt:141-161` and
   `SessionSheet.kt:188-201`, same 15 lines (id set, `alreadyAnimated ||
   reduced`, capped delay, `fadeIn + slideInVertically`), differing only
   in divisor/constants.

6. **Shared-axis transition spec duplicated** — `AppNav.kt:300-310` and
   `OnboardingScreen.kt:141-152`, identical `forward/enterOffset/
   exitOffset` blocks (÷4 vs ÷3).

7. **Meter card chrome duplicated** — `RecordScreen.kt:256-282`
   (`IdleMeter`) and `:399-424` (`LiveMeter`) repeat the same `meterBg` /
   30dp / 20dp / header-row / footer-row shell.

8. **Dead tokens (YAGNI).** `ProtoColors.sheetBg`, `navBorder`, `grabber`,
   `rowHighlight`, `inkFaint`, `inkMuted`, and `Type.kt:45
   ProtoOvershoot` — verified zero call sites beyond declaration and the
   two theme assignments. (`inkSubtle`/`inkStrong` are live, 4/3 sites —
   not dead.) `inkFaint` died when UI-025 moved the idle meter onto
   `accent`.

9. **KISS regression.** `SettingsScreen.kt:55` — `LazyColumn` wrapping 6
   static `item {}` blocks. No virtualization benefit; was a scrolling
   `Column`.

10. **Naming inconsistency.** `SettingsViewModel.kt:29` — `private val
    app = application`, while `LibraryViewModel`/`SessionSheetViewModel`
    use `getApplication<Application>()`.

Clean, called out explicitly by the reviewer: `Motion.kt` (reduced-motion
handled at the token, not per call site), `Waveform.kt`, the
`strings.xml` extraction with plurals, `values/colors.xml`'s launch-window
duplication (documented exactly as the guidelines require), and
`LiveUpdateNotification.kt`'s literals (has an explicit keep-in-step
note).

## Spec axis

### Implemented but wrong

1. **Backend pill lies about connection state.** `RecordScreen.kt:164-169`
   paints `stateDone`/`stateDoneFg` (the doc's *"'Connected'"* fill)
   unconditionally — no reachability input. UI-023 removed the fake
   *host*; the fake *"connected"* survived. Reads "connected" while the
   backend is refusing.

2. **Splash→app crossfade ignores reduced motion.** `AppNav.kt:117-119`
   `Crossfade(animationSpec = tween(220))` is a raw duration, not a
   `HarkenMotion` token. UI-006: *"Every HarkenMotion token… returns
   snap()"* under reduced motion; this one doesn't route through it.

3. **Live-dot nested circles are dead.** `AppNav.kt:283-290` rings
   `stateLive` around an `accent` core. UI-020 made `stateLive == accent`
   in both themes (`ProtoColors.kt:110,140`), so the two circles render
   as one flat dot — the nesting has had no visual effect since UI-020.

### Asserted by spec, not in code

4. **`brand-guidelines.md` §3 is already false, under its own rule**
   (*"the code is what settles a disagreement"*): it says the splash
   wordmark *travels* into the header slot, *"so the two are one object,
   not two."* `SplashScreen.kt:218-232`'s own comment says the opposite:
   *"a second, independently-positioned copy cross-fades in… rather than
   one element visually leaping."* Landing position is also inexact:
   splash lands at 16dp top inset; Record's row centres at ≈13dp
   (`RecordScreen.kt:157-160`).

5. **Doc §5 "Waveform bars | 2dp"** not followed everywhere — the live
   meter hand-rolls `RoundedCornerShape(3.dp)` at `RecordScreen.kt:446`,
   outside `HarkenWaveform.BarShape` (2dp).

6. **Doc §5: "A hand-rolled control has to declare role + 48dp
   explicitly."** Splash tap-to-skip is a bare `clickable` with
   `indication = null`, no `Role`, no label (`SplashScreen.kt:116-119`).
   Nav tabs and the upload card comply; this one predates UI-028's rule
   and was missed by it.

7. **UI-007 "every user-facing string [in strings.xml]"** — not
   universal: `LiveUpdateNotification.kt:34`
   `setContentTitle("Recording — $title")` is still a Kotlin literal, in
   a file this branch edited (UI-028).

### Scope creep

- Splash tagline + 260dp glow (`SplashScreen.kt:147-160,197-205`) go
  beyond UI-011's *"single animated brand moment"*; the tagline's 13.5sp
  is absent from `Theme.kt`'s type scale.
- `Theme.kt:105-107` comment still explains the scheme as *"terracotta
  means 'live' and sage means 'done'"* — that mapping was deleted by
  UI-020 (accent now covers both live and resting).

Faithful, not listed as findings: UI-001/002/004/005/006/008/012/013/
015/016/017/018/028/029 (029 only outside its own EnterExit miss above).

## Resolution

Fixed in one batch, all verified building (`dotnet build` + `gradle
assembleDebug`):

- **Standards-1** (hardcoded host): backend pill now shows the
  configured host with a neutral fill (`c.pillTrack`/`c.textSecondary`)
  instead of claiming `stateDone` — user decision: name the target, drop
  the false "connected" claim, no new live-polling work.
- **Standards-2**: `FilterChipProto` now `heightIn(min = 48.dp)`.
- **Standards-3**: `SplashScreen.kt`'s `EnterExit` deleted, both call
  sites use `ProtoEaseOut`.
- **Standards-5/6**: extracted `rememberStaggerShown()`
  (`ui/components/StaggerReveal.kt`) — used by `LibraryScreen.kt` and
  `SessionSheet.kt`.
- **Standards-6** (shared-axis transition): extracted
  `sharedAxisTransition()` (`ui/theme/Motion.kt`) — used by `AppNav.kt`
  and `OnboardingScreen.kt`.
- **Standards-7** (meter chrome): extracted `MeterCard()` composable in
  `RecordScreen.kt` — used by `IdleMeter`/`LiveMeter`.
- **Standards-8**: removed dead `ProtoColors` fields (`sheetBg`,
  `navBorder`, `grabber`, `rowHighlight`, `inkFaint`, `inkMuted`) and
  `Type.kt`'s `ProtoOvershoot`. (`inkSubtle`/`inkStrong` confirmed live,
  kept — original agent report over-reached there.)
- **Standards-9**: `SettingsScreen.kt`'s `LazyColumn` replaced with a
  scrolling `Column`.
- **Standards-10**: `SettingsViewModel.kt` now uses
  `getApplication<Application>()`, matching sibling ViewModels.
- **Spec-1**: see Standards-1 above.
- **Spec-2**: `AppNav.kt`'s splash→app `Crossfade` now uses
  `HarkenMotion.effectsDefault()` instead of raw `tween(220)`.
- **Spec-3**: live-dot nested circle in `AppNav.kt` simplified to one
  filled `Box` (stateLive == accent since UI-020).
- **Spec-4**: `docs/brand-guidelines.md` §3 corrected to describe the
  actual cross-fade handoff, not a literal "travels" claim.
- **Spec-5**: `LiveMeter`'s bar shape now `HarkenWaveform.BarShape`
  instead of a hand-rolled `RoundedCornerShape(3.dp)`.
- **Spec-6**: `SplashScreen.kt`'s tap-to-skip `clickable` now carries
  `Role.Button` + `onClickLabel` (new string `splash_skip`).
- **Spec-7**: `LiveUpdateNotification.kt`'s `"Recording — $title"`
  literal extracted to `notification_recording_title`.

**Deferred, own ticket:** Standards-4 (`ProtoColors` positional-`c`
threading / duplicated theme resolution → `LocalProtoColors`) — a
genuine architectural change, not a mechanical fix, out of scope for
this batch.

Not fixed, not raised as separate items: the two "scope creep" notes
(splash tagline/glow beyond UI-011's stated scope; the stale
terracotta/sage comment in `Theme.kt`) — no action requested on these.
