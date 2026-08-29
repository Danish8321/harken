# ADR-0013: Adopt the Organic design system as Harken's own component library

## Status
Accepted

## Context
A Claude Design project ("Harken mobile app modernization",
`claude.ai/design/p/7178a441-515e-4341-90b9-e7694fb23f5c`) produced two annotated
screen mocks (`Harken Android - Modernized.dc.html`,
`Harken Android - Interactive.dc.html`) plus the "Organic" design-system token sheet
(`organic-styles.css`) they're built from, pulled and saved to
`docs/design/claude-design-modernization/` for reference.

Cross-checking against the app's existing Compose theme
(`ui/theme/Color.kt`, `ui/theme/Theme.kt`) found the color ramps, Caprasimo/Figtree
type pairing, and Material 3 Expressive shape direction already match the pulled
tokens almost exactly — the app carried out its own Organic redesign already (per
ADR-0010, `docs/adr/0010-expressive-redesign.md`, referenced in `Theme.kt` comments
but never committed as a file in this repo). What's actually new in the mocks is
screen-level UX not yet built, plus two token categories (spacing, elevation) the
Compose theme has no equivalent for.

## Decision
1. **Theme merge, not replacement.** Keep the existing 5-step `HarkenShapes` scale
   (ADR-0010's intentional Material 3 Expressive evolution beyond the mock's flat
   3-step `--radius-sm/md/lg`) and add named radius aliases (`sm`/`md`/`lg`) mapping
   onto the nearest existing step, so the design doc's vocabulary still resolves in
   code. Add two token categories that don't exist yet: `Spacing.kt` (8-step scale,
   `space1`–`space8`, converted from `--space-1..8` px to dp) and elevation/shadow
   tokens (`--shadow-sm/md/lg` equivalents) — neither has a Compose equivalent today.
2. **Existing screens migrate onto `Spacing` tokens as the lowest-priority task** in
   this plan — new components use it from day one; retrofitting old magic-number
   `dp` literals happens last, sequenced so it can't block or derail the rest.
3. **Five new reusable Compose components**, in `ui/components/`:
   `UploadQueueCard`, `StorageWarningBanner`, `SoftArchiveSwipeRow`, `PermissionSheet`,
   `GroupedSettingsList`.
4. **Three logic/wiring changes**, not components: provisional session title (view
   model + `SessionRow` logic), predictive back on the session detail sheet
   (`PredictiveBackHandler` wiring), and a Live Update notification — which is a
   system `NotificationCompat`/progress-notification concern, not Compose, and lives
   in `notifications/` alongside the existing foreground-service code (ADR-0003), not
   `ui/components/`.
5. **Three Room schema changes**, additive columns only, through `.claude/scripts/schema.sh`
   with the generated migration read before applying — same discipline as slice-09's
   `isLocalOnly` column:
   - `SessionRow.isArchived: Boolean = false` — soft-archive, distinct from hard delete
   - `SessionRow.userTitle: String? = null` — set on explicit rename; null means
     "derive provisional title from first transcript line"
   - upload-queue state surfaced from existing upload/session status (no new column
     expected — confirm during implementation whether current status enum already
     carries queued/retrying/failed or needs an additive field)
6. **Sequencing**: new branch off `master`, started only after `feat/on-device-transcription`
   (slice-09) merges — both touch `SessionSheet.kt`, `RecordScreen.kt`, and the Library
   screen; running them concurrently risks conflicting redesigns of the same files.
   `feat/azure-batch-transcription` (unmerged, unrelated concern) is unaffected.

## Alternatives considered
- **Reconcile `HarkenShapes` down to the mock's flat 3-step radius scale.** Rejected:
  the 5-step scale is a documented, intentional deepening (ADR-0010), not a gap —
  flattening it would be a regression dressed as fidelity to the source mock.
- **Defer the 3 schema changes, build UI against fake/stubbed state.** Rejected: would
  mean throwaway UI now and a second rewiring pass later, worse than doing the real
  columns up front given they're small additive changes matching an already-proven
  pattern (slice-09).
- **One ADR per new UX surface (8 ADRs).** Rejected: the surfaces are implementation
  slices of one interlinked decision (token merge, schema, component location), not
  independent architectural choices.
- **Run this plan stacked on top of, or parallel with, slice-09.** Rejected: real file
  overlap (`SessionSheet.kt`, `RecordScreen.kt`) makes concurrent large redesigns of
  the same files higher-risk than sequencing.

## Consequences
- Two new token files (`Spacing.kt`, elevation/shadow tokens) join the existing theme
  package; `HarkenShapes` and `Organic` color ramps are unchanged in value.
- Existing screens get a (lowest-priority, non-blocking) follow-up pass onto
  `Spacing` tokens — a mechanical diff across multiple screen files, done last.
- Three new Room columns (`isArchived`, `userTitle`, possibly an upload-status field)
  via reviewed, non-destructive migrations — same story as slice-09: no
  `fallbackToDestructiveMigration()`.
- A new `notifications/` concern (Live Update) sits outside the Compose component
  library, next to the existing foreground-service code.
- This work cannot start until slice-09 merges to `master`.

## Related
[ADR-0011](0011-on-device-transcription.md), reference mocks and tokens in
`docs/design/claude-design-modernization/`.
