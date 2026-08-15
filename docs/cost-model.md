# Cost model

What Harken costs to run. Rewritten for [ADR-0007](adr/0007-record-then-transcribe.md)
and [ADR-0008](adr/0008-local-whisper-first.md) — the record-then-transcribe design
changes not just the amount but the *shape* of the cost.

> **Verify before budgeting.** Rates are region-specific and change. The authoritative
> source is the [Azure Speech pricing page](https://azure.microsoft.com/pricing/details/speech/).
> Quota figures come from Microsoft's
> [quotas and limits doc](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits)
> (checked 2026-08-15). The arithmetic is mine — recompute against your own region.

---

## MVP 1: zero

| Component | Cost |
| --- | --- |
| Whisper transcription | $0 — local, on the GPU you own |
| Ollama + Gemma summaries | $0 — local |
| SQLite | $0 — a file |
| Hosting | $0 — your own machine |

**Nothing meters.** No account, no key, no quota, no budget alert, no forgotten-session
risk. Recording all day costs a flat battery and some disk.

The real costs of MVP 1 are not billed:

- **Electricity and wear** on your own machine. Small, but not literally zero.
- **Time.** Transcription takes minutes per recording hour on a 3050 (unmeasured — see
  ADR-0008; measuring it is a gate on the phone client).
- **Disk.** Recordings plus a ~1.5 GB model file. See storage below.

## MVP 2: Azure, and why batch changes everything

| | Real-time (old design) | Batch (ADR-0007) |
| --- | --- | --- |
| Rate | ~$1.00/audio hour | **~$0.18/audio hour** |
| Billed for | wall-clock time the recognizer is open | audio content submitted |
| A silent hour costs | the same as a busy one | the same — but you never submit one |
| A forgotten session costs | hours of billing | one upload you can delete first |

The rate is about a fifth. The bigger change is the second row. Under real-time, the
meter ran on wall-clock time you were not in control of, which is why ADR-0006 needed two
timers, server-side enforcement, and a sync contract just to bound the damage. Under
batch, you hold a finished file and decide whether to submit it. **The runaway case stops
existing**, and with it the machinery that guarded against it.

### What MVP 2 actually costs

At ~$0.18/audio hour, and only for recordings you choose to send to Azure rather than
transcribe locally:

| Usage | Hours/month | Cost/month |
| --- | --- | --- |
| Solo learner — 2 lectures/week, 90 min | ~13 h | **~$2.34** |
| Solo daily driver — 1 h each workday | 22 h | **~$3.96** |
| Heavy — 3 h/day, every day | 90 h | **~$16.20** |

Compare the old design: the same solo-daily-driver case was ~$22/month, and one forgotten
session could add ~$8 on its own.

### The Visual Studio Enterprise credit

$150/month. At $0.18/audio hour that is ~830 audio hours — far more than one person can
generate. Azure is effectively free for a single user.

Read the terms before relying on it: **individual dev/test use by the subscriber only, no
rollover, no SLA, and instances suspend after 120 hours of continuous running.** It
cannot legitimately fund family or public use. It is runway, not a funding model
(ADR-0008).

## Storage, which is now the real budget

Cost moved from a meter to your disk. Encoding is the lever:

| Encoding | Per hour of audio | 100 hours |
| --- | --- | --- |
| Raw PCM, 16 kHz mono 16-bit | ~115 MB | ~11 GB |
| Opus, speech bitrate | ~10 MB | ~1 GB |

Roughly a tenfold difference, and it lands twice — on the phone before upload, and on the
backend after. On a phone that is the difference between a recording you can hold for a
week and one you must upload immediately.

**Decided so far:** the console slice records **WAV** (16 kHz mono PCM), because it is
what Whisper wants and needs no encoder, and ~115 MB/hour is irrelevant on a PC. Opus is
an open question for the phone slice, where it stops being irrelevant.

**Recordings are kept after transcription**, not deleted. Audio is the only artifact that
cannot be recreated, and re-transcribing with a better model or a different Provider needs
it. So backend disk grows with total hours ever recorded, not with hours pending:

| Total recorded | WAV on the backend | Opus on the backend |
| --- | --- | --- |
| 50 h | ~5.8 GB | ~500 MB |
| 200 h | ~23 GB | ~2 GB |
| 500 h | ~58 GB | ~5 GB |

At which point retention becomes a real policy rather than a default. It is not one yet.

Plus a ~1.5 GB Whisper model and ~3 GB of Gemma on the backend, once each.

## What the limits are for now

Silence Timeout and Session Cap survive from ADR-0006 with the cost rationale removed.
They now bound **battery and device storage**:

| Guardrail | A forgotten recording costs |
| --- | --- |
| None | a flat battery, and hours of audio on disk |
| Session Cap 2 h | ~2 h of file |
| Silence Timeout 5 min | ~5 min of file |

Worth having. Not urgent, and no longer needing server-side enforcement — the backend
holds nothing open while a phone is recording.

## Keeping an eye on it

- **MVP 1** — nothing to watch. Check free disk space occasionally.
- **MVP 2** — set an Azure budget alert (Cost Management → Budgets) when you first send
  anything to Azure. Free, two minutes. The Speech resource's *Metrics* blade shows audio
  hours consumed; Azure's numbers are the billing truth.

---

Sources:
- [Azure Speech pricing](https://azure.microsoft.com/pricing/details/speech/)
- [Azure Speech quotas and limits](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits)
