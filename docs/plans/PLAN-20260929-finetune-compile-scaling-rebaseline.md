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

Since then, the compile-time front has moved decisively — and on **two distinct paths**,
one of which is the very cost that `FINE_TUNE_FAIL.md` names as dominant. It is important
to keep these separate, because it is easy to overstate how directly the headline
convolution result predicts the fine-tuning result:

- **The dominant fine-tuning cost — expression-cache matching.** `FINE_TUNE_FAIL.md`'s own
  stage-detail breakdown attributes the bulk of the embed=64 backward-compile time to
  `expressionCacheMatch` (**~1375.6 s**, the single largest entry), with `kernelSeries` a
  *secondary* consumer (**~150 s**). Its September-2026 updates then record that this
  dominant cost was attacked directly: `ExplicitExpressionMatrix.populate` was moved under
  `ExpressionCache.bypass(...)` ("Stop explicit expression matrices from flooding the compile
  cache") and its entries are now substituted on demand ("Evaluate explicit expression matrix
  entries on demand"), taking the isolated `testSingleAttentionBackward` from ~186 s to ~3.6 s
  on Metal. Those changes are the ones most likely to move the fine-tuning numbers, and they
  are already on `master`.
- **The secondary cost — kernel-series analysis.** Separately, `docs/plans/CONVOLUTION_COMPILE_TIME.md`
  records a resolution that landed on `feature/cl-profile-perf` (**2026-09-08**): the
  kernel-series detection pass was rebuilt around block evaluation with early abort, structural
  (symbolic) series derivation, and construction-time folds, taking `convDeltaSmall`
  **172.1 s → 1.15 s** and `upsample` **233 s → 4 s** (~150×). This targets the `Sum.simplify →
  getSeries → Expression.sequence` path — the ~150 s *secondary* fine-tuning consumer, not the
  ~1375 s dominant one. It is real leverage, but the ~150× convolution figure should not be
  read as a ~150× prediction for the fine-tuning backward pass.

The combined effect of both fronts on the transformer backward pass is unmeasured, which is
exactly what this task exists to establish.

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

0. **Prerequisite — make the scaling harness individually runnable within a bounded timeout.**
   The existing `AggressiveFineTuningTest.testCompilationScaling()` (in `studio/compose`,
   `org.almostrealism.studio.ml.test`) is a *single* `@Test` method that loops over all six
   configurations `{embed 8/16/32/64/128/256, depth=1}` internally, and it — like
   `testProfiledFineTuning()` — carries a hard `@Test(timeout = 5 * 60000)` five-minute JUnit
   timeout. That is a real execution blocker for this plan: the test runner can only select a
   whole `@Test` method, not one configuration inside the loop, and a runner-level timeout
   cannot extend a JUnit `@Test(timeout=…)` (JUnit aborts the method at five minutes
   regardless). With the Feb-2026 numbers, even a single mid-size configuration exceeds five
   minutes, so the sweep cannot complete as one test. The first implementation step is therefore
   a small, additive harness change that exposes **bounded, individually selectable** runs — for
   example a per-configuration `@Test` method (or a single-config method driven by a system
   property) each with an explicit timeout sized to the expected backward-compile time — **without
   weakening the existing tests** (do not lower an assertion, shrink a dimension, or raise a
   `@TestDepth` past what CI runs). This is a source change (`studio/compose` test sources), so it
   is *not* part of this documentation review; it is the first coding task for whoever implements
   the plan, and every measurement step below depends on it.

1. **Reproduce the scaling measurement on current `master`.** Using the harness prepared in
   step 0, run the small-to-mid configurations (embed 8 → 64, at minimum) individually and
   capture the current numbers alongside the documented Feb-2026 numbers. Note the harness is
   marked `@TestProperties(knownIssue = true)`; the measurement re-validates whether that marker
   is still warranted. **Record the confounds in the existing sweep** (see step 3): `ioChannels`,
   `numHeads`, and `globalCondDim` all change together with `embedDim`, and every point is
   `depth=1`, so these configurations measure a combined "model size" axis, not embed alone.

2. **Capture a fresh backward-compile profile.** Run `testProfiledFineTuning()` (embed=64,
   depth=1), which writes an `OperationProfileNode` XML. Load it with `ar-profile-analyzer`
   (`load_profile`, `find_slowest`, `get_timing_breakdown`, `get_source`) and identify what
   now dominates backward-pass compilation. Confirm or refute that the Feb-2026 hot spots
   (`collectionProductComputation`, `collectionAddComputation`, nested-`reshape` overhead,
   the 72×-larger `projectDelta` intermediate) are still the top consumers, or whether the
   dominant cost has shifted to something else after the kernel-series work.

3. **Produce a current scaling curve and feasibility verdict — within the limits of what is
   measured.** Rebuild the table with today's numbers alongside the Feb-2026 numbers. The
   existing six-point sweep cannot, on its own, support a categorical embed=1024/depth=16
   verdict: it varies `ioChannels`, `numHeads`, and `globalCondDim` in lockstep with `embedDim`
   and holds `depth=1` throughout, so it isolates neither embed scaling nor depth scaling. To
   claim anything about the production configuration, add controlled measurements: an embed-only
   series (all other dimensions fixed while `embedDim` varies) to isolate the embed exponent, and
   at least two `depth` points at a fixed small embed to estimate the per-block multiplier. If a
   given axis cannot be measured inside the timeout budget, **limit the stated conclusion to the
   configurations actually measured** and record the missing axis as an open question rather than
   extrapolating past the data. Only with the controlled series in hand should the plan state
   whether the growth is still super-quadratic and whether the "infeasible" verdict still holds,
   has softened to "slow but feasible", or is now false.

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
   `CONVOLUTION_COMPILE_TIME.md` §"What remains": reshape/delegate-chain fusion, the 72×
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

1. **Establish the baseline first, change no production code.** Apart from the additive test-harness
   change in Scope step 0 (which only makes the existing measurements runnable, and touches no
   production source), the first commit-worthy artifact is pure measurement: current numbers vs.
   the documented Feb-2026 numbers. This follows the `CONVOLUTION_COMPILE_TIME.md` discipline —
   evidence before hypotheses, profile-based ratios (which are robust to a noisy shared host)
   rather than raw wall-clock comparisons across machines.

2. **Respect the test-execution limits.** Run one test at a time via
   `mcp__ar-test-runner__start_test_run` with `test_classes` / `test_methods`, each with an
   explicit runner timeout ≤ 40 min. Never run the whole module or a CI shard. Note the two
   independent limits this must satisfy: the runner-level timeout is a ceiling the harness
   enforces, but each `@Test` also carries its own JUnit `@Test(timeout = 5 * 60000)`, and the
   runner cannot lengthen that — so once step 0 has split the sweep into per-configuration
   methods, each such method needs a JUnit timeout sized to its own expected backward-compile
   time (never lowered below, or removed from, an existing method). Run `testProfiledFineTuning`
   and each scaling configuration as its own selectable method; prefer the smaller configurations
   first, and if embed=128/256 do not complete in budget, record that as the current practical
   ceiling rather than forcing it.

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

- The additive harness change from Scope step 0 landed (per-configuration, individually
  selectable, bounded runs) with the existing tests unweakened.
- Current backward-compile numbers for at least embed ∈ {8, 16, 32, 64} on today's `master`,
  presented next to the Feb-2026 numbers. For any embed-scaling or depth claim, the numbers
  come from a *controlled* series (embed varied with other dimensions fixed; ≥2 depth points at
  fixed embed); where an axis could not be measured in budget, the conclusion is explicitly
  limited to the measured configurations and the gap is recorded as an open question.
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

- None blocking, but one internal prerequisite: the measurement harness
  (`AggressiveFineTuningTest`) exists yet is not individually runnable within a JUnit timeout
  (Scope step 0 must land first). Both optimizations it must be measured against — the
  `ExplicitExpressionMatrix` cache-bypass / on-demand-entry work that targeted the dominant
  `expressionCacheMatch` cost, and the kernel-series rebuild that targeted the secondary cost —
  are already on `master`.
- Coordinate-around (not depend-on): the in-flight `feature/lora-gradients` family. This
  task must not modify the sparse-Jacobian projection computations, the `Sum` reordering
  budget, or the memoization gating those branches own.

## Estimated Complexity

**Medium.** Step 0 (the additive harness change) plus steps 1–5 (measure, profile, re-baseline
the doc, name the next lever) are a focused, achievable session and are the core deliverable.
Step 6 (landing one optimization)
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
