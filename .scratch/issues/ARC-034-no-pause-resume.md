# ARC-034 — A recording cannot be paused

- **Severity:** medium
- **Status:** open
- **Area:** `recording/RecordingForegroundService.kt`, `ui/RecordScreen.kt`

## Problem

Stop is the only control. A break in a meeting means either recording the
break — which the five-minute silence auto-stop will then end, splitting the
meeting in two — or stopping and starting a second recording that has to be
reconciled by hand afterwards.

The WAV format supports it trivially: pausing is not writing chunks. The
service already owns the writer gate and the silence detector, both of which
simply stop being fed.

## Fix

A pause action on the record screen and in the notification, that stops feeding
`writeChunk` and freezes the elapsed counter and the silence detector, without
touching `AudioRecord` (so no re-acquisition of the mic and no gap in the
capture pipeline).
