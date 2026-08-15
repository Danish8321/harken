# Cost model

What Harken costs to run, by how much it is actually used. Written to make the
session-limit decisions in ADR-0006 concrete: the limits exist because of the numbers
below, not because bounded is tidier than unbounded.

> **Verify before budgeting.** Rates are region-specific and change. The authoritative
> source is the [Azure Speech pricing page](https://azure.microsoft.com/pricing/details/speech/).
> The quota figures here are from Microsoft's
> [quotas and limits doc](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits)
> (checked 2026-08-15). The scenario arithmetic is mine — recompute it against your own
> region's rate.

---

## What actually costs money

| Component | Cost | Why |
| --- | --- | --- |
| **Azure Speech, real-time STT** | **~$1.00 per audio hour** (S0) | The only meter |
| Ollama + Gemma (summaries) | $0 | Runs locally |
| SQLite | $0 | A file on disk |
| Hosting | $0 today | Runs on your own machine (ADR-0001, ADR-0005) |

**The billable unit is an audio hour — wall-clock time a recognizer stays open.** Not
requests, not words, not transcript length. A silent hour with the microphone open costs
the same as an hour of dense speech. This single fact drives everything below.

## Tiers

| | Free (F0) | Standard (S0) |
| --- | --- | --- |
| Included | ~5 audio hours/month | none — pay per hour |
| Rate beyond that | n/a (blocked) | ~$1.00/audio hour |
| **Concurrent requests** | **1 — not adjustable** | 100 default, adjustable |
| Resources per subscription | 1 F0 | many |

The concurrency row is the one that matters for Harken's scope. **F0 permits exactly one
recognizer at a time.** Two family members captioning simultaneously is not a
rare edge case — it is a Tuesday evening. On F0 the second person's session fails, and it
fails mid-attempt rather than with a clear "you are over quota". That is why ADR-0006
moves to S0 before family rollout, not because of the 5-hour allowance.

---

## Usage scenarios

At **$1.00/audio hour**. Adjust proportionally for your region.

| Scenario | Recording | Hours/month | Cost/month |
| --- | --- | --- | --- |
| **Proving it works** — a few test sessions | 15 min × 8 | 2 h | **$0** (within F0) |
| **Solo learner** — 2 lectures/week, 90 min each | 3 h/week | ~13 h | **~$13** |
| **Solo daily driver** — 1 h of meetings each workday | 1 h × 22 | 22 h | **~$22** |
| **Family, light** — 4 people × 5 h each | — | 20 h | **~$20** |
| **Family, heavy** — 4 people × 15 h each | — | 60 h | **~$60** |

Nothing here is alarming. The alarming numbers are the accidents.

## The accident, which is the real reason for limits

ADR-0003 keeps the microphone alive with the screen locked, because captioning a lecture
from a pocketed phone is the daily-driver case. The cost of that decision: **backgrounding
the app was the only natural stop signal, and it is gone.** A Session ends when someone
remembers to end it.

One forgotten session, phone in a pocket until the battery dies:

| Guardrail | Session runs for | Cost of that one incident |
| --- | --- | --- |
| **None** (today) | ~8 h | **~$8.00** |
| Session Cap 2 h | 2 h | ~$2.00 |
| **Silence Timeout 5 min** | 5 min | **~$0.08** |

A hundredfold difference on a single mistake, from a timer. And mistakes repeat: one
forgotten session a week, unguarded, is **~$32/month of silence** — more than the family-light
scenario spends on actual use.

**Why both limits, not one:**

- **Silence Timeout alone** misses a noisy room. Background chatter keeps producing Final
  Results, so the silence timer keeps resetting and never fires. The session runs on.
- **Session Cap alone** lets a pocketed phone bill for the full cap — 2 hours, ~$2.00 —
  before it trips. The silence timer would have caught it in 5 minutes for ~$0.08.

Each covers the other's blind spot. That is the whole argument for two timers instead of
one, and it is why the cap is a backstop rather than the primary control.

## Choosing a Session Cap

The cap the user picks before starting bounds the worst case for that session:

| Cap | Worst-case cost of one forgotten session |
| --- | --- |
| 1 h | ~$1.00 |
| 2 h (default) | ~$2.00 |
| 4 h | ~$4.00 |
| none | bounded only by Silence Timeout — or by the battery, in a noisy room |

`none` exists so a genuine all-day recording is not truncated. It is the one path where a
session can still run up a real bill, which is why it is a deliberate choice and not the
default.

---

## Keeping an eye on it

1. **Set an Azure budget alert** on the subscription — Cost Management → Budgets. Free,
   two minutes, and it is the only thing that tells you about a problem you did not
   predict. Do this when you move to S0.
2. **Watch the notification.** Live elapsed time on the recording notification (ADR-0006)
   is the per-session view, on the surface the user actually sees when the screen is
   locked.
3. **Check Azure's own metrics** — the Speech resource's *Metrics* blade shows audio hours
   consumed. Azure's numbers are the billing truth; anything Harken reports is a
   convenience.

## Not applicable today, worth knowing

**Batch transcription costs roughly $0.18/audio hour** — about a fifth of real-time. It
does not fit Harken: batch uploads a finished file and returns a transcript later, and
Harken's product is the live caption. But if a "transcribe this recording I already have"
feature ever appears, it should not go through the real-time meter.

---

Sources:
- [Azure Speech pricing](https://azure.microsoft.com/pricing/details/speech/)
- [Azure Speech quotas and limits](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits)
