# 04 — Feasibility Gate

> Per the falsification-gate method (`../../falsification-gate/FEASIBILITY.md`): before
> optimizing toward a quantitative target, establish that the *structure can reach it* — and the
> cheapest way to establish that is to try, cheaply, to prove it **can't**. This document runs
> that gate for the run-ahead-streams design. **It deliberately does not reach a verdict yet**,
> because reaching one from the prior docs' numbers — every one of which is `unverified` (01) —
> would be the exact failure this gate exists to prevent. It specifies the one decomposition
> measurement that produces the verdict, and the decision rule that consumes it.

## 0b. CORRECTION (2026-06-28) — short-warmup masked a2; sustained is ~2×, both layers bind

**The §0 result below (8-tick warmup) was an artifact and overstated headroom.** Re-measured with
24-tick warmup + 12 profile ticks (`PdslHotPathBreakdownTest`, same densest seed=58, efx+reverb
ON), draining the 8-slot render-ahead ring toward the *sustained 2-minute* regime the goal
actually requires:

| Buffer | sustained tick | ratio | realtime× | hotAwait (a3 waits a2) | hotForward |
|---|---|---|---|---|---|
| 4096 | ~44.6 ms | 0.48 | **2.08×** | **3.16 ms** | 40.5 ms |
| 8192 | ~93.8 ms | 0.50 | **1.98×** | **23.5 ms** | 69.3 ms |

Per-tick at 8192 swings **60–125 ms** (severe variance — the consistency requirement is at risk).

**What this corrects:** the §0 claim "hotAwait ≈ 0.01 ms, a2 never blocks, a3 is the whole
bottleneck" was true only for a *short* window where the prefilled ring hid a2's throughput
deficit. In sustained operation a2 and a3 **share the single Metal command runner**, so the
per-buffer wall is bounded by *total* GPU work (a2 + a3); a2 is slightly slower than a3 consumes,
the ring empties, and a3 waits a2 (3 ms @4096, 23 ms @8192). **Both layers are binding in
sustained operation**, and the system is **~2× realtime, not the ~2.5–3.2× the short run
suggested.** Reaching 5× needs ~2.4–2.5× off *total* per-buffer GPU work **and** variance control.

**Epistemic note (the project's core failure mode, caught here):** a short-warmup throughput
benchmark with a prefilled ring *lies* — it hides the producer deficit. Always warm past ring
depth, or run the full duration. The §0 numbers are retained below as the cautionary record, not
as the operative result.

---

## 0. PHASE-1 MEASURED RESULT (2026-06-27, SHORT WARMUP — see 0b correction) — the gate has run

The decomposition was measured (M1, local hybrid Metal, fresh full-closure build, depth 2, **efx
+ reverb ON**, real curated library, densest curated genome seed=58 / 1126 elements) via the new
`PdslHotPathBreakdownTest`, which reads `AudioSceneRealtimeRunner.hotAwaitNanos` /
`hotForwardNanos` (added counters around `awaitSlot` / `compiled.forward`):

| Buffer | budget | tick | **hotAwait** | **hotForward (a3 mixdown)** | a2 total (producer) | realtime× |
|---|---|---|---|---|---|---|
| 4096 | 92.88 ms | 37.78 ms | **0.01 ms** | **36.91 ms** | 16.27 ms | 2.46× |
| 8192 | 185.76 ms | 58.68 ms | **0.01 ms** | **57.52 ms** | 35.03 ms | 3.17× |

Render-once confirmed (cacheMisses 5–12 vs cacheHits 221–278).

**Verdict: PIVOT — the a3 mixdown forward is the bottleneck; a2 is not.**

- **`hotAwait ≈ 0.01 ms` at both sizes ⇒ a3 never waits on a2.** The ring decoupling already
  works; a2 runs ahead and never blocks a3. **a2 is not on the critical path.** This *measures*
  the decoupling invariant the prior docs only asserted — and it refutes "the system is
  a2-bound."
- **The entire tick is the a3 mixdown forward** (37–58 ms). It **exceeds the 5× budget** at both
  sizes (need ≤18.6 ms @4096, ≤37.2 ms @8192). Per the decision rule below, *mix alone exceeds
  budget → re-scope to the a3 forward.*
- **The forward scales sub-linearly with frame count** (2× frames → only 1.56× forward) — the
  signature of **fixed per-forward dispatch/encoding overhead**, not frame-proportional GPU
  compute (consistent with the existing benchmark's note that compiled-op GPU time is "almost
  none of the tick wall"). So the gap is **incidental dispatch overhead, reducible by fusing the
  PDSL mixdown graph into fewer/larger kernels** — a *better-PDSL-platform* gain, exactly the
  objective's allowed lever. The gate **passes** in the sense that the structure can reach the
  target by reducing dispatch overhead; it **pivots** in the sense that the target is the a3
  forward, not a2 placement.

**Consequence for the prior plan:** the a2 placement batching (03 §4) is now a *secondary*
optimization (it gives a2 headroom but does not reduce the tick, since a2 is already hidden
behind the forward). The primary 5× work is the **a3 mixdown-forward dispatch reduction**. The
remaining open sub-question (what specifically makes the forward 37–58 ms — op count, the reverb
`delay_network`, the per-channel FIRs) is the immediate Phase-2 profiling step.

> Caveat held honestly: this is a local M1 measurement (not CI) and **dual-mono** (true stereo
> will *add* forward cost — R9). It is reproducible via `PdslHotPathBreakdownTest`; CI on the
> exact commit remains the acceptance authority.

---

## 1. The core question

> Is the gap to ~5× a **constant factor within the right structure**, or is the **structure
> itself the ceiling**?

The prior attempt answered "structure is the ceiling, and the structure is the batched render
kernel" — concluding 5× needs per-row-independent source lengths + in-kernel input generation.
[02](02_GROUND_TRUTH_ARCHITECTURE.md) gives a strong source-level reason to doubt that answer:
the per-buffer a2 cost is dominated not by the synthesis kernel's shape but by **per-note
placement** — a Java `forEach` issuing one GPU sum per active note per window. If so, the gap is
a *structural overhead removable by batching the placement*, not a kernel-algorithm ceiling — a
very different (and much cheaper) conclusion. **But "if so" is the whole question, and it is
unmeasured.** The gate measures it.

## 2. The decomposition to measure (one thorough pass, not serial guesses)

The falsification method's first rule: decompose the *whole* cost once, up front, rather than
serializing one-hypothesis-per-run probes (the prior session's dominant time sink). On the
**pinned dense scene** (07/G7), at the production buffer size, attribute the end-to-end per-tick
cost into:

| Term | What it is | How to measure (instrument exists?) |
|---|---|---|
| **synth** | per-element synthesis (resample/env/filter) | `NoteAudioCache.cacheHits/cacheMisses/cachePuts` (exist) + `BatchedPatternLayerRenderer.evalNanos` (exists). Confirms render-once: misses ≈ distinct elements, not ≈ active-notes×windows. |
| **place** | per-note placement sums | **add** a counter at `sumToDestination` (count + nanos); this is the suspected dominant term, currently uninstrumented as such |
| **gather** | building note destinations each tick | `BatchedPatternLayerRenderer` gather timing (partial instrumentation exists) |
| **marshal** | host→device input writes | `BatchedPatternLayerRenderer.marshalNanos` (exists) |
| **mix (a3)** | the PDSL `mixdown_master_wet` forward | `pdslTickStageTiming` stage timing (exists) — re-measure; the "DSP already under budget" claim is `unverified` (01) |

Output: a single attributed breakdown with date/machine/flags, through the pinned harness — the
artifact the prior effort never produced and the owner explicitly asked for ("dig through all
the code… make sure all the claims are actually true" + "decompose the cost once, fully").

## 3. The ceiling computation (the falsification step)

With the breakdown in hand, compute the ceiling under the design:

1. **Irreducible core under render-once + batched placement.** Imagine `place` collapses from N
   per-note dispatches to **one** scatter-add, and `synth` is strictly render-once (synth ≈
   onset-rate × per-element cost, amortized by render-ahead over sparse regions). The irreducible
   per-tick work is then: `synth_new` (a small batch) + `place_batched` (one scatter-add) +
   `mix` (the PDSL forward). Does that sum meet **≤ 37.2 ms @ 8192**?
   - **Yes** → the structure can reach the target; the gap was incidental (per-note dispatch
     overhead). The gate **passes** — build the construct. This is the outcome 02 predicts.
   - **No, and `mix` alone already exceeds budget** → the mixdown DSP is the ceiling, not a2;
     the plan pivots to the a3 forward (a different problem than the prior attempt chased).
   - **No, and `synth_new` alone exceeds budget even amortized** → element synthesis is genuinely
     too expensive; that is a real kernel-redesign finding (and only *then* does the prior "5×
     needs a kernel redesign" framing earn a receipt).
2. **Name the abstraction first (already done).** The right general construct — PDSL run-ahead
   streams — is named and sketched in [03](03_PDSL_STREAMS_DESIGN.md) before building, per the
   method's step 4.

## 4. Decision rule (mechanical, to prevent hill-climbing the wrong hill)

- **A small local gradient next to a large gap is a STOP signal, not a to-do.** If, after the
  decomposition, the proposed fix moves the end-to-end ratio only a few percent while the target
  is a multiple away, the structure is wrong — escalate rebuild-vs-tweak immediately; do not
  enter a measure→tweak→verify loop (the prior 14-hour failure mode).
- **Hypotheses are labelled, measured before acted on.** "Placement dominates" (02) is a
  *hypothesis with a structural argument*. It is confirmed by the §2 breakdown before any
  `render_ahead` implementation is committed.
- **Re-apply the gate** the moment several changes show diminishing returns vs. the gap.

## 5. Why the verdict is deferred (and that is correct)

A verdict now would have to borrow the prior docs' numbers ("eval ~24 ms under budget", "a2-bound
at 2.34×", "5× needs a redesign") — all `unverified-needs-measurement` (01), all resting on
git-ignored benchmark output. Borrowing them is precisely the throwaway-becomes-fact failure the
whole plan is built to avoid. The gate's input is the §2 breakdown, produced in Phase 1
([05](05_MIGRATION_PLAN.md)) through the pinned harness ([07](07_TOOLING_AND_GUARDRAILS.md)).
**Phase 2+ (build the construct) does not begin until the gate has a measured verdict.**

> One-line: we have a strong structural reason to believe the gap is incidental (batchable
> placement), and a strict rule not to act on that belief until the one decomposition measurement
> confirms it.
