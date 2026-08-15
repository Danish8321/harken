# 1. Backend-centric audio streaming

Date: 2026-08-14

## Status
Accepted

## Context
Clients (console now; mobile + browser extension later) must turn live audio into
captions and store transcripts. Two shapes were possible:

- **Client-direct:** each client talks straight to Azure Speech (lower latency, but
  Azure keys/auth pushed to every client, transcription and agent logic duplicated
  per platform, harder to secure).
- **Backend-centric:** clients stream raw audio to our own backend, which owns the
  Azure Speech recognizer, persistence, and Agents. Clients stay thin.

## Decision
Backend-centric. Clients stream audio chunks to an ASP.NET Core backend over
SignalR. The backend owns the Azure Speech recognizer lifetime, persistence, and all
Agent logic. Clients render captions and history only.

## Consequences
- Azure/AI keys stay server-side; clients hold no secrets.
- One codebase for transcription + agents; every client reuses the same contract.
- Cost: extra network hop (audio up, captions down) adds latency vs client-direct.
- Backend must manage stateful, long-lived per-Session recognizers and clean them up
  on disconnect — a real resource-leak risk to watch.
