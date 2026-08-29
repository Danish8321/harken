# Harken Brand Guidelines v2.3

> Last updated: 2026-08-29
> Status: Draft — derived from the shipped Android UI (`ui/theme/ProtoColors.kt` + screen copy), not a separate design exercise.

**This document describes what is in the code, so the code is what settles a disagreement.** Every value below is copied from `ui/theme/ProtoColors.kt` or a screen composable. A change to either is not finished until this file matches it — v1.0 went stale inside a single branch because four re-palettes landed without touching it, and a design standard nobody updates is worse than none, because it is quoted with confidence.

## Quick Reference

| Element | Value |
|---------|-------|
| Accent Color | `#BFA789` (dark) / `#8A744A` (light) — one warm tan, two lightnesses |
| Primary Font (display) | Space Grotesk |
| Primary Font (body/UI) | Figtree |
| Numeric/technical readouts | IBM Plex Mono |
| Voice | Plainspoken, calm, technically precise, no hype |
| Positioning | A quiet, on-device recorder — audio is transcribed on the phone and never leaves it |

---

## 1. Color Palette

Harken is dual-themed (dark default, light variant, or follows system) — there is no single "brand palette," there are two, sharing one accent role. The dark theme is a cool slate ground carrying a warm tan accent; the light theme is the same relationship inverted, not a naive inversion of the hexes.

### Accent

| Name | Dark | Light | Usage |
|------|------|-------|-------|
| Accent | `#BFA789` | `#8A744A` | Record button, active nav tab, waveform bars, live/recording state |
| Accent On (text/icon on accent) | `#2B2016` | `#FDF4E8` | Content sitting on the accent fill |

The accent doubles as the live/recording fill (`stateLive`). That is deliberate: idle and live are the same instrument, so they are the same colour, not two colour systems.

### Dark Theme

| Token | Hex | Usage |
|-------|-----|-------|
| Screen BG | `#2C313A` | App background |
| Sheet BG | `#333944` | Bottom sheets — one step up so a sheet reads as lifted |
| Card / Nav | `#3C414A` | Cards, rows, the floating nav bar |
| Card Border | `#464D56` | Card/segment hairlines, pill tracks, row highlight |
| Text | `#D1C9BE` | Primary text |
| Text Secondary | `#A0A6AD` | Captions, metadata, inactive nav |
| Skeleton | `#828A94` | Loading placeholders |
| Done Fill (sage) | `#2E3D33` / on `#8FBF9A` | "Connected", "Summarized" states |
| Danger Fill | `#E74C3C` / on `#2B0B08` | Upload failed, destructive states |
| Meter BG | `#232830` | Waveform card — the one surface that goes *under* the ground |

### Light Theme

| Token | Hex | Usage |
|-------|-----|-------|
| Screen BG | `#F3F6F7` | App background |
| Card / Sheet BG | `#FFFFFF` | Cards, rows |
| Card Border | `#DDE6E8` | Hairlines |
| Text | `#10161A` | Primary text |
| Text Secondary | `#5B676C` | Captions, metadata |
| Done Fill (sage) | `#DCEBDF` / on `#1F4A2B` | Connected/summarized states |
| Danger Fill | `#C7392F` / on `#FFFFFF` | Failed/destructive states |
| Meter BG | `#E4EAEC` | Waveform card background |

### The launch window is part of the palette

`res/values/colors.xml` and `res/values-night/colors.xml` hold `window_background`, painted by the OS *before* Compose starts. It must equal `screenBg` for its theme or every cold start flashes the wrong ground. Android cannot read a Compose value that early, so this is the one duplication of a palette token in the repo — **a re-palette must edit those two files by hand.** This was missed in UI-009 and again in UI-024; both times the flash came back.

### The ink surface is a separate, deliberate palette

`InkSurface` (`ui/components/HarkenSurfaces.kt`) and its `LocalInk` tokens (`ui/theme/Theme.kt`) are not `ProtoColors` — they read from a small warm ramp defined on `Organic` (`ui/theme/Color.kt`): `#2E2B25` ink / `#F5EAD8` on-ink for light theme, `#100E0C` ink / `#F5EAD8` on-ink for dark. This is intentional, not a leftover from the pre-UI-002 two-system era: ink is reserved for audio surfaces only — the capture stage, the player, the floating toolbar — and is meant to read as one warm, near-black anchor regardless of which `ProtoColors` theme is active, so audio always has the same visual weight. It still flips its own light/dark ink shade with the system theme; what stays fixed is that it never borrows a `ProtoColors` hue. Don't route a non-audio surface through `InkSurface`, and don't fold `Organic` into `ProtoColors` — diluting either erases the one signal this system carries.

### Accessibility

- Text/background pairs above are the actual Compose `ProtoColors` tokens — all body-text pairs clear WCAG AA (4.5:1) in both themes.
- Contrast decides the token, not the reference art. The dark theme's inactive-nav colour is `textSecondary` (5.3:1) rather than the reference's `#828A94` (3.7:1), which is enough for an icon but not the label beside it.
- The ink ramp's alphas are tuned to the ground they sit on. They were raised in UI-024 because the tier that read as a hairline on a near-black ground all but vanished on a slate one.
- Never introduce a third accent hue. Semantic states (danger, connected) use the fills already defined above.

---

## 2. Typography

### Font Stack (Google Fonts, loaded via `GoogleFont.Provider`)

- **Display** — Space Grotesk, weights 400/500/700. Headlines, screen titles, the wordmark.
- **Body/UI** — Figtree, weights 400/500/600/700/800. Labels, buttons, metadata, body copy.
- **Mono** — IBM Plex Mono, weights 400/500. Numeric and technical readouts only: the elapsed timer, meter labels, format lines, the cap countdown.

Space Grotesk replaced Caprasimo in UI-010. Geometric and technical suits a precision-instrument product; the rounded slab fought it. Never substitute a system font for the display face on a screen title — it is what keeps Harken from reading as a generic Material app.

### Type Scale (as used in the app)

| Element | Size | Font | Weight |
|---------|------|------|--------|
| Splash wordmark | 34sp | Space Grotesk | Regular |
| Record hero ("Ready when you are.") | 32sp, 37sp line height | Space Grotesk | Regular |
| Screen title ("Library", "Settings") | 26sp | Space Grotesk | Regular |
| Onboarding step title | 27sp | Space Grotesk | Regular |
| Record wordmark (header) | 20sp | Space Grotesk | Regular |
| Elapsed timer | 36sp | IBM Plex Mono | Medium |
| Format / meter readouts | 12.5–14sp | IBM Plex Mono | Regular |
| Eyebrow labels (CAPTURE LIMITS, SPEECH MODEL) | 11sp, +1.2sp tracking | Figtree | Black/800 |
| Card title / list title | 15sp | Figtree | Bold |
| Nav tab label | 13sp | Figtree | Bold |
| Body / description | 13.5–14.5sp | Figtree | Regular |
| Caption / metadata | 11–12.5sp | Figtree | Regular or Bold |

---

## 3. Logo Usage

No dedicated logo asset exists yet. The app identifies itself two ways:

- The **wordmark** "Harken" in Space Grotesk — 34sp on the splash, 20sp top-left of Record. On a cold launch into Record, the large splash wordmark fades out as a second, independently-positioned copy cross-fades in at the Record header slot — the handoff reads as one continuous object settling into place, not a literal single view moving on screen.
- The **mic mark** — an 88dp accent circle with a 32dp `Icons.Filled.Mic`, identical on the splash and as the Record FAB. Same instrument, same weight, both places.

Treat both as placeholders until a standalone logo is designed.

**Don'ts (once a mark exists):**
- Don't set the wordmark in Figtree — the display face is the identity signal.
- Don't recolor the wordmark outside `text` / `textSecondary` tokens.
- Don't scale the mic mark down to make it a "small logo" — it is sized to match the FAB on purpose.

---

## 4. Voice & Tone

Derived directly from shipped copy — Harken's actual voice is calm, specific, and privacy-forward, never marketing-speak.

### Brand Personality

| Trait | Description | Evidence |
|-------|-------------|----------|
| **Quiet & confident** | States facts plainly, no exclamation points | "Ready when you are." |
| **Privacy-forward** | Volunteers *where* data goes and doesn't | "Recording transcribes right there on your phone the moment you stop — no upload, no account, no per-minute cost, entirely on-device." |
| **Technically precise** | Gives exact numbers, not vague reassurance | "16 kHz mono · caps at 3 h", "On-device transcription needs a one-time download (about 140 MB)." |
| **Unpatronizing on errors** | Explains cause + what happens next, no apology theatre | "Download failed" (model download) states the fact and offers Retry, no apology theatre. |

Labels name the real thing (ADR-0011, on-device pivot). The old Record header "backend" pill, which showed a configured host read from settings, is gone along with the self-hosted-server architecture it pointed at — see UI-032 finding 4. There is nothing left in the shipped app that names a remote host or network destination for a recording.

### Tone by Context

| Context | Tone | Example (real, from the app) |
|---------|------|-------------------------------|
| Idle/ready state | Calm, present-tense | "Ready when you are." |
| Recording | Status-only, no cheerleading | "CAPTURING", elapsed timer, "Stops after 5 min silence" |
| Error | Cause stated, recovery offered, no blame | "Download failed", "Nothing recorded yet. Recordings appear here as soon as you stop one." |
| Settings/limits | Exact values, plain units | "Session cap — 3 hours", "Format — 16 kHz · 16-bit · mono" |
| Onboarding | Short declarative sentences, one idea per screen | "Record now, read later. Recording transcribes right there on your phone the moment you stop." |

### Prohibited Terms

| Avoid | Reason |
|-------|--------|
| Seamless / effortless | Not how the app talks about itself anywhere in shipped copy |
| Revolutionary / game-changing | Contradicts the "quiet" personality |
| Cloud-powered / AI-powered | Harken's pitch is the opposite — local-first, self-hosted |
| Exclamation points | None appear in any shipped string; keep it that way |

---

## 5. Design Components (from actual Compose usage)

### Corner Radii

| Element | Radius |
|---------|--------|
| Floating nav bar (outer) | 32dp |
| Meter / waveform card | 30dp |
| Cards, banners, nav tab pill | 24dp |
| Pills (status chips, filter chips, segmented control, buttons) | 999dp (full pill) |
| Waveform bars | 2dp |

### Spacing

| Token | Value | Usage |
|-------|-------|-------|
| Screen horizontal padding | 20dp | All three main screens |
| Card internal padding | 16dp | Settings/Library cards |
| Inter-card gap | 12–14dp | `Arrangement.spacedBy` |
| Nav bar inset | 20dp horizontal, 12dp vertical | Floats the bar off the screen edges |

### Controls

| Element | Spec |
|---------|------|
| Record FAB | 88dp circle→cookie morph (`rememberRecordShape`), accent fill, 10dp default elevation / 4dp pressed |
| Nav tab | `selectable` with `Role.Tab`, min 48dp height, 24dp pill, accent fill when selected |
| Secondary button (Test, Skip) | 44–52dp height, pill shape, outlined or accent fill |
| Filter chip | Pill, 1dp hairline border when unselected, accent fill + transparent border when selected |
| Segmented control (Appearance) | Pill row, accent fill on active segment, `cardBorder` hairline on inactive segments |
| Switch | Accent thumb/track when checked; `textSecondary` thumb on `pillTrack` track when unchecked |

**Every interactive control clears 48dp and carries a semantic role.** A hand-rolled control has to declare both explicitly — replacing `NavigationBarItem` with a custom row in UI-022 silently dropped the `Role.Tab` and the 48dp floor that the Material component had been providing, and TalkBack went back to announcing the tabs as unlabelled text.

### The waveform

One motif, three places: the splash, the idle meter, and the live meter. Bars march left-to-right and mirror around the row's vertical centre — an oscilloscope trace, not a bar chart growing from a floor. Splash and idle share the phase constants; the live meter is driven by real input amplitude. Bar count sets the visual density, because the bars are laid out with `SpaceBetween` across the full width.

---

## 6. Motion (from `ui/theme/Motion.kt` and `MorphShapes.kt`)

Two token families, chosen by what's moving, never by feel in the moment:

| Family | Governs | Behaviour |
|--------|---------|-----------|
| `spatial*` (fast/default/slow) | Offset, scale, size, corner radius, shape morph — anything that **moves or resizes** | Springs with overshoot (`dampingRatio = 0.8`) |
| `effects*` (fast/default/slow) | Alpha, colour, elevation tint — anything that **fades or recolours** | Critically damped, never overshoots |

A colour bouncing past its target reads as a rendering glitch, so the two families exist precisely so an alpha or hue change can't accidentally inherit a spring's overshoot. Speed tier follows element size: fast for small controls (icons, chips, toggle knobs), default for cards/indicators/sub-half-screen sheets, slow for full-screen surfaces (the session sheet, scrims).

Every `HarkenMotion` token collapses to `snap()` under the system's reduced-motion setting, at the token, not the call site — a new animation is accessible by construction as long as it's built from a token. Only what can't be expressed as a spec (infinite loops, enter/exit transitions) reads `LocalReducedMotion` directly.

### The signature move: the record button doesn't blink, it changes shape

The record FAB is one `RoundedPolygon` morphing between a calm 12-vertex circle at rest and a 12-lobed "cookie" while capturing (`rememberRecordShape`), on a `spatialFast` spring. While recording, the cookie turns slowly (14s per rotation) so the state reads as *ongoing*, not decorative. Under reduced motion the spin stops but the shape change alone still carries the state — silhouette, not colour or blink, is what "recording" means on this control. A third shape (`ShapeClover`) is the loading sequence's second beat, same morph mechanism.

This is deliberate: see `docs/adr/0010-expressive-redesign.md`. Nothing in the app blinks, pulses, or flashes for attention — states change shape or crossfade, never strobe.

### Other motion in the app

- **Splash → Record handoff** — the large splash wordmark fades out as a second, independently-positioned copy at the Record header slot fades in; reads as one object settling into place (§3), not a literal shared-element move.
- **Idle ↔ live meter** — crossfades as one block on an `effects` token rather than a hard cut, so the waveform doesn't visibly restart.
- **Tab / step transitions** — `sharedAxisTransition`: forward slides in from the right, back from the left, fade paired with the slide; collapses to a plain swap under reduced motion.
- **Status appearances** (upload badges, error banners, "still capturing" hints past 10.5s) — plain `fadeIn`/`fadeOut` via `AnimatedVisibility`, never slide — a status line arriving is not something moving into place.

### Rules

- Never bind a colour or alpha change to a `spatial*` token, or a move/resize to an `effects*` token — the family is the contract, not a suggestion.
- Never hand-roll a `spring()` or `tween()` outside `HarkenMotion` — a hand-rolled spec skips the reduced-motion collapse.
- No infinite loop may be the sole carrier of state (see the recording spin above) — always pair it with a non-animated signal (shape, colour, text) that survives reduced motion.

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 2.3 | 2026-08-29 | Removed the stale self-hosted/backend narrative left over from before ADR-0011's on-device pivot (Quick Reference positioning line, §4 voice/tone evidence, the dead "BACKEND" eyebrow label) — see UI-032 finding 4. Replaced with copy that matches the shipped on-device transcription flow. Added a §1 "ink surface" section documenting `Organic`/`LocalInk` as a deliberate, `ProtoColors`-independent palette for audio surfaces only, previously undocumented (UI-032 finding 6). |
| 2.2 | 2026-08-29 | Added §6 Motion (spatial/effects spring tokens, the record button's circle→cookie morph, reduced-motion contract) reverse-derived from `ui/theme/Motion.kt` and `MorphShapes.kt` — no motion direction existed in this doc before. Added a build-time JUnit test (`WindowBackgroundConsistencyTest`) that fails compilation the next time `window_background` drifts from `ProtoColors.screenBg`, closing the UI-009/UI-024 gap this doc had only documented, not enforced. |
| 2.1 | 2026-08-28 | Corrected after UI-031's Standards/Spec review: §3's splash→Record handoff description fixed to match the actual cross-fade (was asserting the wordmark "travels" as one object); §4 backend-pill note updated — the pill's fill is neutral, not a false "Connected" claim. |
| 2.0 | 2026-08-28 | Brought back in line with the code after UI-009 (Wire palette), UI-010 (Space Grotesk), UI-020/UI-024 (tan accent, slate ground), UI-022 (floating nav) and UI-019–UI-027 (splash, waveform). Every colour, font and radius updated; added the launch-window rule, the waveform motif, and the touch-target/role rule for hand-rolled controls. |
| 1.0 | 2026-08-27 | Initial guidelines, reverse-derived from the merged prototype UI (`ui/theme/ProtoColors.kt`, Record/Library/Settings/Onboarding screens) after the prototype-to-main promotion. |
