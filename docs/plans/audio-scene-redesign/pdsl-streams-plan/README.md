# PDSL Run-Ahead Streams — Implementation Plan (from-scratch replan)

> **Status:** planning, opened 2026-06-27. Authored as a deliberately separate document set
> from the older `../*.md` files in this folder, because those older docs were written by the
> prior (failed) attempt and **must be treated as untrusted claims, not facts** (see the Prime
> Directive below). This subfolder is the authoritative plan going forward; the sibling docs are
> evidence to be verified, not foundation to build on.

This plan exists because a ~14-hour effort to make `AudioScene` render in real time stalled,
and the project owner's post-mortem was explicit about *why*: a failure to keep fact separate
from hypothesis, and incrementally tweaking a structure instead of recognizing the right
abstraction. The right abstraction — the owner's words — is a **PDSL platform capability**:
independent *streams*, separately defined, with dependencies, where a producer stream can *run
ahead* of a real-time consumer so the consumer's forward pass always finds its input ready.
"Render pattern-element audio ahead of the mixdown" is one *special case* of that capability.

## The Prime Directive (read before touching anything)

**Every quantitative or causal statement in this plan must carry a receipt produced in the
current working session** — a test you ran, a measurement you took, or a `file:symbol` you
read. Any statement whose only support is a prior planning doc or a recalled memory is stamped
`UNVERIFIED` and **may not be used as a premise.** The dominant historical failure mode on this
project, across at least five sessions, was a throwaway statement becoming an "uncontestable
fact" that an entire edifice was then built on. The claim-verification ledger
([01_CLAIM_VERIFICATION_LEDGER.md](01_CLAIM_VERIFICATION_LEDGER.md)) exists to enforce this.

If you are a later session reading this: the moment you catch a load-bearing claim here with no
receipt, that is the failure happening. Re-derive it or delete it.

## Reading order

| # | Doc | What it is |
|---|---|---|
| 00 | [00_OBJECTIVE_AND_ACCEPTANCE.md](00_OBJECTIVE_AND_ACCEPTANCE.md) | The objective in the owner's terms, the in-scope constraints, and the **un-fakeable** acceptance gates. Start here. |
| 01 | [01_CLAIM_VERIFICATION_LEDGER.md](01_CLAIM_VERIFICATION_LEDGER.md) | Every load-bearing claim from the old docs/memories, each marked verified-true / verified-false / unverified, with the receipt or the test that resolves it. |
| 02 | [02_GROUND_TRUTH_ARCHITECTURE.md](02_GROUND_TRUTH_ARCHITECTURE.md) | What the code **actually does today** (a1/a2/a3 wiring), from source, with `file:symbol` receipts. The decisive finding: a2 renders per-buffer-window, not per-element-once. |
| 03 | [03_PDSL_STREAMS_DESIGN.md](03_PDSL_STREAMS_DESIGN.md) | The run-ahead-stream construct: PDSL syntax/semantics (the north star, expressed *in* PDSL), runtime model, scheduling under Metal serialization, rings, back-pressure, live-swap, stereo as a sink shape. |
| 04 | [04_FEASIBILITY_GATE.md](04_FEASIBILITY_GATE.md) | The feasibility analysis run honestly on the **render-once** structure: irreducible work vs. structural overhead, and the ceiling computation that says whether the target is reachable. |
| 05 | [05_MIGRATION_PLAN.md](05_MIGRATION_PLAN.md) | Phased execution, each phase with mechanical entry/exit gates. |
| 06 | [06_RISKS_AND_OPEN_QUESTIONS.md](06_RISKS_AND_OPEN_QUESTIONS.md) | The up-front issue register: every hard problem named now, with a decided approach or a resolution plan. |
| 07 | [07_TOOLING_AND_GUARDRAILS.md](07_TOOLING_AND_GUARDRAILS.md) | The tooling that keeps the work honest: the pinned CI-identical harness, the render-once counter assertion, the mechanical acceptance gate, anti-drift measures. |

## The one-sentence thesis

The current a2 layer is decoupled onto a thread but still does **per-buffer-window** work
(it clears a one-buffer output and re-sums every overlapping note each window — receipt in 02),
so a note that lasts K buffers is touched K times; the fix is to render **each element's audio
once** and make "produce-ahead-and-reuse" a first-class PDSL stream construct, at which point
the per-tick cost collapses to mixing pre-rendered audio and the real-time target should follow
**by construction** rather than by tuning.
