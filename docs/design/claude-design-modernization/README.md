# Claude Design reference — Harken mobile app modernization

Pulled from claude.ai/design project `7178a441-515e-4341-90b9-e7694fb23f5c`
("Harken mobile app modernization") for use as implementation reference only.
Not wired into the build — no code here executes.

## Files

- `Harken Android - Modernized.dc.html` — annotated screen set: idle/recording
  capture, Library with upload queue, session sheet, settings, permission
  sheet, Live Update notification mock. Read the on-page captions — each
  screen names the specific platform convention it's demonstrating
  (predictive back, edge-to-edge, Live Update, One UI sheet, grouped
  settings, haptic cue).
- `Harken Android - Interactive.dc.html` — same screens with CSS
  keyframe-driven interaction states (recording pulse, sheet slide-in/out,
  toast/banner transitions) — reference for motion timing and easing, not
  literal code.
- `organic-styles.css` — the "Organic" design-system token sheet + component
  classes these screens are built from (source: `_ds/organic-.../styles.css`
  in the same project). This is the canonical source for every color/spacing/
  radius value referenced below.

Open the `.dc.html` files directly in a browser to view.

## Token → Compose mapping

The app's own Compose theme should be the source of truth going forward;
this table is a one-time translation aid, not a sync target.

| Organic token | Value | Compose equivalent |
|---|---|---|
| `--color-bg` (dark ground used in screens: `#12100d`/`#1C1A17`) | ink surfaces | `MaterialTheme.colorScheme.background` / `surface` |
| `--color-accent` / `--color-accent-400` | `#c67139` / `#F6A06B` (terracotta — "live/recording") | primary accent color |
| `--color-accent-2` / `--color-accent-2-400` | `#7a8a5e` / `#AEBF92` (sage — "done/connected") | secondary/success accent |
| `--font-heading` | Caprasimo | display/headline text style |
| `--font-body` | Figtree | body text style |
| `--radius-lg` | 28px, over-rounded to ~32px on cards/dialogs | large shape token (Material 3 Expressive) |
| `--radius-md` | 16px, pill (999px) on buttons/inputs/tags | medium shape token |
| `--shadow-sm/md/lg` | ink-tinted soft shadows | elevation tonal overlay |

Color/type/shape here matches what ADR-0011 and the existing app already call
the Organic + Material 3 Expressive system — these screens assume that base
is unchanged and layer newer platform conventions on top (see captions).

## Screens covered

1. Record — idle
2. Record — recording (+ storage-cap warning banner)
3. Library — populated, with visible upload queue + auto-retry
4. Session sheet — ready + summary
5. (Interactive file only) additional states/transitions for the above

## Adoption status

Full adoption is planned in [ADR-0013](../../adr/0013-organic-design-system-adoption.md)
and [slice-10](../../plans/slice-10-organic-design-system.md) — new branch off
`master`, starting after `feat/on-device-transcription` merges. This section is the
living reference guide for that work and any further additions to the component
library; update it as components land or new surfaces get scoped.

## Component reference guide

### Built already (existing Compose theme, pre-dates this pull)
| Token/role | Where | Notes |
|---|---|---|
| Color ramps (accent/accent2/neutral) | `ui/theme/Color.kt` (`Organic` object) | Matches `organic-styles.css` values exactly |
| Type (Caprasimo/Figtree) | `ui/theme/Theme.kt` (`HarkenTypography`) | — |
| Shape scale | `ui/theme/Theme.kt` (`HarkenShapes`) | 5-step, intentionally beyond the mock's flat 3-step `--radius-*` (ADR-0010) |

### Planned — slice 10 (see plan for file-level detail)
| Component/surface | Kind | Package | Data change |
|---|---|---|---|
| `Spacing` tokens | tokens | `ui/theme/Spacing.kt` | none |
| `Elevation`/shadow tokens | tokens | `ui/theme/Elevation.kt` | none |
| Radius aliases (`sm`/`md`/`lg`) | tokens | `ui/theme/Theme.kt` | none |
| `UploadQueueCard` | component | `ui/components/` | uses existing/extended upload status |
| `StorageWarningBanner` | component | `ui/components/` | none |
| `SoftArchiveSwipeRow` | component | `ui/components/` | `SessionRow.isArchived` (new column) |
| `PermissionSheet` | component | `ui/components/` | none |
| `GroupedSettingsList` | component | `ui/components/` | none |
| Provisional title | logic | `SessionRepository`/`SessionSheetViewModel` | `SessionRow.userTitle` (new column) |
| Predictive back | wiring | `SessionSheet.kt` | none |
| Live Update notification | system notification | `notifications/` (not `ui/components/`) | none |
| Existing-screen spacing migration | refactor | all `ui/*Screen.kt` | none — lowest priority, non-blocking |

### Not yet scoped — candidates for future additions
Anything present in `organic-styles.css`/the mocks but not covered by slice 10, and
anything a future Claude Design pull might add. When picking one up:
1. Check whether it's a token, a component, or logic/wiring — same triage as above
   decides its package.
2. If it touches persistence, it needs a real Room migration through
   `.claude/scripts/schema.sh`, reviewed before applying — never
   `fallbackToDestructiveMigration()`.
3. A single new surface is usually a task added to an existing plan, not a new ADR —
   reserve a new ADR for a decision, not an addition (see ADR-0013's rationale for why
   this adoption is one umbrella ADR rather than eight).
4. Re-pull the source project (`DesignSync` tool, project id
   `7178a441-515e-4341-90b9-e7694fb23f5c`) if the design evolves — re-save the
   `.dc.html`/`styles.css` files here and diff against what's already built before
   assuming a token or screen is new.

Known gaps not yet mocked at all: no dark-mode-specific screen shown for Settings/
Onboarding (inferred from tokens only), no tablet/large-screen layout, no
accessibility (TalkBack/large-text) pass on the new components.
