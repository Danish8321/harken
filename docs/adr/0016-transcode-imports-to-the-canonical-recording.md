# ADR-0016: Transcode imports to the canonical Recording format

## Status
Accepted

## Context
Harken can only make a Session by capturing one. Audio the user already has — a voice
note someone sent them, a meeting recorded on a laptop, an old memo from before Harken
existed — cannot be transcribed at all, even though transcribing it privately on the
device is exactly what Harken is for. The gap is widest for audio that came from
somewhere else, which is also the audio a user is least willing to hand to a cloud
transcriber.

The app's entire audio path assumes one format, produced by one writer:

- `WavWriter` emits a canonical 44-byte-header, 16 kHz / 16-bit / mono WAV, and its
  constructor requires a fresh zero-length file (`WavWriter.kt:33`).
- `WavFormat.durationSeconds` is `(length - 44) / 32000` (`WavWriter.kt:24`) and is the
  single duration authority for the recorder, recovery and the transcriber (ARC-009).
- `OnDeviceTranscriber` does no WAV parsing whatsoever. `pcmSampleCount`, `scanWindowRms`
  and `readSamples` all hardcode a 44-byte offset and 16-bit little-endian mono
  (`OnDeviceTranscriber.kt:277`, `:295`, `:312`), and the sample rate handed to JNI is
  hardcoded (`:185`).
- `SessionRepository.purge` deletes the file at `audioPath` unconditionally
  (`SessionRepository.kt:260`).

An imported file is essentially never in that format. Share-sheet voice notes are Opus in
an OGG or M4A container; recordings from other devices are 44.1 or 48 kHz stereo AAC or
MP3. So the question this ADR answers is not "can we decode it" but **what lands on disk,
and what `Recording` means afterwards**.

Full decisions were grilled interactively; the alternatives below are what survived.

## Decision
An Import decodes the chosen file and writes a **canonical Recording** — 16 kHz, 16-bit,
mono WAV, byte-identical in kind to one the recorder would have produced. The original
file is never copied into app storage and never referenced from the database. After
import completes, nothing in the app can tell an imported Session from a captured one.

1. **Decode with platform codecs.** `MediaExtractor` + `MediaCodec`, no new dependency.
   Any container with a decodable audio track is accepted, including video containers
   (mp4/mkv/webm) — the AAC inside a phone video is the same codec as the m4a beside it,
   and reached identically.
2. **Downmix and resample in Kotlin.** Channels are averaged to mono; rate conversion to
   16 kHz is a low-pass followed by decimation, not bare linear interpolation. Linear
   interpolation from 44.1 kHz aliases everything above 8 kHz down into the speech band as
   noise, which degrades transcripts invisibly — and transcript quality is the product.
   A hand-rolled, unit-tested decimator is consistent with `WavWriter`, `NoiseFloor` and
   `SilenceDetector`, all of which are hand-rolled for the same reason.
3. **The source is discarded, not retained.** There is no source-vs-derived distinction,
   no second audio artifact, and no `origin` column. `Recording` stays exactly one thing.
4. **Completeness is all-or-nothing.** Decode writes into `cacheDir`; only a fully
   completed WAV is renamed into `filesDir/<uuid>.wav`, and the Session row is inserted
   after the rename. A decode error fails the whole import and creates no Session.
5. **Storage is gated before any byte is written**, using `MediaFormat` `KEY_DURATION`
   against the same `32000` bytes-per-second constant, checked against `StatFs` free
   space. The 3-hour Session Cap does **not** apply: it bounds an open-ended capture
   (`CONTEXT.md`), and an import is not open-ended.

## Alternatives considered
- **Keep the original file and decode at transcribe time.** Storage-honest — a 5 MB m4a
  stays 5 MB instead of becoming a 460 MB WAV — and preserves full fidelity for a future
  re-transcription by a better model. Rejected: it makes `Recording` polymorphic, and
  every consumer pays. The transcriber's hardcoded 44-byte-offset byte contract would have
  to be replaced with real container handling, `WavFormat.durationSeconds` would stop being
  the duration authority, the player and export would each grow a second shape, and
  `purge` would have to become ownership-aware or risk deleting a user's own file when a
  Session is deleted. That is a large, permanent tax on every existing path to save disk
  on a device where 115 MB/hour was already accepted for the recorder itself.
- **Keep both — the original as the durable artifact, the WAV as a regenerable cache.**
  The most technically correct answer, and the only one that preserves fidelity for a
  future re-transcribe. Rejected: it introduces a source-vs-derived distinction into a
  glossary that has exactly one audio artifact, for a future capability with no concrete
  plan behind it.
- **Reference the user's file in place, without copying.** Rejected outright:
  `SessionRepository.purge` deletes `audioPath` unconditionally, so deleting a Session
  would destroy the user's own file outside app storage.
- **Store an `origin` column so imports stay identifiable.** Would allow an "Imported"
  badge and would explain a poor transcript from a thin 8 kHz source. Rejected: it
  reintroduces **Source**, retired in `CONTEXT.md` precisely because there is one client
  and one microphone; and after the transcode there is no behaviour anywhere in the app
  that would branch on it. A field that exists only to be displayed is a badge, not a
  model.
- **Add `androidx.media3:media3-transformer`** and let Google's pipeline handle
  extraction, downmix and Sonic-based resampling. Rejected for this slice: several MB and
  a large API surface for one job, where ~50 lines of testable DSP suffices. It stays the
  fallback if platform-codec container handling proves fragile in the field.
- **Import partial audio when a decode fails mid-file.** Rejected: it produces a
  complete-*looking* Session whose transcript silently stops part-way through, with
  nowhere to record that it is partial (see the `origin` decision above). The imported
  file may be the user's only copy.

## Consequences
- **A 5 MB m4a becomes a ~115 MB WAV per hour of audio, and the original's fidelity is
  gone for good.** A re-transcribe in two years runs against 16 kHz mono, not the source.
  This is the price of `Recording` staying one thing, and it is not recoverable after the
  fact.
- Users must be shown that price before it is paid — hence the pre-flight size gate and
  a confirmation for large imports, rather than a silent 460 MB write.
- No schema change. The database stays at version 4, so this slice never touches the
  migration path.
- `RecordingRecovery` adopting a stray `<uuid>.wav` from `filesDir` becomes correct rather
  than dangerous, because an imported file genuinely is canonical: `repairHeader` patching
  it is a no-op on a well-formed file. The narrow crash window between rename and row
  insert therefore loses no audio — the Session is recovered, it just gets a
  `lastModified`-derived name instead of its filename.
- New maintenance surface: a hand-written decimator and downmixer, whose correctness is
  asserted by unit tests rather than by a vendor.
- Imports bypass the 3-hour Session Cap by design, so a single Session can now be longer
  than any recording the app would ever make.

## Related
[ADR-0007](0007-record-then-transcribe.md),
[ADR-0011](0011-on-device-transcription.md),
[ADR-0014](0014-minimum-supported-device.md)
