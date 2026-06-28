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
**Exit gate (one of):**
- **PASS — placement is the dominant removable term** (02's prediction): irreducible core
  (render-once synth + batched place + mix) ≤ 37.2 ms @ 8192 in the ceiling computation → proceed
  to Phase 2.
- **PIVOT — mix (a3 DSP) alone exceeds budget** → re-scope to the a3 forward; Phases 2–3 change
  target. (Re-measure the `mixdown_master_wet` claim first — it is `unverified`, 01.)
- **PIVOT — synth alone exceeds budget even amortized** → genuine kernel-redesign finding; only
  here does the prior "5× needs a redesign" framing earn its receipt, and the plan adopts it
  *with* the measurement.
**This gate is the project's central decision.** It is recorded with its receipts and is the
thing a future session re-checks before trusting any direction here.

## Phase 2 — `render_ahead`: batched placement + structural render-once (behind a flag)

**Entry:** Phase 1 = PASS (placement dominant).
**Work:**
- Implement the batched placement: replace the per-note `renderNotes` `forEach`/`sumToDestination`
  fan-out with **one** scatter-add over `[elements, overlap]` per produced buffer (03 §4),
  GPU-parallel over elements; keep render-once synthesis (the existing cache, promoted to
  content-keyed element identity — R6, no melodic/percussion special-casing — R3).
- Keep it behind a flag; the legacy path stays for A/B.
**Exit gate:** on the pinned dense scene, the measured `place` term collapses to ~one batched
dispatch's cost (not O(active notes)); the a2 end-to-end term meets the Phase-1 ceiling; G1
(render-once) and G2 (no synth on the clock thread) counters pass; **bit/`windowed-RMS` parity
with the legacy path holds** (no audible change); build validator + relevant tests green in CI.

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
**Exit gate:** the 2-minute proving run passes with **efx ON and stereo ON** (G4 — L≠R asserted);
acoustic parity A/B pair produced and owner-signed (G8, dated).

## Phase 5 — 5× + consistency (close out the acceptance suite)

**Entry:** Phase 4 gate green.
**Work:**
- Kernel pre-warm for the (few, enumerable) stream kernel shapes in setup, eliminating the
  first-encounter compile spike (R4).
- Run the full acceptance suite on the pinned dense scene through CI.
**Exit gate:** **G1–G8 all green** (07/00): render-once, a3-clean, 2-min all-channel non-silent,
efx+stereo on, **≤ 0.2 end-to-end at 8192**, consistent across ≥3 runs (no per-tick outlier over
budget after warmup), determinism, owner parity sign-off. Done = this gate, in CI, on the exact
commit.

## Cross-phase rules

- **Behind flags throughout**; legacy path retained for A/B until the final cutover.
- **Parity is checked every phase**, not deferred to the end (the prior "rewrote docs to say
  parity" failure).
- **No base-branch test edits, no CI-gate weakening, no forced-driver/forced-flag "passes"**
  (07/T8). A phase gate that "passes" only under a diagnostic config has not passed.
- **The ledger (01) and these docs update in the same commit as the code** they describe.
- If any phase's measurement contradicts a premise here, **stop and update the plan** — do not
  push the premise through. The plan is falsifiable too.
