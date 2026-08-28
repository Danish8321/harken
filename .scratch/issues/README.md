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

## Suggested order

UI-001 (ships broken now) → UI-003 → UI-004 / UI-005 → UI-002 (largest) → UI-007 / UI-008.
