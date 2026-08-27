# Harken Brand Guidelines v1.0

> Last updated: 2026-08-27
> Status: Draft — derived from the shipped Android UI (`ui/theme/ProtoColors.kt` + screen copy), not a separate design exercise.

## Quick Reference

| Element | Value |
|---------|-------|
| Accent Color | `#F6A06B` (light), same hex both themes |
| Primary Font (display) | Caprasimo |
| Primary Font (body/UI) | Figtree |
| Voice | Plainspoken, calm, technically precise, no hype |
| Positioning | A quiet, self-hosted recorder — audio never leaves your own network unless you send it there |

---

## 1. Color Palette

Harken is dual-themed (dark default, light variant, or follows system) — there is no single "brand palette," there are two, sharing one accent.

### Accent

| Name | Hex | Usage |
|------|-----|-------|
| Accent | `#F6A06B` | Record button, active nav item, primary actions, live-input meter bars |
| Accent On (text/icon on accent) | `#402310` | Content sitting on the accent fill |

### Dark Theme

| Token | Hex | Usage |
|-------|-----|-------|
| Screen / Sheet BG | `#1C1A17` | App background |
| Card | `#262320` | Cards, rows |
| Card Border | `#474238` | Card/segment hairlines |
| Text | `#F5EAD8` | Primary text |
| Text Secondary | `#A19786` | Captions, metadata |
| Accent Fill (warm) | `#4A2E19` / on `#FFC6A5` | Status pills, e.g. "studio-mac" idle state |
| Accent Fill 2 (green) | `#333B26` / on `#CCDBB2` | "Connected", "Summarized" states |
| Danger Fill | `#8C1D18` / on `#F9DEDC` | Upload failed, destructive states |
| Meter BG | `#100E0C` | Waveform card background |

### Light Theme

| Token | Hex | Usage |
|-------|-----|-------|
| Screen BG | `#FAF1E1` | App background |
| Card / Sheet BG | `#FFFFFF` | Cards, rows |
| Card Border | `#E4D6BC` | Hairlines |
| Text | `#241F1B` | Primary text |
| Text Secondary | `#6E6153` | Captions, metadata |
| Accent Fill (warm) | `#F0975C` / on `#3A1C0B` | Status pills |
| Accent Fill 2 (green) | `#9FB37D` / on `#20280F` | Connected/summarized states |
| Danger Fill | `#E8735A` / on `#FFFFFF` | Failed/destructive states |

### Accessibility

- Text/background pairs above are the actual Compose `ProtoColors` tokens — spot-checked, all body-text pairs clear WCAG AA (4.5:1) in both themes.
- Never introduce a third accent hue. Semantic states (danger, connected) use the warm/green fills already defined above, not new colors.

---

## 2. Typography

### Font Stack (Google Fonts, loaded via `GoogleFont.Provider`)

- **Display** — Caprasimo, weight 400 only. Headlines, screen titles ("Ready when you are.", "Library", "Settings").
- **Body/UI** — Figtree, weights 400/500/600/700/800. Everything else: labels, buttons, metadata, body copy.

Never substitute a system font for Caprasimo on a screen title — the display face is what gives Harken its identity against a generic Material app.

### Type Scale (as used in the app)

| Element | Size | Font | Weight |
|---------|------|------|--------|
| Screen title (e.g. "Library") | 26sp | Caprasimo | Regular |
| Record hero ("Ready when you are.") | 32sp, 37sp line height | Caprasimo | Regular |
| Elapsed timer | 36sp | Caprasimo | Regular |
| Eyebrow labels (CAPTURE LIMITS, BACKEND) | 11sp, +1.2sp tracking | Figtree | Black/800 |
| Card title / list title | 15sp | Figtree | Bold |
| Body / description | 13.5–14.5sp | Figtree | Regular |
| Caption / metadata | 11–12.5sp | Figtree | Regular or Bold |

---

## 3. Logo Usage

No dedicated logo asset exists yet — the app currently identifies itself with the wordmark "Harken" set in Caprasimo at 20sp (top-left of Record screen). Treat that wordmark as the placeholder mark until a standalone logo is designed.

**Don'ts (once a mark exists):**
- Don't set the wordmark in Figtree — Caprasimo is the identity signal.
- Don't recolor the wordmark outside `text` / `textSecondary` tokens.

---

## 4. Voice & Tone

Derived directly from shipped copy — Harken's actual voice is calm, specific, and privacy-forward, never marketing-speak.

### Brand Personality

| Trait | Description | Evidence |
|-------|-------------|----------|
| **Quiet & confident** | States facts plainly, no exclamation points | "Ready when you are." |
| **Privacy-forward** | Volunteers *where* data goes and doesn't | "Recordings upload over your own Wi-Fi to a backend you run. Nothing goes further than your LAN." |
| **Technically precise** | Gives exact numbers, not vague reassurance | "16 kHz mono · caps at 3 h", "Both limits end the recording and upload it, so a forgotten session lands on the backend rather than on the phone." |
| **Unpatronizing on errors** | Explains cause + what happens next, no apology theatre | "Nothing answered at localhost:5057. Check the machine is awake and on this Wi-Fi — recordings keep saving locally meanwhile." |

### Tone by Context

| Context | Tone | Example (real, from the app) |
|---------|------|-------------------------------|
| Idle/ready state | Calm, present-tense | "Ready when you are." |
| Recording | Status-only, no cheerleading | "CAPTURING", elapsed timer, "Stops after 5 min silence" |
| Error | Cause stated, recovery offered, no blame | "Upload failed · tap to retry" / "Still on this phone at {path}." |
| Settings/limits | Exact values, plain units | "Session cap — 3 hours", "Format — 16 kHz · 16-bit · mono" |
| Onboarding | Short declarative sentences, one idea per screen | "A quiet recorder for meetings and field notes. Audio, transcripts and summaries — kept on your own network." |

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
| Cards (Settings, Library rows) | 24dp |
| Meter / waveform card | 30dp |
| Inline banners (upload status, warnings) | 22dp |
| Pills (status chips, filter chips, segmented control, buttons) | 999dp (full pill) |

### Spacing

| Token | Value | Usage |
|-------|-------|-------|
| Screen horizontal padding | 20dp | All three main screens |
| Card internal padding | 16dp | Settings/Library cards |
| Inter-card gap | 12–14dp | `Arrangement.spacedBy` |

### Controls

| Element | Spec |
|---------|------|
| Record FAB | 88dp circle→cookie morph (`rememberRecordShape`), accent fill, 10dp default elevation / 4dp pressed |
| Secondary button (Test, Skip) | 44–52dp height, pill shape, outlined or accent fill |
| Filter chip | Pill, 1dp hairline border when unselected, accent fill + transparent border when selected |
| Segmented control (Appearance) | Pill row, accent fill on active segment, `cardBorder` hairline on inactive segments |
| Switch | Accent thumb/track when checked; `textSecondary` thumb on `pillTrack` track when unchecked |

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-08-27 | Initial guidelines, reverse-derived from the merged prototype UI (`ui/theme/ProtoColors.kt`, Record/Library/Settings/Onboarding screens) after the prototype-to-main promotion. |
