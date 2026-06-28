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

## Owner decisions (2026-06-27) — authoritative

These answers from the owner override anything in the docs they touch:

- **Q1 — Acoustic parity is a SEPARATE effort, not a gate.** The goal now is *a working version
  of some kind*, not perfect parity. Be attentive to choices that would *preclude* future parity,
  but do not block progress on it. (00 §2.4 and the old G8 are demoted accordingly.)
- **Q3 — Buffer size: prefer 4096, default 8192, never above 8192.** 4096 (≤ 18.6 ms ratio-1) is
  preferred *if it is not a performance problem*; 8192 (≤ 37.2 ms) is an acceptable default;
  anything **above 8192 is out of bounds** — 37 ms is already large for the future goal of live
  controller-driven automation during playback.
- **Q2 — Incremental-behind-a-flag is fine, but NOT an excuse for timidity.** "We are replacing a
  system here; substantial change is inevitable. Do not let backwards compatibility turn into a
  blocker for progress." The flag exists to de-risk parity comparison, not to preserve the old
  path at the cost of doing the real work.
- **On the `stream` idioms (03):** the owner is skeptical of an elaborate new top-level
  `stream`/`realtime`/`ahead..by` language and notes a layer already "runs ahead" of the layers
  below it — so a **simpler annotation/wrapper** (`stream(a)`, `use_stream(a, …)`, a buffered-edge
  marker) may generalize better. Lead with the simplest spec that works; let the implementation be
  the judge. Watch for chances to make the PDSL language spec *clearer*, not heavier.

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

## The one-sentence thesis (updated by measurement, 2026-06-27)

**Measured:** the a1/a2/a3 ring decoupling already works — the real-time tick *never* waits on a2
(`hotAwait ≈ 0.01 ms`), so a2 is **not** the bottleneck and the prior "a2-bound / 5× needs an a2
kernel redesign" framing is false; the entire tick is the **a3 PDSL mixdown forward** (36.9 ms
@4096 / 57.5 ms @8192), which exceeds the 5× budget and scales sub-linearly with frame count —
the signature of **fixed per-buffer dispatch overhead**. So the 5× lever is **reducing the a3
mixdown-forward's dispatch/encoding overhead by fusing the PDSL mixdown graph into fewer kernels**
(a better-PDSL-platform gain), with the run-ahead-stream construct as the durable home for the
already-working decoupling. Receipts: [04 §0](04_FEASIBILITY_GATE.md),
[02](02_GROUND_TRUTH_ARCHITECTURE.md), via `PdslHotPathBreakdownTest`.

> **Confirmed + narrowed (2026-06-28).** An independent session re-measured this thesis (a3
> `compiled.forward` = ~98% of the steady-state tick, fixed per-dispatch overhead; runs `c87241fb`
> / `074fecbf` / `f4ac91ad`) and **ruled out** a "cross-scene kernel cache is broken" theory raised
> mid-stream (cache reuse is flawless — a second scene recompiles zero kernels; run `68bfcc17`, see
> `HANDOFF_2026-06-28.md §8`). The 5× lever is unchanged: cut the a3 forward's per-dispatch
> encode/arg-bind overhead — [05 Phase 2](05_MIGRATION_PLAN.md) localizes it to `MetalCommandRunner`.
