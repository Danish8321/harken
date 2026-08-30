# ADR-0010: Material 3 Expressive redesign of the Android client

Status: **Accepted**
Date: 2026-08-22
Supersedes: nothing outright. Amends the UI-facing parts of
[ADR-0003](0003-mobile-foreground-service.md) (the recording notification becomes a Live
Update) and [ADR-0007](0007-record-then-transcribe.md) (the record screen no longer implies
a live caption stream).

## Context

The Android client worked but did not read as one product. Concretely, from the code:

- **Three typefaces.** Caprasimo for headings, Figtree for body — and then Roboto, because
  `Typography` never overrode `labelMedium`, which is what `NavigationBarItem` uses for its
  label. The three tab labels were the only Roboto in the app and nobody had noticed.
- **Text below any legible floor.** `9.5.sp` and `10.5.sp` in `RecordingListScreen`.
- **Four layout languages on four screens.** Record was a centred altar; Recordings was a
  hairline index; Session detail was the only bordered, elevated card in the app; Settings
  was a form with a bottom-pinned CTA that appeared nowhere else.
- **An accent used as a wash.** `Accent500` appeared at 42%, 24%, 18% and 10% alpha as
  decoration. With cream on cream on cream and no dark surface anywhere, nothing on any
  screen had a foreground.
- **Decoration in the place data belongs.** The list waveform was seeded from
  `session.id.hashCode()` — noise wearing the costume of information — while a recording's
  actual length, name and transcription progress appeared nowhere on the row.
- **No empty, error or loading state on any screen.** A failure surfaced as `"HTTP 500"`.

## Decision

Rebuild the client on Material 3 Expressive, and give every part of the palette one job.

### Colour has roles, not shades

| Role | Job | Nowhere else |
| --- | --- | --- |
| **Ink** (`Neutral900` / `#100E0C`) | Audio surfaces: capture stage, player, floating toolbar | This is what gives the cream ground a foreground |
| **Accent** (terracotta) | "Live", and the one primary action per screen, at full strength | Never a tint wash; tinted fills come from `Accent200`, not an alpha of `Accent500` |
| **Accent2** (sage) | "Finished and safe": transcribed, summarized, reachable, progress | A genuine second voice, not a highlight |
| **Neutral** | Ground, card, inset field, hairline | Four steps, no more |

### Motion is bound to spring tokens, never to durations

Originally specified against `MotionScheme.expressive()`. **Deviation from spec:**
`MotionScheme`, `MaterialTheme.motionScheme`, `MaterialExpressiveTheme` and
`ExperimentalMaterial3ExpressiveApi` are Kotlin-`internal` in the released `material3`
1.4.0 and 1.5.0-alpha26 artifacts — confirmed by decompiling both AARs with `javap`: the
members are public at the JVM bytecode level but the Kotlin metadata visibility flag is
set to internal, so they cannot be called from outside the androidx.compose.material3
module regardless of Kotlin/AGP/compileSdk version. `ui/theme/Motion.kt` therefore
reimplements the same two-family spring token set on plain
`androidx.compose.animation.core.spring()`, and `ui/theme/Theme.kt` builds on plain
`MaterialTheme(...)` rather than `MaterialExpressiveTheme(...)`. The design intent is
unchanged; the tokens are hand-tuned approximations of Expressive's own constants rather
than the library's tuned values.

- **spatial\*** — overshoot and settle. Anything that moves or resizes: offset, scale,
  size, corner radius, shape morph.
- **effects\*** — never overshoot. Anything that fades or recolours. Bouncing a colour
  reads as a rendering glitch, so the token set makes that mistake unavailable.

Speed follows element size: `fast` for small controls, `default` for most things, `slow`
for full-screen surfaces. Two animations are deliberately NOT sprung and say so in
comments: the live waveform (a data readout, linear) and the skeleton shimmer (an ambient
idle pulse).

### Shape carries state

The record button is one `Morph` between `RoundedPolygon` shapes (via
`androidx.graphics:graphics-shapes`): a circle at rest, a slowly turning twelve-lobe
cookie while capturing. The old build said the same thing three times — an animated halo,
a pulsing ring and a separate waveform behind a static circle.

### Expressive components: adapted to stable APIs where the internal wall was hit

The mockups call for `ButtonGroup`/`clickableItem` (Library filters), `LoadingIndicator`
(transcribing rows), `FloatingActionButtonMenu`/`ToggleFloatingActionButton` (Record's
input options), and `HorizontalFloatingToolbar`/`SplitButtonLayout` (Session sheet
toolbar). All of these sit behind `ExperimentalMaterial3ExpressiveApi`, and each was
either confirmed or is presumed subject to the same Kotlin-internal wall as
`MotionScheme` in `material3:1.4.0`. Rather than pin an alpha `material3` train (which
would require AGP 9.1.0 and `compileSdk 37` — too invasive for this project), each is
adapted to a stable substitute that preserves the same UX:

| Mockup component | Where | Stable substitute used |
| --- | --- | --- |
| `ButtonGroup` + `clickableItem` | Library filters | `LazyRow` of `FilterChip` |
| `LoadingIndicator` | Transcribing rows | `CircularProgressIndicator` |
| `FloatingActionButtonMenu` | Record's input options | `FloatingActionButton` + `DropdownMenu` |
| `HorizontalFloatingToolbar` + `SplitButtonLayout` | Session sheet toolbar | `Surface` + `Row` of `IconButton`/`Button` + `DropdownMenu` |

If a future `material3` release exports these APIs publicly, each substitute is a
one-file swap back to the mockup's original component.

### Screens renamed

`CaptureScreen` → `RecordScreen`, `RecordingListScreen` → `LibraryScreen`,
`SessionDetailScreen` → `SessionSheet`; routes `capture` → `record`, `recordings` →
`library`. A tab labelled "Recordings" next to a tab that records was the single most
confusing thing in the navigation.

### Room becomes the source of truth for the screen

The app gains its first local database — a **full mirror** of sessions, segments and
summaries, plus local-only columns the backend has no field for. Library renders from the
mirror immediately and a refresh is background reconciliation, so an unreachable backend
degrades to "slightly stale" rather than "empty screen". `SessionDao.upsertMirrored`
deliberately does not touch local columns: a sync must never clobber something the user
typed on this device.

### Speaker labels are a heuristic and are labelled as one

Whisper `base.en` returns no diarization, so there is nothing to derive a real identity
from. `SpeakerHeuristic` flips a voice index on a gap of ≥2 s and the UI says **"Voice 1"**
and **"Voice 2"** — never "Speaker A", never a name. The wording is the honest part of the
feature. With one voice detected, the labels do not render at all. If the backend gains
real diarization, `SpeakerHeuristic` is the single thing that gets deleted.

### Dynamic colour moves neutrals only

Wallpaper extraction may recolour background, surface and outline. Primary and secondary
stay on the Organic accents, because here they are semantic: if the wallpaper could
recolour them, "live" and "done" would stop meaning anything.

## Owed by the backend

The redesign is shippable without any of these; each one is a stated limitation in the UI
rather than a silent gap.

| Need | Endpoint / field | Interim behaviour |
| --- | --- | --- |
| Playback | `GET /sessions/{id}/audio`, `audio/wav`, `Accept-Ranges: bytes` | `audioAvailable = false`; the player renders disabled with the reason stated. Upload is currently one-way — once a recording leaves the device there is no way to fetch it back |
| Duration | `durationSeconds` on `SessionListItem` | Derived from the last segment's offset, so it is missing until transcription finishes |
| Title | `title` on `SessionListItem` | Local-only `localTitle`; unnamed recordings fall back to `DerivedTitle` ("Morning recording") |
| Tags | `tags` on `SessionListItem` | Local-only `localTags`, this device only |
| Transcription progress | percentage on `GET /sessions/{id}` | Indeterminate loading indicator instead of a percentage |
| Waveform peaks | peak array on `GET /sessions/{id}` | The player's bars are a seek affordance derived from the session id, and never claim to be amplitude |
| Diarization | `speaker` on `TranscriptSegmentView` | `SpeakerHeuristic`, labelled "Voice N" |

## Consequences

- New dependencies: `androidx.graphics:graphics-shapes`, Room, and `material3` pinned to
  1.4.0 above the Compose BOM's default. Several Expressive APIs the mockups specify are
  not externally callable in this release and were adapted to stable equivalents (see
  above) rather than blocked on.
- `minSdk` stays at 26. Live Updates degrade to a plain ongoing notification below API 36,
  which is what the previous build always showed.
- Local titles and tags are per-device and are not backed up. Accepted for MVP 1 —
  single-user, one phone (ADR-0009).
- The fake list waveform is deleted. If anyone misses it, that is the point.
