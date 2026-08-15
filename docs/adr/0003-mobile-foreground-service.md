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
  it.** Recording duration becomes unbounded: a forgotten recording on a locked phone runs
  until the battery dies. Under ADR-0006 that was unbounded *cost*; under
  [ADR-0007](0007-record-then-transcribe.md) it is a flat battery and a multi-gigabyte
  file on the device instead. Still worth bounding, no longer urgent, and now enforced on
  the client — the server holds nothing open during a recording.
- The notification is the only surface visible or actionable while the screen is locked,
  so it is where the stop control and elapsed-time display belong — not just a passive
  notice.
- Under ADR-0007 the service wraps **capture to a local file**, not a live SignalR stream.
  Nothing about the foreground-service requirement changes: Android suspends microphone
  access for a backgrounded app regardless of where the audio goes. What does change is
  that a dropped network no longer harms a recording in progress — it only delays the
  upload.
