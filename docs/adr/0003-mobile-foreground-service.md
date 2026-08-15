# 3. Android foreground service for mic capture

Date: 2026-08-15

## Status
Accepted

## Context
The mobile client's core use case is captioning a lecture or meeting the user is
physically in, phone likely screen-locked in a pocket. Android suspends microphone
access and background work for a plain backgrounded app; only a foreground service
(with a persistent notification) can keep recording once the screen turns off.

## Decision
Slice two ships with a foreground service wrapping the mic-capture + SignalR-stream
lifecycle from the start, not as a later hardening pass. Requires `RECORD_AUDIO`
runtime permission plus a notification channel for the ongoing-recording notice.

## Consequences
- Recording survives screen-lock — the primary daily-driver scenario actually works.
- Extra Android boilerplate up front (service class, notification channel, manifest
  service declaration) that a screen-only prototype wouldn't need.
- User always sees a persistent "recording" notification while a session is live —
  intentional, not a bug: mic-capture apps must not record silently in the background.
- **Backgrounding the app was the only natural stop signal, and this decision removes
  it.** Session duration becomes unbounded: a forgotten Session on a locked phone streams
  until the battery dies. On a per-audio-hour meter that is unbounded cost, so this
  decision is what makes server-side session limits necessary rather than nice to have.
  Addressed in ADR-0006.
- The notification is the only surface visible or actionable while the screen is locked,
  so it is where the stop control and elapsed-time display belong — not just a passive
  notice (ADR-0006).
