# Issues

One file per ticket. Status: `open` | `in-progress` | `done` | `wontfix`.

Source: full UI/UX code scan of `src/Harken.Android` on 2026-08-27, after the
prototype-to-main promotion (`60cf6a2`). All findings verified by grep/read;
contrast ratios computed from actual token hex values via the WCAG
relative-luminance formula.

| ID | Title | Severity | Status |
|----|-------|----------|--------|
| [UI-001](UI-001-hardcoded-dark-colors-break-light-theme.md) | Hardcoded dark-theme colors break light theme | critical | fixed |
| [UI-002](UI-002-two-parallel-design-systems.md) | Two parallel design systems composed together | critical | fixed |
| [UI-003](UI-003-blank-screen-on-cold-start.md) | Blank screen on cold start while DataStore loads | high | fixed |
| [UI-004](UI-004-touch-targets-below-44dp.md) | Error-state buttons below 44dp touch target | high | fixed |
| [UI-005](UI-005-no-semantic-roles.md) | No semantic roles on clickable elements | high | fixed |
| [UI-006](UI-006-no-reduced-motion.md) | No reduced-motion handling | high | fixed |
| [UI-007](UI-007-no-strings-xml.md) | No strings.xml — all copy inlined in Kotlin | medium | fixed |
| [UI-008](UI-008-protocolors-flat-token-bag.md) | ProtoColors is a flat token bag with positional names | medium | fixed |
| [UI-009](UI-009-repalette-wire.md) | Re-palette to Wire | high | fixed |
| [UI-010](UI-010-typography-swap.md) | Typography swap (Space Grotesk / IBM Plex Mono) | medium | fixed |
| [UI-011](UI-011-splash-screen.md) | Splash screen with mark-to-wordmark continuity | high | fixed |
| [UI-012](UI-012-screen-transitions.md) | Screen-to-screen transition motion | high | fixed |
| [UI-013](UI-013-record-screen-motion.md) | Record screen micro-interactions | medium | fixed |
| [UI-014](UI-014-persistent-recording-indicator.md) | Persistent recording indicator across tabs | medium | fixed |
| [UI-015](UI-015-library-stagger.md) | Library list stagger-in | low | fixed |
| [UI-016](UI-016-transcript-reveal.md) | SessionSheet transcript reveal | low | fixed |
| [UI-017](UI-017-haptics.md) | Haptic feedback pairing | low | fixed |
| [UI-018](UI-018-uniformity-audit.md) | Component uniformity audit | medium | fixed |
| [UI-028](UI-028-review-regressions.md) | Regressions found by branch review | high | fixed |
| [UI-029](UI-029-duplicate-declarations.md) | Duplicated waveform and font declarations | medium | fixed |
| [UI-030](UI-030-interactive-pass-backfill.md) | Backfill: the interactive pass, UI-019..UI-027 | low | fixed |
| [UI-031](UI-031-review-findings-solid-dry.md) | Standards/Spec review findings: SOLID/DRY/KISS/YAGNI pass | high | fixed |
| [UI-032](UI-032-on-device-pivot-leftovers.md) | On-device pivot leftovers: hardcoded model claim, stale backend copy, dead upload states, undocumented ink system, illegible duration bar | high | resolved |
| [UI-036](UI-036-voice-one-label-invisible.md) | The "Voice 1" label is painted in the on-accent colour | medium | fixed |
| [UI-037](UI-037-stuck-transcription-and-repair-header.md) | Stuck-transcription reconciliation + wire up `WavWriter.repairHeader` | medium | fixed |

UI-019..UI-027 were driven by direct interactive requests and have no
ticket files of their own — [UI-030](UI-030-interactive-pass-backfill.md)
indexes them and says why they weren't reverse-derived into nine specs.
UI-009, UI-010 and UI-011 each
carry a superseded note (UI-029) naming the commit that replaced them;
read them as history, not as the current state.

## Suggested order

UI-001 (ships broken now) → UI-003 → UI-004 / UI-005 → UI-002 (largest) → UI-007 / UI-008.

UI/UX modernization pass (2026-08-28), sequential: UI-009 → UI-010 → UI-011 →
UI-012 → UI-013 → UI-014 → UI-015 → UI-016 → UI-017 → UI-018 (audit last,
on purpose — it sweeps whatever the earlier tickets leave inconsistent).

## ARC — architecture review, 2026-09-06

Line-by-line architect review of the whole repository (8,456 Kotlin LOC,
5,214 C# LOC) against SOLID, DRY, KISS and YAGNI, plus a daily-driver read of
the product surface. Every finding below was verified by reading the code, not
inferred.

| ID | Title | Severity | Status |
|----|-------|----------|--------|
| [ARC-001](ARC-001-no-launcher-icon.md) | The app ships with no launcher icon | critical | fixed |
| [ARC-002](ARC-002-allowbackup-exfiltrates-recordings.md) | `allowBackup=true` copies recordings and transcripts off the phone | critical | fixed |
| [ARC-003](ARC-003-transcription-has-no-foreground-service.md) | A transcription is killed whenever the user leaves the app | critical | closed |
| [ARC-004](ARC-004-cleartext-traffic.md) | `usesCleartextTraffic=true` for an app that only talks HTTPS | high | fixed |
| [ARC-005](ARC-005-model-download-has-no-integrity-check.md) | The downloaded model is never verified, only counted | high | closed |
| [ARC-006](ARC-006-audiorecord-use-after-release.md) | `AudioRecordCapture.stop()` can release a native object mid-read | high | closed |
| [ARC-007](ARC-007-deleted-recording-comes-back.md) | A deleted recording reappears at the next launch | high | closed |
| [ARC-008](ARC-008-sticky-restart-shows-phantom-error.md) | A sticky restart reports a recording the user never started | high | closed |
| [ARC-009](ARC-009-wall-clock-durations.md) | Recording duration is measured with the wall clock | high | closed |
| [ARC-032](ARC-032-no-search.md) | There is no way to find anything | high | fixed |
| [ARC-010](ARC-010-chunk-rms-computed-three-times.md) | Every audio chunk's RMS is computed three times | medium | fixed |
| [ARC-011](ARC-011-noisefloor-sorts-every-chunk.md) | The noise floor re-sorts its whole window on every chunk | medium | fixed |
| [ARC-012](ARC-012-blocking-io-on-default-dispatcher.md) | Blocking reads run on the CPU dispatcher | medium | fixed |
| [ARC-013](ARC-013-per-chunk-allocation.md) | A fresh byte array is allocated for every audio chunk | medium | fixed |
| [ARC-014](ARC-014-no-composition-root.md) | Every ViewModel builds its own dependencies | medium | closed |
| [ARC-015](ARC-015-domain-speaks-a-removed-backends-language.md) | The data layer speaks the language of a removed backend | medium | blocked |
| [ARC-016](ARC-016-persistence-triggered-from-the-ui-layer.md) | A finished recording is saved by the UI, not by the recorder | medium | closed |
| [ARC-017](ARC-017-derived-titles-not-localizable.md) | Derived recording titles are hardcoded English | medium | fixed |
| [ARC-018](ARC-018-notification-title-is-a-hex-fragment.md) | The recording notification is titled with eight hex characters | medium | fixed |
| [ARC-019](ARC-019-download-guard-does-not-guard.md) | The concurrent-download guard does not guard the download | medium | closed |
| [ARC-020](ARC-020-no-static-analysis-no-ci.md) | Nothing checks style, lint or correctness except a compiler | medium | fixed |
| [ARC-021](ARC-021-no-version-catalog.md) | Dependency versions are string literals in the build file | medium | fixed |
| [ARC-022](ARC-022-no-release-signing-no-versioning.md) | The release build cannot be released | medium | fixed |
| [ARC-023](ARC-023-instrumented-tests-never-run.md) | The instrumented tests are in no gate | medium | fixed |
| [ARC-024](ARC-024-dead-dotnet-tier.md) | Half the repository is a backend nothing calls | medium | done |
| [ARC-025](ARC-025-collectasstate-without-lifecycle.md) | Flows keep collecting while the app is backgrounded | medium | fixed |
| [ARC-026](ARC-026-viewmodel-holds-navigation.md) | A ViewModel holds the navigation callbacks | medium | closed |
| [ARC-033](ARC-033-no-export.md) | Nothing can leave the app except a copied transcript | medium | fixed |
| [ARC-034](ARC-034-no-pause-resume.md) | A recording cannot be paused | medium | fixed |
| [ARC-027](ARC-027-duplicate-tracker-id.md) | Two tickets share the ID UI-032 | low | fixed |
| [ARC-028](ARC-028-stale-doc-references.md) | Comments point at files that no longer exist | low | fixed |
| [ARC-029](ARC-029-locale-independent-formatting.md) | `String.format` without a Locale | low | fixed |
| [ARC-030](ARC-030-wavwriter-seeks-every-chunk.md) | The WAV writer seeks before every write | low | fixed |
| [ARC-031](ARC-031-model-handle-leak-on-throw.md) | A failed `nativeFreeModel` leaks the handle permanently | low | closed |
| [ARC-035](ARC-035-tab-transition-animated-a-pane-against-itself.md) | The tab transition animated each screen against itself | medium | fixed |
| [ARC-036](ARC-036-dynamic-color-crashes-below-api-31.md) | The wallpaper-colours switch crashes on Android 8 through 11 | high | fixed |
| [ARC-037](ARC-037-lint-warnings-formatter-and-ci.md) | Lint's 76 warnings, a formatter, and CI | medium | in-progress |
| [ARC-038](ARC-038-typeface-depends-on-play-services.md) | The app's whole typeface system is a Play Services download | high | open |
| [ARC-039](ARC-039-migration-test-asserts-a-guess.md) | The migration test asserts against a v1 schema typed out by hand | medium | fixed |
| [ARC-040](ARC-040-dead-sync-era-surface.md) | The sync tier is gone; its DAO surface and one of its chips are not | medium | fixed |
| [ARC-041](ARC-041-integration-tests-are-not-deterministic.md) | The .NET gate failed once and passed on a re-run | medium | fixed |
| [ARC-042](ARC-042-transcription-failure-text-comes-from-the-exception.md) | A failed transcription showed the user the exception's message | high | fixed |
| [ARC-043](ARC-043-transcription-notification-could-stick.md) | The transcribing notification could stay up forever | medium | fixed |
