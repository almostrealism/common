# Re-baseline LoRA fine-tuning backward-pass compile scaling

## Title

Re-measure and re-baseline transformer backward-pass compilation scaling after the
`ExplicitExpressionMatrix` expression-cache work and the kernel-series / block-evaluation
optimization, and revise the fine-tuning feasibility verdict accordingly.

## Category

Performance.

## Motivation

The platform's authoritative statement on training feasibility is stale, and it is a
pessimistic one.

`docs/plans/FINE_TUNE_FAIL.md` concludes, in bold, that **"production-scale LoRA
fine-tuning is currently infeasible with the existing compilation architecture."** Its
scaling table (embed 8 → 128) shows first-training-step latency — which at that scale was
almost entirely lazy backward-pass compilation — growing from ~38 s to ~31 min for a *single*
transformer block, and it extrapolates to "days, not minutes" for
the production configuration (embed=1024, depth=16). Every downstream planning decision
that touches training — and the entire proof-of-value / self-hosted-training trajectory in
the Manager Log — inherits that verdict.

That measurement is dated **February 2026**. Its own profile analysis attributes the
backward-compile cost to matrix-multiply derivatives (`collectionProductComputation`),
gradient accumulation (`collectionAddComputation`), and heavy nested `reshape` wrapping
around index-arithmetic expressions.

Since then, the compile-time front has moved decisively — and on **two distinct paths**.
It is important to keep these separate, and to be careful about how their February sizes are
compared, because it is easy to overstate how directly the headline convolution result
predicts the fine-tuning result.

A caveat on the February numbers first. `FINE_TUNE_FAIL.md` §"Detailed Profile Analysis
(February 2026)" reports `expressionCacheMatch` at **~1375.6 s** and `kernelSeries` at
**~150 s**, but both are `stageDetailTime` entries: the profile's scope listener was created
non-exclusive (`OperationProfileNode.getScopeListener(false)` records into
`getStageDetailTime()`, whose javadoc describes "non-exclusive accumulation of overlapping stage
timings"). That is why `expressionCacheMatch` alone is listed at "268 %" of the 512.8 s profiled
run. These figures are accumulated across nested and overlapping timings, so they cannot be read
as wall-clock shares, and they are not comparable to the node-level 28.5 % / 22.0 %
`collectionProductComputation` / `collectionAddComputation` shares cited above. What the February
data *does* support is weaker: `expressionCacheMatch` was by far the largest accumulated
stage-detail entry (and `FINE_TUNE_FAIL.md` itself calls it "the main performance bottleneck"),
and `kernelSeries` was a much smaller one. No exclusive measurement establishes how much of the
~500 s backward pass either one actually consumed.

- **Expression-cache matching — the largest accumulated stage-detail entry.** `FINE_TUNE_FAIL.md`'s
  September-2026 updates record that this cost was attacked directly, in two steps.
  `ExplicitExpressionMatrix.populate` was moved under `ExpressionCache.bypass(...)`, taking the
  isolated `ProductDeltaIsolationTest#testSingleAttentionBackward` from a mean of ~186 s to ~25 s
  on Metal; its entries were then made to substitute on demand, taking the same test from 26.0 s
  to 3.6 s on Metal (and 40.4 s → 17.8 s on the native backend). These are the changes most likely
  to move the fine-tuning numbers, and they are already on `master`. Note they were measured on an
  isolated attention backward, not on the full `DiffusionTransformer` backward pass this plan
  targets.
- **Kernel-series analysis — a smaller accumulated stage-detail entry.** Separately,
  `docs/plans/CONVOLUTION_COMPILE_TIME.md` records a resolution that landed on
  `feature/cl-profile-perf` (**2026-09-08**): the kernel-series detection pass was rebuilt around
  block evaluation with early abort, structural (symbolic) series derivation, and
  construction-time folds, taking `convDeltaSmall` **172.1 s → 1.15 s** and `upsample`
  **233 s → 4 s** (~150×). This targets the `Sum.simplify → getSeries → Expression.sequence` path —
  the ~150 s accumulated `kernelSeries` entry. It is real leverage, but the ~150× convolution figure
  should not be read as a ~150× prediction for the fine-tuning backward pass.

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

   The profiled run needs the same treatment, not just the sweep. `testProfiledFineTuning()` is
   itself an existing five-minute `@Test`, and the only historical profile of it covered
   **512.8 s** — well past that limit. Raising the timeout on the existing method is not an option:
   it would weaken an existing test, and `test-integrity-check`
   (`tools/ci/agent-protection/detect-test-hiding.sh`, Pattern 9 "Timeout value INCREASED by more
   than 2x") mechanically rejects a net timeout increase above 2× (5 min → anything over 10 min).
   Step 0 must therefore also add a **new, independently selectable profiled-measurement method**
   (embed=64, depth=1, same configuration as `testProfiledFineTuning()`) with its own explicit
   JUnit timeout sized to the expected run and **strictly shorter than the runner timeout, not
   merely equal to it**. The runner timeout is a total wall-clock budget that already includes the
   synchronous preflight/build step before the test JVM's own timer is armed
   (`_remaining_timeout_seconds` in `tools/mcp/test-runner/server.py` subtracts elapsed preflight
   from `timeout_minutes * 60`, so the JVM's remaining budget is *less* than the configured runner
   timeout). A JUnit timeout set equal to the 40-minute runner ceiling would therefore be killed by
   the runner before JUnit could abort the method and run the `finally` block that saves the
   profile (see the save-on-failure defect below). Size the JUnit timeout to the expected run plus
   a margin, and keep it below the runner timeout with explicit headroom reserved for preflight and
   for the profile-persistence `finally` block — e.g. a JUnit timeout of ~30 min under a 40-min
   runner budget, not 40 under 40. A brand-new method with a larger timeout has no removed
   counterpart, so it does not trip Pattern 9; the existing method is left exactly as it is.

   **This new method must wrap only the first backward step in its own `OperationProfile`** so
   the emitted XML is backward-scoped. This is not a convenience: `ar-profile-analyzer` cannot
   restrict a ranking to a subtree. `find_slowest_by_category` (backed by `ProfileAnalyzerCLI.printSlowest`)
   always collects every node in the profile (`collectNodes(root, …)`) and takes no node key, and
   `list_children` (`ProfileAnalyzerCLI.printChildren`) returns only the 20 children with the greatest
   *total* duration, so a compile-heavy but run-light descendant of the backward subtree can be
   dropped from its output and it cannot feed a category ranking anyway. The only reliable way to
   get a backward-specific compile ranking out of the analyzer is to hand it a profile that already
   contains only the backward pass. The existing `runProfiledFineTuning` wraps model creation,
   forward execution, and three epochs in one profile; the new method must instead build the model
   and warm the forward pass outside the profiled region and profile just the first
   `backward.run()` (the lazy-compile step), writing that scoped profile to the module `results/`
   directory. If isolating the backward step cleanly is not feasible in the harness, that is itself
   a step-0 finding to record — the profile-scope success criterion below then falls back to a
   whole-run profile with the limitation stated, not to an unsupported subtree ranking.

   **Every new measurement method must be explicitly excluded from the CI pipeline** with
   `@TestProperties(excludeProfiles = TestUtils.PIPELINE)`, while staying individually selectable
   for the measurement runs. CI's test jobs run with `-DAR_TEST_PROFILE=pipeline`, and under that
   profile neither `@TestDepth` nor `longRunning` keeps a test out: `TestUtils.getTestDepth()`
   returns `Integer.MAX_VALUE` and `TestUtils.getSkipLongTests()` returns `false` for the pipeline
   profile (the `TestProperties.excludeProfiles` javadoc says exactly this). The existing methods
   are kept out of CI today only by `@TestProperties(knownIssue = true)` —
   `TestUtils.getSkipKnownIssues()` returns `true` under the pipeline profile. That marker is not a
   safe exclusion for the new methods: step 1 re-validates whether it is still warranted, and if a
   method is declared (or later corrected) without it, each run of up to 40 minutes would be
   discovered by routine CI and add hours to the suite. The profile exclusion is independent of
   the known-issue status, so it stays correct whichever way step 1 comes out.

   Running these methods outside CI also has a precondition worth knowing before the first
   attempt: `getSkipKnownIssues()` also returns `true` whenever `getSkipLongTests()` does, which
   is the default (`AR_LONG_TESTS` unset and `AR_TEST_DEPTH` at its default of 9). A method
   marked `knownIssue = true` is therefore silently skipped — reported as an assumption failure,
   not a pass — unless the run sets `AR_LONG_TESTS=true` (or a test depth above 10). Check that
   each measurement actually executed rather than reading a skipped run as a fast one.

   The same harness change must also fix three measurement defects in the existing code, or the
   numbers it produces will not mean what the rest of this plan needs them to mean:

   - **`bwdMs` is not backward-compile time.** `measureCompilation()` reports as `Bwd(ms)` the
     wall-clock of the *entire* first `optimizer.optimize(1)` call. The backward pass is compiled
     lazily inside the first `backward.run()` (`FINE_TUNE_FAIL.md` §"Critical Finding: Lazy
     Compilation During Backward Execution"), so that interval contains the lazy compile **plus**
     forward execution, loss evaluation, backward execution, the parameter update, and any other
     work `ModelOptimizer` does in its first step. The second `optimize(1)` (`Train1(ms)`)
     measures the same work warm. When compile took tens of minutes, the runtime share was noise
     (the Feb-2026 warm step was 0.2–3 s); if compile has fallen to seconds, it no longer is, and
     treating `bwdMs` as compile time would distort the scaling curve. The harness must therefore
     report **cold first-step latency and warm second-step latency as separate columns**, and the
     plan's compile figure is the *derived* estimate `cold − warm`, labelled as such. Attribution
     of that compile cost (what it is spent on) comes from the profile in step 2, which separates
     compile, run, and `stageDetailTime` (`expressionCacheMatch`, `kernelSeries`) entries — not
     from wall-clock subtraction.
   - **The profile path is hard-coded to a container layout.** `testProfiledFineTuning()` creates
     and writes `/workspace/project/common/utils/results/finetune_profile_embed64.xml`. That
     absolute path exists only in the agent container; on a host without it (e.g. the macOS
     workers, where `/workspace` is not writable) `Files.createDirectories` fails before any
     measurement is taken. The profile should be written under the module's own `results/`
     directory, which is where `ar-profile-analyzer` expects `<module>/results/*.xml`.
   - **The profile is lost if the run fails.** `testProfiledFineTuning()` calls `profile.save(...)`
     only after `profile(profile, ...)` returns. The known `IndexProjectionProducerComputation.delta()`
     scope error (see Open questions) propagates out of `runProfiledFineTuning` as an exception, so
     no XML is written, including the compile timings recorded before the failure. The new
     profiled method should save the profile whether the run succeeds or fails (for example, in a
     `finally` block) and should record which of the two happened. A partial profile of a failed
     run is still evidence of where compile time went up to the failure.

1. **Reproduce the scaling measurement on current `master`.** Using the harness prepared in
   step 0, run the small-to-mid configurations (embed 8 → 64, at minimum) individually and
   capture the current numbers alongside the documented Feb-2026 numbers. **Record two SHAs for
   every measurement: the base `master`/compiler commit the branch was built on, and the exact
   commit of the tested worktree that actually produced the numbers**, together with the host and
   backend (see Open questions). The base `master` SHA alone does not identify the code that ran:
   step 0 first changes `AggressiveFineTuningTest`, so every measurement executes a branch worktree
   whose tree differs from that `master` commit, and the numbers belong to the tested commit, not
   to `master`. "Current `master`" is not a reproducible baseline — it moves, and if the
   conditional step-6 optimization later lands in this same workstream the before/after numbers
   would otherwise no longer be tied to the compiler source that produced them. Every reported
   figure and every before/after comparison names both SHAs it was measured at. The like-for-like
   comparison with Feb-2026 is **cold first-step latency** (that is what the old `Backward (ms)`
   column measured); the derived compile estimate (`cold − warm`) is reported next to it, never in
   place of it. **Repeat each configuration and report a median plus spread, not a single
   observation.** When compile took tens of minutes, one sample was enough because host noise was
   negligible against the signal; if compile has fallen to seconds, `cold − warm` becomes the
   difference of two small, noisy wall-clock samples, and host load, JVM warm-up, and GC can shift
   it enough to change the fitted scaling exponent or a step-6 before/after conclusion. Take
   several **independent cold starts** per configuration — each in a fresh process (or at least a
   freshly built model) so lazy backward compilation is not already cached — record the warm step
   from each, and report the median and the spread (min–max or inter-quartile range) for both cold
   and derived-compile figures. Any step-6 before/after uses the same repetition protocol on both
   sides so the comparison is between distributions, not between two single runs. Note that `FINE_TUNE_FAIL.md` holds *two* Feb-2026 scaling tables that disagree for
   the same configurations (§"Current Scaling Data": embed=8 51,988 ms, embed=16 cancelled after
   >300,000 ms; §"Scaling Test Results": embed=8 37,957 ms, embed=16 472,645 ms). Compare against
   both, state which one each ratio uses, and do not quote a single "Feb-2026 number" as if it were
   settled. Several of those historical figures (up to ~44 min) are also far longer than the
   five-minute JUnit timeout the method carries today, so they were taken under a different harness
   configuration than the one on `master`. Note the harness is
   marked `@TestProperties(knownIssue = true)`; the measurement re-validates whether that marker
   is still warranted. **Record the confounds in the existing sweep** (see step 3): `ioChannels`,
   `numHeads`, and `globalCondDim` all change together with `embedDim`, and every point is
   `depth=1`, so these configurations measure a combined "model size" axis, not embed alone.

2. **Capture a fresh backward-compile profile, and treat the two timing kinds separately.** Run the
   new profiled-measurement method from step 0 (the `testProfiledFineTuning()` configuration:
   embed=64, depth=1), which writes an `OperationProfileNode` XML. Load it with `ar-profile-analyzer`.
   Two distinct costs must be ranked, and the analyzer surfaces them through two different tools that
   are **not interchangeable**:

   - **Backend compile timings (per-operation metric entries).** `find_slowest_by_category` with
     `category="compile"` ranks nodes by the sum of their metric entries whose key ends in
     `" compile"` (`ProfileAnalyzerCLI.getCategoryDuration` reads `OperationProfileNode.getMetricEntries()`).
     These are the backend compiler's per-kernel timings — the cost of turning an already-built
     expression graph into a native kernel. Prefer this over plain `find_slowest`, which ranks total
     (compile + run) duration and, once compile has fallen, can let a run-heavy node displace a
     compile hot spot.
   - **Stage-detail timings (`expressionCacheMatch`, `kernelSeries`).** These are *not* metric
     entries and carry no `" compile"` suffix, so `find_slowest_by_category(category="compile")` does
     **not** rank them. They live in `getStageDetailTime()`, recorded per node, and the analyzer
     offers no command that ranks or aggregates them across the profile: `get_timing_breakdown` is a
     **point lookup by node key** (`ProfileAnalyzerCLI.printBreakdown` resolves a single node via
     `findByKey` and prints only that node's `stage_details`), and the node-discovery commands that
     could feed it a key — `search` (`searchOperations`) and `list_children` (`printChildren`) — each
     return only the 20 nodes with the greatest *total* duration. A node carrying a large accumulated
     stage-detail cost but a modest total duration can therefore be missed entirely. Read the
     stage-detail totals from `get_timing_breakdown` on the named backward-phase operation nodes
     whose keys are already known — the keyed top-level operations inside the backward-only
     profile, **not** its root node. The root created for the profile (as the existing method does,
     `new OperationProfileNode("finetune_embed64")`) carries a **null key** — the
     `OperationProfileNode(String name)` constructor sets the key to `null` — and
     `ProfileAnalyzerCLI.printBreakdown` resolves its argument through `findByKey`
     (`key.equals(node.getKey())`), which a null key can never match, so `get_timing_breakdown`
     on the root returns "Node not found". Point the lookup at the keyed operation nodes instead
     (a keyed backward operation can be discovered with `search`/`list_children`), and treat this
     as accumulated evidence, not a ranking. Because these entries are non-exclusive
     (see Motivation) they are reported as accumulated seconds only, never as a percentage of the run
     or ranked against node-level compile shares. This is the JVM-side expression-construction /
     cache-matching cost that the February profile identified as the *largest* accumulated entry
     (`expressionCacheMatch` ~1375.6 s) — precisely the cost the `ExplicitExpressionMatrix` work
     targeted.

   Consequently, the compile-category top list alone cannot answer "what now dominates backward-pass
   compilation": in February the dominant cost was a stage-detail entry that this ranking excludes.
   Produce **two** pieces of evidence — a backend-compile top list from
   `find_slowest_by_category(category="compile")`, and the accumulated stage-detail seconds
   (`expressionCacheMatch`, `kernelSeries`) read from `get_timing_breakdown` on the known
   backward-phase nodes — and read the verdict from the two together. If a stage-detail hot spot is
   suspected on a node that total-duration-based discovery does not surface, record that the analyzer
   cannot rank stage-detail entries and that locating it would require extending the analyzer (or
   exhaustively walking the profile's nodes); do not report a stage-detail *ranking* the tooling
   cannot produce.

   **Scope the analysis to the backward phase.** The *existing* `runProfiledFineTuning` wraps model
   creation, forward execution, and three training epochs (`optimizer.optimize(3)`), so an all-node
   ranking over that whole profile is not backward-specific. The analyzer cannot fix this after the
   fact — `find_slowest_by_category` always ranks over the entire profile and takes no subtree key,
   and `list_children` only returns the 20 highest *total*-duration children, so it neither scopes a
   category ranking nor is guaranteed to surface a compile-heavy/run-light backward node. Backward
   scoping must therefore come from the profile itself: rank over the backward-only profile that
   step 0's new method emits (a profile wrapping just the first `backward.run()`), so a plain
   whole-profile `find_slowest_by_category(category="compile")` is already backward-specific. Only if
   step 0 could not isolate the backward step is a whole-run profile used, and then the ranking is
   reported with the explicit caveat that it includes model-creation and forward/epoch nodes.
   Then drill into the top nodes with
   `get_timing_breakdown` and `get_source`, and confirm or refute that the Feb-2026 hot spots
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

   **A feasibility label requires a threshold fixed before measurement.** The words "feasible" and
   "infeasible" are only assigned against an acceptance criterion the approver sets before step 1
   runs: a named target configuration (for example the proof-of-value configuration, or
   embed=1024/depth=16) and a maximum acceptable cold first-step latency or derived compile budget
   for it. The verdict is then a comparison of the measured (or, with the controlled series,
   extrapolated) figure against that number, with the extrapolation labelled as such. If no
   threshold has been fixed when the measurements are taken, the deliverable is limited to the
   measured timings and fitted growth rates, and no feasibility label is assigned — the stale
   "infeasible" verdict is then withdrawn as unsupported rather than replaced by another
   judgement call. See Open questions.

4. **Rewrite `docs/plans/FINE_TUNE_FAIL.md` to reflect reality.** Replace the stale
   "BLOCKED / infeasible" framing and the Feb-2026 tables with the current measurement, the
   fresh profile analysis, and — *if the approver fixed a threshold before measurement (step 3)* —
   the revised feasibility verdict. If no threshold was fixed, the rewrite instead carries the
   measured timings and fitted growth rates and withdraws the stale "infeasible" verdict as
   unsupported, assigning no new feasibility label in its place (matching step 3 and the success
   criteria). Preserve the historical numbers clearly
   labelled as pre-optimization so the improvement is legible. If the old `IndexProjectionProducerComputation.delta()`
   scope-error blocker (`'_..._i' undeclared`) no longer reproduces, record that it is
   resolved; if it still reproduces, capture the exact current failure with
   `ar-profile-analyzer` / the generated source rather than the year-old description.

5. **Identify the single highest-value next lever, with evidence.** From the fresh profile,
   name the one optimization that would most reduce current backward-compile time (candidates
   already catalogued in `FINE_TUNE_FAIL.md` §"Potential Solutions" and
   `CONVOLUTION_COMPILE_TIME.md` §"What remains": reshape/delegate-chain fusion, the 72×
   `projectDelta` intermediate, or a remaining enumeration hot spot). Cite the evidence in the
   unit its timing kind supports. For a profile node, give its compile time and its share of
   total compile time — computed against the **whole-profile** compile-category denominator (the
   summed compile duration of *every* compile-bearing node, obtained by calling
   `find_slowest_by_category(category="compile")` with a `limit` large enough to return all nodes
   with a non-zero compile duration and summing their `duration` fields), **not** the summed
   durations of only the ranked top few (which would normalize the reported nodes to 100 % and
   overstate each share), and **not** the analyzer's own `percentage` field, which
   `ProfileAnalyzerCLI.printSlowest` derives against total node duration (compile + run, the
   summed `getNodeDuration` over all nodes) and so understates the compile share. For a
   `stageDetailTime` entry (for example an `expressionCacheMatch` or
   `kernelSeries` hot spot), give its accumulated seconds only. That figure is non-exclusive
   (see Motivation), so no valid percentage of the run exists for it. If the profile could not be
   captured (see Open questions), say so. Name the lever as a hypothesis backed by wall-clock data,
   not as a profile-evidenced one.

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
   runner cannot lengthen that — so the new per-configuration and profiled methods added in
   step 0 each need a JUnit timeout sized to their own expected run and **strictly shorter than the
   runner timeout** (the runner budget includes preflight/build time, so a JUnit timeout equal to
   the 40-minute runner ceiling would be killed before the method can unwind and save its profile;
   reserve explicit margin), while the timeouts on the existing methods stay unchanged. Run the new profiled
   method and each scaling configuration as its own selectable method; prefer the smaller configurations
   first, and if embed=128/256 do not complete in budget, record that as the current practical
   ceiling rather than forcing it.

3. **Use `ar-profile-analyzer` before any hand-instrumentation.** Per the project's
   kernel-behavior rule, the fresh profile is loaded and inspected with the analyzer's
   `find_slowest_by_category` (`category="compile"`) / `get_timing_breakdown` / `get_source`
   before adding any `log()` probe.

4. **Keep the doc rewrite faithful to source.** Every claim in the revised
   `FINE_TUNE_FAIL.md` is tied to a measured number or a generated-source inspection, not to
   the prior document's assertions. Cite code by stable identifier (class/method), never by
   line number (per `docs/CLAUDE.md`).

5. **Store findings as memories immediately** (namespace `performance`, tagged with the
   branch) so the next planning cycle can act on the revised verdict without re-deriving it.

## Success Criteria

- The additive harness change from Scope step 0 landed (per-configuration and profiled
  methods, each individually selectable and bounded) with the existing tests, including their
  timeouts, unchanged; it reports cold first-step and
  warm second-step latency separately, the profiled method emits a backward-scoped profile
  (wrapping just the first `backward.run()`, or the whole run with the limitation recorded if that
  could not be isolated), and the profiled run writes its XML under the module's
  `results/` directory rather than a hard-coded container path.
- Every reported "backward compile" figure is either a profile-derived compile/stage-detail time
  or the explicitly labelled `cold − warm` estimate, never raw first-step latency.
- Current backward-compile numbers for at least embed ∈ {8, 16, 32, 64} on today's `master`,
  presented next to the Feb-2026 numbers, each annotated with both the base `master` commit SHA
  and the exact tested-worktree commit SHA (the branch commit carrying the step-0 harness change,
  which is what actually produced the numbers), plus the host and backend they were measured at
  (per Scope step 1). For any embed-scaling or depth claim, the numbers
  come from a *controlled* series (embed varied with other dimensions fixed; ≥2 depth points at
  fixed embed); where an axis could not be measured in budget, the conclusion is explicitly
  limited to the measured configurations and the gap is recorded as an open question.
- A fresh `finetune_profile_embed64` profile captured and analyzed **within the backward-pass
  scope** — a profile emitted around the first `backward.run()` alone (per Scope step 0), since
  the analyzer cannot scope a ranking to a subtree after the fact — not a whole-run ranking over
  model creation, forward, and the three profiled epochs,
  with the two timing kinds reported separately. First, the current top-3 backend-compile cost nodes from
  `ar-profile-analyzer` `find_slowest_by_category` (`category="compile"`), each carrying its compile
  time and its share of total compile time, where that share is computed against the whole-profile
  compile-category denominator (the summed compile duration of every compile-bearing node in the
  profile, obtained by requesting a `limit` large enough to return them all — not the summed compile
  durations of only the ranked top few, which would normalize them to 100 % and overstate each
  share) rather than the analyzer's
  reported `percentage`, which is a share of total node duration (compile + run). Second, the accumulated stage-detail seconds
  (`expressionCacheMatch`, `kernelSeries`) read from `get_timing_breakdown` on the keyed
  backward-phase operation node(s) — not the profile's null-keyed root, which `findByKey` cannot
  resolve — a point lookup, not a ranking, since no analyzer command aggregates or
  ranks stage-detail entries across the profile — each quoted as accumulated
  seconds only, never as a share, and never ranked against the node-level compile shares, since
  `find_slowest_by_category(category="compile")` does not include them.
  *Conditional on the profile being capturable:* if the profiled run still fails with the
  `delta()` scope error and that fix is ruled out of scope (see Open questions), this criterion is
  instead met by one of two things. The first is an analysis of the partial profile saved on failure,
  labelled as covering only the work before the failure. The second, if even that profile is
  empty, is a recorded statement that no profile could be captured, with the exact current error.
- `docs/plans/FINE_TUNE_FAIL.md` rewritten so its headline verdict matches the current
  measurement, with pre-optimization numbers retained and labelled. The headline states the
  acceptance threshold it was judged against (target configuration and budget, fixed before
  measurement per Scope step 3); if none was fixed, the headline reports the measured timings and
  growth rates without a feasibility label.
- Every new measurement method from Scope step 0 carries
  `@TestProperties(excludeProfiles = TestUtils.PIPELINE)`, so it does not run in CI's pipeline
  profile regardless of its `knownIssue` status.
- A named next optimization lever, scoped so it does not collide with the in-flight
  `feature/lora-gradients` work. The lever is profile-evidenced when a complete profile exists.
  Otherwise it is explicitly labelled as a hypothesis from wall-clock and partial-profile data.
- If a clean lever was implemented: a before/after measurement from the same harness showing
  the effect, taken with the same repetition protocol on both sides (median plus spread over
  several independent cold starts, per Scope step 1) so the change is distinguishable from
  measurement noise; the relevant targeted test(s) pass; and the build validator is clean
  (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`).
- A `performance`-namespace memory recording the revised feasibility verdict.

## Dependencies

- None blocking, but one internal prerequisite: the measurement harness
  (`AggressiveFineTuningTest`) exists yet is not individually runnable within a JUnit timeout
  (Scope step 0 must land first). Both optimizations it must be measured against — the
  `ExplicitExpressionMatrix` cache-bypass / on-demand-entry work that targeted the
  `expressionCacheMatch` stage-detail entry, and the kernel-series rebuild that targeted the
  `kernelSeries` entry — are already on `master`.
- Coordinate-around (not depend-on): the in-flight `feature/lora-gradients` family. This
  task must not modify the sparse-Jacobian projection computations, the `Sum` reordering
  budget, or the memoization gating those branches own.

## Open questions

Recorded for whoever approves this plan; none of them is resolved by this document.

- **Can the profile be captured at all?** `FINE_TUNE_FAIL.md` opens with "BLOCKED":
  `testProfiledFineTuning` failed with the `IndexProjectionProducerComputation.delta()` scope error
  (`'_..._i' undeclared`). Scope step 4 treats that as something to re-check, but steps 2 and 5
  depend on the profile existing. If the error still reproduces, the profile-driven attribution
  (step 2), the "top-3 cost nodes" success criterion, and the next-lever choice (step 5) all fall
  back to wall-clock data and whatever partial profile the step 0 save-on-failure change preserves.
  The plan's core deliverable then shrinks to the scaling table. The success criteria already give
  that fallback, so the plan can be carried out either way. The approver should still decide up
  front whether fixing that error is in scope here or is a separate plan. Fixing it means changing
  autodiff code near the `feature/lora-gradients` work that this plan otherwise avoids.
  `FINE_TUNE_FAIL.md` records the error only against `testProfiledFineTuning`. It does not say
  whether `testCompilationScaling()` hits it too, so the step 0 per-configuration methods may be
  affected as well.
- **How much of the delta is the harness, not the platform?** Step 0 changes how the numbers
  are taken (separate cold/warm columns, per-configuration methods, different timeouts). Since the
  Feb-2026 figures came from a harness configuration that no longer matches `master` (they exceed
  the current five-minute JUnit timeout), a large improvement could partly reflect
  measurement differences. Cold first-step latency is the closest like-for-like metric; any
  headline ratio should say which metric it compares.
- **Machine, backend, and commit.** The Feb-2026 numbers do not record the host or the
  `AR_HARDWARE_DRIVER` backend. Current runs should record both **and two SHAs — the base `master`
  commit and the exact tested-worktree commit that produced the numbers** (per Scope step 1). The
  tested commit is the one that matters: step 0 changes the harness, so the measured tree is never
  plain `master`; recording only the `master` SHA would not identify the code that ran. "Current
  `master`" also moves, and any before/after from a step-6 optimization must be tied to the exact
  compiler source that produced each number.
  Cross-machine ratios should
  be treated as indicative only (the `CONVOLUTION_COMPILE_TIME.md` discipline of profile-based
  ratios applies).
- **Does "feasible" have a threshold?** The plan asks whether the verdict is "slow but feasible"
  or "false" without saying what compile budget counts as feasible for the proof-of-value run.
  Without a number (for example, first-step latency for the proof-of-value configuration under
  some fixed ceiling), the revised verdict risks being as subjective as the one it replaces.
  Scope step 3 now makes this a precondition: the approver fixes the target configuration and
  budget before measurement, or the deliverable carries timings and growth rates only, with no
  feasibility label. The plan deliberately does not propose the number itself; the approver
  should supply it (and name the proof-of-value configuration it applies to) when approving.

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
