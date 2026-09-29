# Re-baseline LoRA fine-tuning backward-pass compile scaling

## Title

Re-measure and re-baseline transformer backward-pass compilation scaling after the
kernel-series / block-evaluation optimization, and revise the fine-tuning feasibility
verdict accordingly.

## Category

Performance.

## Motivation

The platform's authoritative statement on training feasibility is stale, and it is a
pessimistic one.

`docs/plans/FINE_TUNE_FAIL.md` concludes, in bold, that **"production-scale LoRA
fine-tuning is currently infeasible with the existing compilation architecture."** Its
scaling table (embed 8 → 128) shows backward-pass compilation time growing from ~38 s to
~31 min for a *single* transformer block, and it extrapolates to "days, not minutes" for
the production configuration (embed=1024, depth=16). Every downstream planning decision
that touches training — and the entire proof-of-value / self-hosted-training trajectory in
the Manager Log — inherits that verdict.

That measurement is dated **February 2026**. Its own profile analysis attributes the
backward-compile cost to matrix-multiply derivatives (`collectionProductComputation`),
gradient accumulation (`collectionAddComputation`), and heavy nested `reshape` wrapping
around index-arithmetic expressions.

Since then, the compile-time front has moved decisively. `docs/plans/CONVOLUTION_COMPILE_TIME.md`
records a resolution that landed on `feature/cl-profile-perf` (**2026-09-08**): the
kernel-series detection pass that dominated expression simplification was rebuilt around
block evaluation with early abort, structural (symbolic) series derivation, and
construction-time folds. The measured result was `convDeltaSmall` **172.1 s → 1.15 s** and
`upsample` **233 s → 4 s** — a ~150× reduction in exactly the `Sum.simplify → getSeries →
Expression.sequence` analysis cost that also dominates the backward-compile numbers in
`FINE_TUNE_FAIL.md`. Two further compile-cache changes landed on the fine-tuning path
afterward ("Stop explicit expression matrices from flooding the compile cache",
"Evaluate explicit expression matrix entries on demand").

In short: the cost model that produced the "infeasible" verdict has been substantially
optimized, and nobody has re-measured the transformer backward pass against it. The
platform may already be much closer to feasible fine-tuning than its own documentation
claims — or the wall may have simply moved to a new dominant cost. Either way, the current
number is unknown, and an unknown that gates the flagship proof-of-value goal is worth the
cost of one focused measurement pass.

This is a performance task in the truest sense: it does not add a feature, it recovers the
*truth* about the platform's most strategically important performance characteristic, and
it leaves behind a validated, reproducible baseline so the next regression is caught by a
number rather than by an anecdote.

## Scope

Concrete, ordered deliverables:

1. **Reproduce the scaling measurement on current `master`.** Use the existing harness
   `AggressiveFineTuningTest` (in `studio/compose`, `org.almostrealism.studio.ml.test`),
   specifically `testCompilationScaling()`, which already sweeps
   `{embed 8/16/32/64/128/256, depth=1}` and reports forward-compile, backward-compile, and
   first-train-step timings. Run the small-to-mid configurations (embed 8 → 64, at minimum)
   and capture the current numbers. Note the harness is marked
   `@TestProperties(knownIssue = true)`; the measurement re-validates whether that marker is
   still warranted.

2. **Capture a fresh backward-compile profile.** Run `testProfiledFineTuning()` (embed=64,
   depth=1), which writes an `OperationProfileNode` XML. Load it with `ar-profile-analyzer`
   (`load_profile`, `find_slowest`, `get_timing_breakdown`, `get_source`) and identify what
   now dominates backward-pass compilation. Confirm or refute that the Feb-2026 hot spots
   (`collectionProductComputation`, `collectionAddComputation`, nested-`reshape` overhead,
   the 72×-larger `projectDelta` intermediate) are still the top consumers, or whether the
   dominant cost has shifted to something else after the kernel-series work.

3. **Produce a current scaling curve and feasibility verdict.** Rebuild the
   embed-vs-backward-compile-time table with today's numbers alongside the Feb-2026 numbers.
   Determine whether the growth is still super-quadratic, and re-derive the production-scale
   (embed=1024, depth=16) projection from the *current* curve. State plainly whether the
   "infeasible" verdict still holds, has softened to "slow but feasible", or is now false.

4. **Rewrite `docs/plans/FINE_TUNE_FAIL.md` to reflect reality.** Replace the stale
   "BLOCKED / infeasible" framing and the Feb-2026 tables with the current measurement, the
   fresh profile analysis, and the revised verdict. Preserve the historical numbers clearly
   labelled as pre-optimization so the improvement is legible. If the old `IndexProjectionProducerComputation.delta()`
   scope-error blocker (`'_..._i' undeclared`) no longer reproduces, record that it is
   resolved; if it still reproduces, capture the exact current failure with
   `ar-profile-analyzer` / the generated source rather than the year-old description.

5. **Identify the single highest-value next lever, with evidence.** From the fresh profile,
   name the one optimization that would most reduce current backward-compile time (candidates
   already catalogued in `FINE_TUNE_FAIL.md` §"Potential Solutions" and
   `CONVOLUTION_COMPILE_TIME.md` §"What remains": reshape/​delegate-chain fusion, the 72×
   `projectDelta` intermediate, or a remaining enumeration hot spot). Cite the profile node
   and its share of total time.

6. **(Conditional) Land one small, self-contained optimization.** *Only if* step 5 surfaces
   a lever that is genuinely low-risk, self-contained, and does not overlap the in-flight
   core-autodiff work on the `feature/lora-gradients` family (sparse-Jacobian projection
   computations, `Sum` reordering budget, memoization gating — see
   `docs/plans/LORA_GRADIENTS_REDUCTION.md`), implement it and re-measure with the same
   harness to show the before/after. If no such clean lever exists, do **not** force one;
   documenting the evidenced next step is a complete and valuable outcome. A collision with
   the in-flight branch is an explicit non-goal.

Out of scope: rewriting the automatic-differentiation architecture; the sparse-Jacobian /
`Sum`-reordering work already in flight on `feature/lora-gradients`; the run-side (not
compile-side) cost of the 72× `projectDelta` intermediate; production-scale (embed=1024)
end-to-end training runs.

## Approach

1. **Establish the baseline first, change nothing.** The first commit-worthy artifact is
   pure measurement: current numbers vs. the documented Feb-2026 numbers. This follows the
   `CONVOLUTION_COMPILE_TIME.md` discipline — evidence before hypotheses, profile-based
   ratios (which are robust to a noisy shared host) rather than raw wall-clock comparisons
   across machines.

2. **Respect the test-execution limits.** Run one test at a time
   (`-Dtest=AggressiveFineTuningTest#testProfiledFineTuning`, then individual scaling
   configurations) via `mcp__ar-test-runner__start_test_run` with `test_classes` /
   `test_methods`, each with an explicit timeout ≤ 40 min. Never run the whole module or a
   CI shard. For the scaling sweep, prefer running the smaller configurations that fit inside
   a single test's timeout; if embed=128/256 do not complete in budget, record that as the
   current practical ceiling rather than forcing it.

3. **Use `ar-profile-analyzer` before any hand-instrumentation.** Per the project's
   kernel-behavior rule, the fresh profile is loaded and inspected with the analyzer's
   `get_source` / `get_timing_breakdown` / `find_slowest` before adding any `log()` probe.

4. **Keep the doc rewrite faithful to source.** Every claim in the revised
   `FINE_TUNE_FAIL.md` is tied to a measured number or a generated-source inspection, not to
   the prior document's assertions. Cite code by stable identifier (class/method), never by
   line number (per `docs/CLAUDE.md`).

5. **Store findings as memories immediately** (namespace `performance`, tagged with the
   branch) so the next planning cycle can act on the revised verdict without re-deriving it.

## Success Criteria

- Current backward-compile scaling numbers for at least embed ∈ {8, 16, 32, 64} on today's
  `master`, presented next to the Feb-2026 numbers.
- A fresh `finetune_profile_embed64` profile captured and analyzed, with the current
  top-3 backward-compile cost nodes and their time shares named from `ar-profile-analyzer`.
- `docs/plans/FINE_TUNE_FAIL.md` rewritten so its headline verdict matches the current
  measurement, with pre-optimization numbers retained and labelled.
- A named, evidenced next optimization lever, scoped so it does not collide with the
  in-flight `feature/lora-gradients` work.
- If a clean lever was implemented: a before/after measurement from the same harness showing
  the effect; the relevant targeted test(s) pass; and the build validator is clean
  (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`).
- A `performance`-namespace memory recording the revised feasibility verdict.

## Dependencies

- None blocking. The measurement harness (`AggressiveFineTuningTest`) already exists, and
  the kernel-series optimization it must be measured against is already on `master`.
- Coordinate-around (not depend-on): the in-flight `feature/lora-gradients` family. This
  task must not modify the sparse-Jacobian projection computations, the `Sum` reordering
  budget, or the memoization gating those branches own.

## Estimated Complexity

**Medium.** Steps 1–5 (measure, profile, re-baseline the doc, name the next lever) are a
focused, achievable session and are the core deliverable. Step 6 (landing one optimization)
is genuinely conditional and, if the surfaced lever is not small and self-contained, is
correctly deferred to a follow-up plan rather than forced into this one.

## How this fits the trajectory

The Manager Log's stated sequence after the documentation cycles was **performance
(compile-time and kernel reuse) → proof-of-value (a small self-hosted training run)**. The
compile-time optimization work has quietly delivered a large win; this task's job is to
*cash that win in* by re-establishing what is now possible, so the proof-of-value training
step is planned against reality instead of an eight-month-old pessimistic estimate. If the
verdict flips to "feasible", the very next planning cycle can scope the minimal end-to-end
training run — the first concrete step toward software that studies itself.
