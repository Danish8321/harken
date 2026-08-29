# UI-030 — Backfill: the interactive pass, UI-019..UI-027

- **Severity:** low
- **Status:** fixed (record only — no code change)
- **Area:** `.scratch/issues/`

## Problem

`.scratch/issues/README.md` says "One file per ticket". UI-019 through
UI-027 — nine commits, including two re-palettes, the nav replacement and
three splash rewrites — have none. They came from direct interactive
requests during a live session, not from written tickets, so there was
never a spec to file.

UI-028 recorded this as a follow-up and proposed backfilling nine ticket
files. That is not what this ticket does, on purpose: a ticket
reverse-derived from its own diff cannot disagree with the code, so it
records nothing the code doesn't already say while *looking* like an
independent spec. That is exactly the failure mode of the stale
`brand-guidelines.md` (UI-028 #4) and of UI-009/010/011 (UI-029 #3) — a
confident document nobody checked.

So this is one index entry, not nine specs. It closes the gap in the
tracker by naming what happened and pointing at the commits, which are
the actual source of truth.

## The pass

Driven interactively on 2026-08-28, after UI-018 closed the first
modernization pass. Each commit message carries its own rationale.

| # | Commit | What |
|---|--------|------|
| UI-019 | `21e8c6c` | Waveform splash animation, crossfade to app root |
| UI-020 | `7878289` | Warm tan/gold accent rebrand; live state folded onto the accent |
| UI-021 | `91c30b0` | Splash polish — full-width marching waveform |
| UI-022 | `ac949d4` | `NavigationBarItem` replaced by a floating pill bottom nav |
| UI-023 | `d30ca36` | Live backend pill; idle meter matched to the splash waveform |
| UI-024 | `c481092` | Slate/taupe reference palette adopted in the dark theme |
| UI-025 | `2469da5` | Idle waveform tightened and moved onto the accent |
| UI-026 | `ea7f9e6` | Splash glow re-centred on the mic it haloes |
| UI-027 | `8a3bde5` | Idle/live capture states crossfaded instead of hard-cut |

Three of these regressed earlier fixed tickets, caught later by review:
UI-022 dropped UI-004's touch target and UI-005's semantics; UI-020 and
UI-024 both missed the launch-window duplicate of UI-003. See UI-028.
UI-023's copy-paste of the waveform was undone by UI-029.

## Note for the next interactive pass

A ticket written *before* the work can disagree with the result, which is
the only reason to have one. A ticket written after cannot. If a run of
interactive changes is worth tracking, open the ticket at the start of
it, or accept the commits as the record — but don't manufacture the
paperwork afterwards.
