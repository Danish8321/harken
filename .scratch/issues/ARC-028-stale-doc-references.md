# ARC-028 — Comments point at files that no longer exist

- **Severity:** low
- **Status:** open
- **Area:** `speech/OnDeviceTranscriber.kt:19`, others

## Problem

`OnDeviceTranscriber.kt` line 19 refers the reader to `network/HarkenApi.kt`
for the shape of a transcribed segment. That file was deleted when Retrofit
went (ADR-0011). The comment is now a dead end that costs a reader a search
before they conclude it is stale.

## Fix

Sweep the comments for references to deleted files and either update them to
the surviving type or delete the sentence.
