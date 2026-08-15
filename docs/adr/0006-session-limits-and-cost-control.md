# 6. Session limits and cost control

Date: 2026-08-15

## Status
Superseded by [ADR-0007](0007-record-then-transcribe.md) before implementation. None of
this shipped.

The premise below — that an open recognizer bills per wall-clock hour — stops being true
once the MVP records first and transcribes afterwards. Local Whisper has no meter at all,
and Azure batch bills per audio hour of *content*, so a silent recording costs almost
nothing and a forgotten one costs nothing extra.

**Silence Timeout and Session Cap survive, reduced.** They now bound phone battery and
device storage rather than spend, which changes what they are:

- Enforcement moves **server-side to client-side**. The server holds nothing open during
  a recording, so it has nothing to enforce.
- The sync contract, the absolute UTC deadlines, `SessionEnded(reason)` and the three new
  hub messages are all unnecessary. They existed so a client could not run up a bill the
  server was paying.
- Urgency drops from "required before rollout" to "worth having". A runaway recording is
  now a flat battery, not an invoice.

The tier decision (move to S0 before rollout) applies only if and when real-time
recognition returns. Batch has different quotas.

The recognition-language decision — set it explicitly rather than inherit the SDK default
— **still stands** and carries over to whichever provider is in use.

Retained in full below as the reasoning behind those surviving limits.

## Context
Azure Speech bills per audio hour of streaming recognition. The meter tracks how long a
recognizer stays open — wall-clock recording time, not request count. Harken currently
has no session length cap, no silence timeout, and no usage visibility: a Session runs
until a client stops it.

ADR-0003 makes that materially worse, correctly and on purpose. A foreground service
keeps the microphone alive with the screen locked, because captioning a lecture from a
pocketed phone is the daily-driver case. But backgrounding the app was the only natural
stop signal, and that decision removes it. A forgotten Session on a locked phone streams
until the battery dies, and every minute is billed.

Two further facts sharpen this:

- The **F0 free tier** allows roughly 5 audio hours per month and **one concurrent
  request**. One concurrent request cannot serve two family members captioning at once —
  the second fails mid-session rather than cleanly.
- The client cannot be the guarantee. A phone that crashes, loses Wi-Fi, or is
  force-stopped leaves the server's recognizer open with nobody pushing audio and nobody
  to close it. That is precisely the runaway case, and it is invisible from the client.

## Decision

**Tier.** Move to **S0 (pay-as-you-go) before family rollout.** F0 stays for proving the
system works single-user. This makes an Azure budget alert a setup step, not an optional
extra.

**Recognition language.** English (`en-US`) for now, but stated rather than inherited:
the transcriber sets the language explicitly instead of relying on the SDK default, so
the choice is visible in code. Multi-language is out of scope and untested.

**Two independent limits, both enforced server-side:**

- **Silence Timeout** — the Session ends after a configurable period with no Final
  Result. Default 5 minutes. Catches the pocketed-phone case.
- **Session Cap** — an absolute maximum duration the user chooses *before* the Session
  starts (1h / 2h / 4h / none), defaulting to 2h. Catches the case Silence Timeout
  cannot: a room with constant background noise produces recognitions that are not real
  speech, so the silence timer never fires. `none` remains available so a genuinely long
  recording is not truncated, but it is a deliberate choice rather than the default.

The two are not redundant. Each covers the other's blind spot.

**Enforcement is server-side; the client mirrors it.** The server owns both timers
because the server owns the recognizer, and the recognizer is what costs money. The
client displays countdowns and warnings, and never votes: if client and server disagree,
the server ends the Session.

**Sync contract.** The server pushes state to the client rather than the client
inferring it:

- On session start, the server echoes the limits it actually applied, as **absolute UTC
  deadlines** rather than durations. A duration forces the client to guess when its own
  clock started and drift from there; a deadline is a fact it can render from.
- On each Final Result the silence deadline moves, so the new deadline rides along with
  a message already being sent — no extra round trip.
- Before Auto-stop fires, a warning (30 seconds out) so the client can offer "keep
  going" rather than dying mid-sentence.
- On stop, `SessionEnded(reason)` distinguishing silence, cap, and user-stopped. Without
  a reason the client can only show "disconnected", which reads as a bug and costs the
  user's trust in whether the recording was kept.

**Usage visibility.** The recording notification shows live elapsed time and carries a
Stop action. When the screen is locked, that notification is the only surface the user
can see or act on, so it is where both visibility and the kill switch belong.

## Consequences
- Cost becomes bounded by design rather than by the user remembering. The worst case is
  one Session Cap, not one battery.
- Three new server→client messages and a parameter at session start: a hub contract
  change, and therefore a change every client must be updated for in the same commit.
- The server must track per-Session timers, which is state the hub did not previously
  hold. This is bounded work but it is real work, and it is the first server-side
  scheduling in the project.
- `SessionEnded(reason)` makes "the session ended" three distinguishable things, so
  `CONTEXT.md` gains **Silence Timeout**, **Session Cap**, and **Auto-stop**.
- A user who sets Session Cap to `none` opts out of the backstop. Silence Timeout still
  applies, so this is not unbounded — but it is the one path where a long recording can
  still run up a bill.
- Moving to S0 means Harken costs money per recorded hour from rollout onwards. That is
  the price of two people being able to record at once.

## Alternatives considered
- **Client-side timers only:** simplest, and worthless for the failure that matters — a
  dead client cannot stop anything, and it is the dead client that runs up the bill.
- **Hard cap only, no silence timeout:** one timer instead of two, but the common case
  (phone in a pocket, nobody speaking) then bills for hours before the cap trips.
- **Silence timeout only, no cap:** misses constant-background-noise rooms entirely,
  where recognitions keep arriving and the silence timer never fires.
- **Staying on F0:** free, but one concurrent request means the second family member's
  session fails. Free is not a feature if the app does not work.
