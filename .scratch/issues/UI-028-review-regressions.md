# UI-028 — Regressions found by branch review

- **Severity:** high
- **Status:** fixed
- **Area:** `res/values*/colors.xml`, `ui/AppNav.kt`, `recording/LiveUpdateNotification.kt`, `docs/brand-guidelines.md`

## Problem

Two-axis review of `master...HEAD` (36 commits) found four defects, three
of them regressions of tickets already marked fixed. None were catchable
by `check.sh` — every one is valid, compiling code.

1. **Launch window paints the wrong ground (regresses UI-003).**
   `values/colors.xml` still held `#FAF1E1` and `values-night` `#1C1A17`
   while `ProtoColors.screenBg` had moved to `#F3F6F7` / `#2C313A`. The
   OS splash painted warm cream/near-black, then the app painted slate —
   the exact flash UI-003 closed. Missed by UI-009's palette sweep and
   again by UI-024, because neither looked in `res/`.

2. **Nav tabs lost their semantics and touch target (regresses UI-005,
   UI-004).** UI-022 replaced `NavigationBarItem` with a hand-rolled
   `FloatingTabBar` row. `NavigationBarItem` had been supplying
   `Role.Tab`, the selected state and the 48dp minimum for free; the
   replacement was a bare `clickable` with `indication = null` at ~40dp.
   TalkBack announced the tabs as unlabelled text and never said which
   one you were on — on the app's most-tapped controls.

3. **Notification painted in the deleted palette.** `RECORDING_ACCENT` /
   `DONE_ACCENT` were still Organic terracotta `#C67139` and sage
   `#7A8A5E`, with a comment pointing at symbols removed from
   `Color.kt`. The notification shade was the one surface the re-palette
   never reached.

4. **`docs/brand-guidelines.md` was false.** Added in `e8661b6`, then
   contradicted by four later commits in the same branch. Every hex and
   both font rows were wrong. UI-007 made the doc load-bearing, so a
   stale copy is quoted with confidence.

## Fix

1. Both `colors.xml` files updated to the current `screenBg`, with a
   comment naming this as the one place a palette token is duplicated
   and must be hand-edited on every re-palette.
2. `clickable` → `selectable(selected, role = Role.Tab)` with
   `LocalIndication.current` restoring the ripple, plus
   `heightIn(min = 48.dp)`.
3. Notification constants moved to `ProtoDarkColors.accent` / `.success`
   (`#BFA789` / `#8FBF9A`); comment re-pointed at `ProtoColors.kt`.
4. `brand-guidelines.md` rewritten to v2.0 against the shipped code, and
   given a standing rule that the code settles disagreements. Added the
   launch-window rule, the waveform motif, and a rule that hand-rolled
   controls must declare role and 48dp explicitly.

## Verification

- `bash .claude/scripts/check.sh` — `== check: OK ==`
- `installDebug --rerun-tasks`, on-device screenshot: nav pill sits at
  the 48dp floor, palette unchanged otherwise.
- `uiautomator dump`: active tab reports `selected="true"`,
  `focusable="true"`, bounds 94px ≈ 48dp. Before the fix no tab carried
  a selected state at all.

## Follow-ups not done here

- **UI-019..UI-027 have no ticket files** (9 commits, including two
  re-palettes and the nav replacement). They were driven by direct
  interactive requests. `.scratch/issues/README.md` says "One file per
  ticket"; the backlog needs backfilling.
- **UI-009, UI-010, UI-011 assert states the code no longer has** —
  UI-009/010 describe a palette UI-020/UI-024 deleted, UI-011's splash
  was rewritten three times. Still marked fixed.
- **Waveform phase constants are duplicated** between `SplashScreen.kt`
  and `RecordScreen.kt`. UI-023 was titled "match splash waveform" and
  copied rather than extracting; they now have to be kept in step by
  hand.
- **`ProtoColors.kt` also owns the fonts and easings**, duplicating the
  font declaration in `Theme.kt`. Two copies to keep in sync.
