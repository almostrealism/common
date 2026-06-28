# 05 — Migration Plan (phased, with mechanical gates)

> Each phase has an **entry condition**, a body of work, and an **exit gate** that is a machine
> check, not a narrative. No phase starts until the prior phase's gate is green. The ordering is
> deliberately *measurement-first*: the entire performance strategy is gated on one decomposition
> measurement (Phase 1) so we never spend a 14-hour block tuning a structure we have not shown can
> reach the target. All work is behind flags, parity-checked at each step, with CI on the exact
> commit as the only ground truth (integrity history demands this — 07/T7-T8).

## Phase 0 — Pinned harness + instruments (no feature work)

**Entry:** plan approved.
**Work:**
- Build the pinned, CI-identical proving harness (07/T1): the dense scene (seed + committed
  `pattern-factory.json` + committed/seeded arrangement), default driver/flags, real samples,
  with graceful CI skip when the library is absent and a loud local failure on accidental
  silence. Add a sparser scene for headroom.
- Add the render-once / placement instruments (07/T2): a `sumToDestination` dispatch counter
  (count + nanos) and the thread-tagged synthesis counter; wire the existing
  `cacheHits/cacheMisses/cachePuts`, `evalNanos`, `marshalNanos`, and `pdslTickStageTiming` into
  one breakdown report.
**Exit gate:** the harness reproduces the *same element set* and the *same per-stage breakdown
shape* across ≥3 runs of the pinned dense scene (G7 determinism); the breakdown report prints all
five terms (synth/place/gather/marshal/mix) with date/machine/flags. No claim about *values* yet.

## Phase 1 — Decomposition measurement + feasibility verdict (THE GO/NO-GO)

**Entry:** Phase 0 gate green.
**Work:**
- Produce the full attributed cost breakdown (04 §2) on the pinned dense scene, end-to-end, at
  the production buffer size. Confirm render-once empirically: synthesis misses ≈ distinct
  elements, **not** ≈ active-notes × windows.
- Compute the ceiling (04 §3) and record the gate verdict.
**Exit gate — DONE (2026-06-27): PIVOT to the a3 forward.** The measurement
([04 §0](04_FEASIBILITY_GATE.md)) showed `hotAwait ≈ 0.01 ms` (a3 never waits on a2 — a2 is **not**
the bottleneck) and the entire tick is the **a3 mixdown forward** (36.9 ms @4096, 57.5 ms @8192),
which exceeds the 5× budget at both sizes and scales sub-linearly with frame count (fixed
dispatch overhead). So the gate took the **"mix alone exceeds budget → re-scope to the a3
forward"** branch. The decoupling invariant is *measured*, not just asserted; the prior
"a2-bound / 5× needs an a2 kernel redesign" framing is refuted. **a2 placement batching is
demoted to a secondary optimization; the primary 5× lever is reducing the a3 forward's per-buffer
dispatch/encoding overhead (PDSL kernel fusion).** This gate was the project's central decision
and is now recorded with its receipts (`PdslHotPathBreakdownTest`).

## Phase 2 — Reduce the a3 mixdown-forward dispatch overhead (the primary 5× lever)

**Entry:** Phase 1 = PIVOT to a3 forward (done).
**Work:**
- **2a — Profile the forward. DONE (2026-06-27).** Two run-time points give a linear fit:
  **`forward ≈ 16.3 ms fixed dispatch overhead + 0.005 ms/frame`** (frame-proportional DSP =
  20.6 ms @4096, 41.2 ms @8192). The `pdslTickProfile` graph has **48,767 operation nodes** and
  takes ~22 s to **compile** (one-time; the profile's durations are compile-dominated — cross-
  checked because "PDSL Automation Refresh" reads 28% there but only 0.45 ms/tick at run). The
  heaviest forward subtrees are the **per-channel FIR convolutions** (`multiOrderFilter`,
  "fir layer (6,8192)") and the **reverb `delay_network`**. Two consequences:
  - **At 4096 (preferred), the fixed 16.3 ms dispatch overhead alone is ~88% of the 18.6 ms 5×
    budget** ⇒ cutting the per-buffer **dispatch count** (graph fusion) is *mandatory* for 4096,
    independent of the FIR.
  - **At 8192, the frame-proportional FIR cost (41 ms) dominates** ⇒ optimizing/fusing
    `multiOrderFilter` matters most there.
  - The 22 s compile of the 48,767-node graph is a one-time spike ⇒ kernel pre-warm (R4) for
    run-to-run consistency, separate from the steady-state run cost.
- **2b — Fuse / reduce dispatches.** Cut the per-buffer dispatch count: fuse channel-uniform
  stages (vectorized `for each channel` is already on — verify it actually collapses to one
  kernel), fuse adjacent elementwise stages (clip→fir→scale), and reduce the
  `delay_network`/`feedback` per-line dispatch fan-out. These are PDSL-platform / compiled-model
  improvements ("more GPU parallelism / better PDSL platform"), **not** "do less DSP."
- Keep changes behind a flag; the legacy forward stays for A/B.
**Exit gate:** on the pinned dense scene (efx+reverb ON), `hotForward` drops below the 5× budget
(≤18.6 ms @4096 / ≤37.2 ms @8192) — or, if it lands between ratio-1 and 5×, the *measured*
remaining gap is attributed and the next reducible dispatch is named; **same-output correctness**
holds (the refactor must not change the rendered audio — a regression check, distinct from
acoustic parity); build validator + relevant tests green in CI. Secondary: optionally batch a2
continuing-note placement via `buildScatterAdd` (03 §4) to widen a2 headroom, but only if a2
ever threatens to stop hiding behind the forward.

## Phase 3 — The PDSL `stream` construct + migrate a1/a2/a3 onto it (behind a flag)

**Entry:** Phase 2 gate green (the perf fix works as a Java change; now make it structural).
**Work:**
- Extend the grammar/AST (`PdslNode.Program`) with `stream` / `realtime` / `ahead … by …`
  declarations; resolve them in `PdslInterpreter` into a stream graph (03 §3).
- Generalize `PatternRenderStream` into the reusable stream **runtime** (rings, producer threads
  / cooperative scheduler, back-pressure, content-keyed render-once, epoch-based live-swap —
  R2/R3/R6), driven through the single Metal command runner (R1).
- Re-express a1/a2/a3 as `patterns` / `rendered = render_ahead(patterns)` / `out =
  mixdown_master_wet(rendered, …)`; cut the hand-built `createPdsl` wiring over to the construct.
**Exit gate:** the pipeline runs entirely on the stream construct; G3 (2-min all-channel
non-silent real-time) passes; the defining invariant holds *by construction* (the runtime, not a
hand-check, guarantees a2 never blocks a3); parity holds; legacy `PatternRenderStream` wiring
retired only after parity is signed off.

## Phase 4 — Stereo + effects + automation (the proving configuration)

**Entry:** Phase 3 gate green.
**Work:**
- True stereo as the sink shape (per-channel pan in `out`, one forward — R9); efx on; per-buffer
  automation verified audibly adequate (R7) or upgraded to a finer automation stream if parity
  (G8) demands.
**Exit gate:** the 2-minute proving run passes with **efx ON and stereo ON** (G4 — L≠R asserted).
No acoustic-parity sign-off is required here (owner Q1); the design review only confirms the
wet/reverb/stereo paths still *exist* so future parity is not precluded.

## Phase 5 — 5× + consistency (close out the acceptance suite)

**Entry:** Phase 4 gate green.
**Work:**
- Kernel pre-warm for the (few, enumerable) stream kernel shapes in setup, eliminating the
  first-encounter compile spike (R4).
- Run the full acceptance suite on the pinned dense scene through CI.
**Exit gate:** **G1–G7 all green** (07/00; G8 parity is demoted, owner Q1): render-once,
a3-clean, 2-min all-channel non-silent, efx+stereo on, **≤ 0.2 end-to-end at the production
buffer (4096 preferred, 8192 acceptable)**, consistent across ≥3 runs (no per-tick outlier over
budget after warmup), determinism. Done = this gate, in CI, on the exact commit.

## Cross-phase rules

- **Substantial change is expected; flags de-risk, they do not preserve (owner Q2).** We are
  replacing a system. The flag exists so we can A/B the new path against the old during
  development — **not** so we tiptoe around the old structure. If doing the real work means
  significantly rewriting `PatternRenderStream` / `createPdsl` / the per-note assembly, do it. The
  named failure mode to avoid here is *timidity*: declining the necessary change to protect
  backwards compatibility. Backwards compatibility is never a reason to not do the work.
- **Acoustic parity is NOT a per-phase gate (owner Q1).** Phases 2–3 carry a *same-output
  correctness* check (the refactor must not change what the existing path produces — a
  regression check, cheap and legitimate), but perceptual parity with the *released* sound is a
  separate effort; do not let it gate progress, and do not rewrite docs to claim it.
- **No base-branch test edits, no CI-gate weakening, no forced-driver/forced-flag "passes"**
  (07/T8). A phase gate that "passes" only under a diagnostic config has not passed.
- **The ledger (01) and these docs update in the same commit as the code** they describe.
- If any phase's measurement contradicts a premise here, **stop and update the plan** — do not
  push the premise through. The plan is falsifiable too.
