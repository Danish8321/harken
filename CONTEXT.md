# Context — Harken

Glossary for the Harken project. Terms only. No implementation details.

## Session
A single continuous capture from one client, from start to stop. Owns an ordered
sequence of Transcript Segments. Has a start time, end time, and a source (mic or
system audio). One Session maps to exactly one live audio stream and one recognizer
lifetime.

## Transcript Segment
A timestamped piece of recognized text belonging to a Session. Emitted as final
(not partial) recognition. Ordered within its Session by start offset.

## Partial Result
An in-progress, not-yet-finalized recognition shown live to the user as it forms.
Flickers/changes as more audio arrives. NOT persisted — only Final Results become
Transcript Segments.

## Final Result
A stabilized recognition the engine will not revise. Persisted as a Transcript
Segment.

## Caption
What the user sees live on screen: the rolling display of Partial and Final Results
as they arrive. The capture-time experience, distinct from the stored Transcript.

## Transcript
The full stored text of a Session — its ordered Transcript Segments joined. The
durable artifact the user owns and runs Agents over. The product's hero, not the
live Caption.

## Agent
An AI worker that consumes a Transcript and produces a derived output. Phase 1 has
one Agent: Summarize. Runs on-demand against a stored Transcript, not live.

## Summary
The output of the Summarize Agent over a Transcript.

## Source
Where a Session's audio comes from. Phase 1: mic (console). Later: system/tab audio
(browser extension), device mic (mobile).
