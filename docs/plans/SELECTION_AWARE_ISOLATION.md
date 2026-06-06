# Selection-aware isolation at `Process.optimize` time

## Status

Investigation complete; implementation not yet started. Work proceeds on the
dedicated branch `feature/selection-aware-isolation` (off `master`), independent
of `feature/lora-gradients` — the capability is a general platform improvement.

Supersedes the deleted `SUBSET_DELTA_SPARSITY.md`, whose central premise (master
returns an identity index-projection from `PackedCollectionSubset.delta`, lora
returns a `SubsetProjectionComputation`) is now obsolete: the
`SubsetProjectionComputation` delta path and its `getStatementCount ->
getMemLength` compile fix have since merged to master, so both branches share the
same delta representation. The only remaining `feature/lora-gradients` difference
in this area is `Sum.enableGenerateReordering = true` plus the
`withinReorderingBudget` cost gate.

**The decision gate is resolved.** CI on `feature/lora-gradients` confirmed
outcome 1 below: `convDelta*` times out while the rotary subset/concat gradients
need the Sum reordering. So the normative stance holds and we pursue P1.

**Two empirical findings now anchor the design** (see "Empirical findings" below):
the collapse is blocked at the `IsolatedProcess` boundary, and the isolation that
matters is decided by the optimization *strategy*, not by per-process
`isIsolationTarget`. These sharpen P1 and demote P2.

The correctness harness exists: `SelectionContractionCollapseTests` (7 oracle +
differential tests over subset / weighted-subset / concat / multiply-sum delta),
green on the master baseline (run `528e7971`).

> A note for whoever repoints it: `SubsetDeltaCollapseTests.java:29` (already on
> master) has a Javadoc prose reference to `docs/plans/SUBSET_DELTA_SPARSITY.md`.
> That reference now dangles; it should be updated to point here.

## The normative stance this document assumes

A *masked sum* — a reduction `sum_k Mask(guard(k), value(k))` — has a "shape"
along two axes: how many terms it carries (width) and how deep each term's
expression is (depth). Two optimizations pull in opposite directions on this
shape:

- **`generateReordering`** (in `KernelTraversalProvider`, reached from
  `Sum.simplify` when every child `isSingleIndexMasked`) collapses the masked sum
  into a precomputed lookup. Its compile-time cost scales as
  `count x expression_size x body_depth`, uncached. It is a *win* for
  narrow/shallow masked sums (rotary-style terminal gradients: the emitted body
  shrinks dramatically, e.g. ~16675 -> ~21 chars) and a *loss* for wide/deep ones
  (convolution-style `multiply().sum()` gradients: the per-index simplification
  pass explodes).

The position taken here — explicitly a *stance*, not a proven law — is that
**narrow/shallow is the normative case**. Most masked sums that arise in practice
are small selections, and the precompute-a-table optimization is the right
default for them. A wide/deep masked sum is the exceptional case, and the
exceptional case is the one that *deserves a dedicated tool* rather than the one
that should hold the default hostage. Concretely: leave the reordering
optimization on (`enableGenerateReordering = true`), and make the wide/deep
contraction (convDelta) efficient through a separate mechanism rather than by
disabling the optimization globally.

This stance is what makes "Option A" (keep the Sum feature on; make convDelta
efficient) the preferred direction, pending CI confirmation that convDelta is in
fact still the failing side under the current budget-gated code.

## Why this is really an isolation problem, not a Sum problem

The earlier investigation peeled the convDelta failure through four subsystems
and landed on the one that actually blocks the collapse: **isolation**.

The contraction that convDelta produces is, in essence,
`sum_j (j == source(i) ? g[j] : 0)`, which equals `g[source(i)]` — a gather. The
machinery to collapse it to a gather *already exists*:
`AggregatedProducerComputation`, when `replaceLoop` is set, calls
`uniqueNonZeroOffset(...)` during `prepareScope`; a non-null offset makes
`getScope` emit a single direct read instead of the `count`-term loop.

That collapse does not fire for convDelta because, by the time `prepareScope`
runs, the contraction input has been wrapped in an `Operation.IsolatedProcess`.
Isolation deliberately breaks expression embedding ("only `IsolatedProcess`
breaks expression embedding"), so the structural query `uniqueNonZeroOffset`
cannot see through the boundary, returns null, and the dense loop is emitted.

Crucially, **the isolation is not a bug — it is a load-bearing decision made by
`Process.optimize`**. Isolating that subexpression is precisely what prevents the
exponential expression-tree growth the gradient machinery exists to avoid. So we
have a genuine architectural tension:

> Isolation prevents expression explosion (needed) but makes the subexpression
> opaque, hiding the selection structure that the gather collapse requires.

The reframing this document argues for: **the isolation *decision* is the lever.**
`Process.optimize` chose to isolate a process that, had it stayed embedded (or
been rewritten before isolation), would have collapsed to something far cheaper
than what isolation now forces. The optimizer made a locally-reasonable call
(this looks big, isolate it) that is globally suboptimal (it was a selection that
would have evaporated). That is not unique to subset gradients — it is a general
weakness in how isolation decisions are made, and improving it is a
platform-wide opportunity, not a subset-specific patch.

## Empirical findings (2026-06-03)

Two probe cycles on `SubsetDeltaCollapseTests.subsetSumDeltaDiagnostic`
(`subset(x).sum().delta(x)`, n=8 k=4) — with `AggregatedProducerComputation.enableLogging`,
`ScopeSettings.enableExpressionWarnings`, and temporary instrumentation (since
reverted) — pinned the mechanism precisely. They confirm the thesis above and
correct two assumptions the earlier design carried.

### Finding 1 — the bail is the `IsolatedProcess` boundary, and it is size-independent

The exact chain when `AggregatedProducerComputation.prepareScope` queries its
input for a unique offset:

```
inputArg.uniqueNonZeroOffset(...)
  -> CollectionVariable.uniqueNonZeroOffset   (producer = ReshapeProducer, traversable) — delegates
  -> ReshapeProducer.uniqueNonZeroOffset      (inner = IsolatedProcess, NOT a TraversableExpression)
  -> returns null
  -> "Unable to determine unique offset" -> dense count-term loop (no collapse)
```

Two corrections to the earlier write-up fall out of this:

- It fires at **4 items**, so it is purely the isolation *boundary*, not a
  magnitude threshold. The size-gating intuition is irrelevant here.
- `CollectionVariable.uniqueNonZeroOffset` **already unwraps `Delegated`** on
  master (the old "GAP 1" Delegated-unwrap is already present). The remaining
  opacity is specifically that `IsolatedProcess` is not a `TraversableExpression`,
  and `ReshapeProducer.uniqueNonZeroOffset` only delegates to a `TraversableExpression`
  inner. The recognition default chain in `TraversableExpression.uniqueNonZeroOffset`
  (the `InstanceReference` guard, the value-predicate in
  `ExpressionMatrix.uniqueNonZeroOffset`) is **never reached** for this case — the
  null is returned earlier, at the boundary. So fixing the recognition predicate
  *alone* cannot fix convDelta; the structure is opaque before recognition runs.
  (Recognition still has to be fixed too — it is the *next* blocker once the
  boundary is opened — but it is not the *first* one.)

### Finding 2 — the strategy isolates it, not per-process `isIsolationTarget`

Two probes settled where the isolation decision is actually made:

- Overriding `SubsetProjectionComputation.isIsolationTarget(ctx) -> false` had
  **no effect** — the collapse still bailed. So the per-process path is not what
  isolates this selection.
- Forcing the `IsolatedProcess` constructor to log its target showed **three**
  ops isolated for `subset(x).sum().delta(x)`: `SubsetProjectionComputation`
  *plus two* `PackedCollectionEnumerate` ops that sit between the projection and
  the aggregation.

The reason the override did nothing: `ParallelProcess.optimize(ProcessContext)`
has **two** isolation pathways, and this case goes through the second:

1. per child, `optimize(context, child)` consults `child.isIsolationTarget(ctx)`
   (the per-process path);
2. then `strategy.optimize(context, this, children, ...)` lets the active
   `ProcessOptimizationStrategy` (default `ParallelismTargetOptimization`) isolate
   children via `generate(parent, children, isolateChildren=true)` based on its
   magnitude heuristics — **independent of `isIsolationTarget`**.

The subset-projection selection is isolated by pathway (2), the **strategy**.

Consequences for the plan:

- **P1 (a cascading strategy) is confirmed as the correct lever**; **P2
  (`isIsolationTarget` override) is demoted** — it cannot influence the pathway
  that actually isolates this case.
- Recognition must see a *selection* through the **enumerate/reshape chain**, not
  just the bare `SubsetProjectionComputation` — the structure is wrapped in
  `PackedCollectionEnumerate`/`ReshapeProducer` before the strategy sees it. A
  `Process`-level capability (recognition source 2 below) that propagates through
  those wrappers is therefore more robust than re-deriving structure from raw
  expressions at the strategy.

## How isolation decisions are made today

The decision lives in `Process.optimize(ProcessContext)` and the
`ProcessOptimizationStrategy` it consults. The relevant surface, as it exists in
the current tree:

- `Process.optimize(ctx)` recursively optimizes children, then the active
  strategy decides, per parent, whether to isolate each child. The strategy's
  `generate(parent, children, isolateChildren)` either wraps each child via
  `parent.isolate((Process) c)` (isolate) or rebuilds with the children inline.
- `Process.isolate(Process)` is gated by `Process.isolationPermitted(...)`, which
  honors the `explicitIsolationTargets` predicate list. `isIsolationTarget(ctx)`
  (default `false`) lets an individual `Process` subclass request isolation.
- The default strategy is `ParallelismTargetOptimization`. Its `optimize(...)`
  computes, per parent:
  - `counts[]` = child `parallelism()` values, `cn` = parent parallelism,
    `max` = greatest child parallelism, `mem` = `Process.outputSize(parent)`;
  - `currentScore = ParallelismSettings.score(cn, mem)` versus the best child
    `altScore`;
  - then a ladder of guards sets `isolate`:
    - single child with matching count, or `cn >= max` -> no isolate;
    - (`enableContextualCount`) `max <= context count` -> no isolate;
    - `max > maxCount (1<<20)` -> no isolate;
    - (`enableNarrowMax`) `max > targetCount (1<<17)` and context count
      `>= minCount (1<<8)` -> no isolate;
    - `altScore < currentScore` -> no isolate;
    - finally, if isolating but `currentScore/altScore > 4` and no explicit
      targets -> no isolate.
- `CascadingOptimizationStrategy` chains strategies, taking the first non-null
  result; specialized strategies (e.g. `ExpansionWidthTargetOptimization`,
  `AggregationDepthTargetOptimization`) sit ahead of the general one.

Two things stand out for our purpose:

1. **The signals are all magnitude signals** — parallelism counts, output size,
   expansion width, depth. *Nothing* in the decision inspects whether a child is
   *structurally a selection* (one non-zero per row). The optimizer literally
   cannot tell the difference between a genuinely-large dense subtree (isolate it,
   correctly) and a large-looking selection that will collapse to a gather
   (isolating it is exactly wrong).
2. **The hook points already exist.** `isIsolationTarget(ProcessContext)` is the
   per-process opt-in/out; `getExpansionWidth()` / `getOutputSize()` are the
   per-process magnitude signals strategies already read; `CascadingOptimization`
   is the place to insert a specialized strategy ahead of the parallelism one.
   A selection-aware signal can be added without rearchitecting the pipeline.

## The opportunity: teach isolation to recognize a collapsible selection

The goal is *not* to force the collapse unconditionally. It is to **make the
collapse reachable** — to stop `Process.optimize` from isolating a subtree that,
left visible, the existing `uniqueNonZeroOffset`/`replaceLoop` machinery would
collapse to a gather. Phrased as an isolation policy:

> If a child process is a recognizable selection (it has a determinable
> unique-non-zero-offset per row), prefer **not** to isolate it — keep it visible
> so the downstream aggregation can collapse the contraction to a direct gather —
> *unless* keeping it visible would itself cause expression explosion that the
> gather does not relieve.

This makes the optimization *optional and signal-driven*, which is the right
altitude: we are adding a new input to the isolation decision, not hard-coding a
rewrite.

### Where the recognition signal comes from

Two candidate sources for "this is a collapsible selection." Finding 2 reorders
their preference: because the selection reaches the strategy already wrapped in
`PackedCollectionEnumerate`/`ReshapeProducer`, a signal that **propagates through
those wrappers** is more robust than one re-derived from raw expressions at the
strategy. That favors source 2 (a propagating capability) as the primary, with
source 1 as the fallback recognizer it ultimately rests on.

1. **`uniqueNonZeroOffset`-style structural query, lifted before isolation.**
   The aggregation machinery already asks this question in `prepareScope` (too
   late, post-isolation). The same query — "does each row have exactly one
   structurally-non-zero column, at a computable offset?" — could be asked at
   `optimize` time, *before* the isolation decision, while the subexpression is
   still embedded and inspectable. The known recognition gap (the default
   predicate `e -> e.doubleValue().orElse(-1) != 0` tests the *value* for
   constancy, but a masked cell's value is a non-constant buffer read; its
   zero-ness is decided by the *guard* `col == source(row)`) must be fixed for
   this to work: recognition has to read the unique offset from the mask guard,
   not from `doubleValue()`. This is the contained, unit-testable half — and, per
   Finding 1, it is the *second* blocker, only reachable once the boundary is
   opened. Reaching it through an isolated subtree requires the strategy to query
   the child *before* it is isolated (which P1 does by construction: the strategy
   runs at optimize time on the still-visible children).

2. **A `Process`-level capability flag (preferred).** A computation that knows it
   is a selection (e.g. `SubsetProjectionComputation`, which is literally
   `Conditional(idx == expected, 1, 0)`) exposes that directly — e.g. an
   `isSelection()` / `getSelectionOffset(...)` capability, analogous to how
   `Algebraic.isIdentity` is recognized today. The isolation strategy reads the
   capability instead of re-deriving it from raw expressions. Each
   selection-producing computation must advertise itself, but it is far cheaper
   and lower-risk than structural re-derivation, it composes (the same capability
   that steers isolation can later steer the contraction rewrite), and —
   critically per Finding 2 — it can be made to **propagate through the
   `PackedCollectionEnumerate`/`ReshapeProducer` wrappers** that sit between the
   projection and the aggregation, which a raw-expression query at the strategy
   cannot see past.

### Where the signal plugs into the decision

Three insertion points, smallest blast radius first:

- **(P1) A new cascading strategy ahead of `ParallelismTargetOptimization`.**
  A `SelectionAwareOptimization` that returns a non-isolating `generate(...)` when
  a child is a recognized collapsible selection, and `null` (cascade) otherwise.
  This is the cleanest fit for the existing architecture — it changes *only* the
  cases it recognizes and defers everything else to the current strategy. It also
  finally exercises the `return null to cascade` path that
  `ParallelismTargetOptimization` has a TODO lamenting it does not use.
- **(P2) `isIsolationTarget(ProcessContext)` override on selection computations.**
  *Demoted by Finding 2 — does not work for this case.* A selection computation
  could in principle answer "do not isolate me," but `isIsolationTarget` only
  governs pathway (1) of `ParallelProcess.optimize`, while the subset-projection
  selection is isolated by pathway (2), the strategy. An empirical probe overriding
  `SubsetProjectionComputation.isIsolationTarget -> false` left the isolation (and
  the bail) in place. Retained here only to record that it was tried and is
  insufficient on its own.
- **(P3) A magnitude correction via `getExpansionWidth`/`getOutputSize`.** A
  selection that will collapse does not really have the output size it appears to;
  teaching its `getOutputSize`/`getExpansionWidth` to report the *collapsed* cost
  would make the *existing* score math in `ParallelismTargetOptimization` decline
  to isolate it, with no new strategy at all. Lowest code change, but the most
  indirect — it leans on the score ladder behaving as hoped, and it conflates
  "cost after a collapse that has not happened yet" with the honest current cost,
  which risks mis-scoring other decisions. Use only if P1 proves too heavy.

### Why P1 is the recommended shape

P1 keeps the new behavior **isolated to the recognized case** (selections), keeps
the magnitude-based default **unchanged** for everything else, and is **reversible
and testable in isolation** (drop the strategy from the cascade to A/B it). It
also positions the recognition signal as a reusable component: once a selection is
recognized at optimize time, the same recognition can drive (a) the
do-not-isolate decision here, and later (b) the actual contraction-to-gather
rewrite, without re-deriving the structure twice.

## Correctness: the non-negotiable

A wrong selection offset yields **silently incorrect gradients** — no exception,
just wrong numbers. Therefore, before any of P1/P2/P3 is enabled by default:

- **Collapse-on-vs-off differential test.** The same computation, run with the
  selection-aware isolation strategy installed and with it absent, must produce
  bit-for-bit (or within-tolerance) identical output across a spread of subset /
  concat / rotary / conv shapes. This is the primary guard and must exist *before*
  the strategy is wired into the default cascade.
- **Recognition unit tests.** Feed `uniqueNonZeroOffset`/the capability known
  selection and non-selection expressions; assert it recognizes selections (and
  their offsets) and, critically, returns "not a selection" for anything it cannot
  prove — a false positive here is a wrong gradient.
- **Regression coverage of both poles.** `RepeatedDeltaComputationTests.convDelta*`
  (the wide/deep contraction that must collapse) and
  `RotaryEmbeddingGradientTests.subsetGradient*` (the narrow/shallow terminal that
  must stay on its fast path) must both pass with the strategy installed.

## Scope and risk

- **Touched subsystems:** the recognition query (`uniqueNonZeroOffset` /
  `ExpressionMatrix.uniqueMatchingOffset`, or a new `Process` capability) and the
  isolation decision (`ProcessOptimizationStrategy` cascade /
  `Process.optimize`). Both are core; changes here can affect every gradient and
  reduction in the platform.
- **Upside beyond subsets:** because the lever is the general isolation decision,
  a smarter selection-aware (and, later, more broadly structure-aware) isolation
  policy can recover performance the current magnitude-only heuristics leave on
  the table across the whole platform, not just for these subset examples. The
  existing strategies (`ParallelismTargetOptimization`, expansion-width,
  aggregation-depth) are explicitly acknowledged as imperfect; this is an avenue
  to make them better.
- **Risk posture:** treat the isolation-decision change as feature-flagged (a
  cascade entry that can be removed) and gate its default-on status on the
  differential test above. Do not change the default isolation behavior for
  non-selection processes as part of this work.

## Decision gate (resolved)

This direction was predicated on convDelta still being the failing side under the
current budget-gated `Sum` code. CI on `feature/lora-gradients` resolved it to
**outcome 1**: `convDelta*` times out while the rotary subset/concat gradients
need the Sum reordering. (Outcome 2 — both pass, the budget gate threads the
needle — and outcome 3 — rotary fails, threshold mis-tuned — did not occur.) The
normative stance therefore holds and we pursue P1.

## Implementation plan

Sequenced, each step independently verifiable and the correctness harness gating
the behavioral one:

1. **Correctness harness — done.** `SelectionContractionCollapseTests` (7 tests)
   pins the gradient *values* of the selection-contraction shapes (subset,
   weighted subset, concat, multiply-sum) with oracles plus
   direct-vs-`Process.optimized` differentials. Green on the master baseline (run
   `528e7971`). The differentials become the collapse-on-vs-off guard the moment
   the strategy changes behavior.

2. **Selection capability (recognition source 2).** Add an `isSelection()` /
   `getSelectionOffset(...)` capability, implemented by `SubsetProjectionComputation`
   (and the concat analogue), that **propagates through
   `PackedCollectionEnumerate` / `ReshapeProducer`** so it survives to the strategy.
   Back it by the guard-based offset recognition (source 1): read the unique offset
   from the mask guard `col == source(row)`, not from `doubleValue()`. Unit-test
   recognition in isolation, including that it returns "not a selection" for
   anything it cannot prove (a false positive is a wrong gradient).

3. **`SelectionAwareOptimization` strategy (P1).** A cascade entry ahead of
   `ParallelismTargetOptimization` that returns a non-isolating `generate(...)` for
   children recognized (via step 2) as collapsible selections, and `null`
   (cascade) otherwise. This stops pathway (2) from isolating the selection, so the
   existing `uniqueNonZeroOffset`/`replaceLoop` collapse in
   `AggregatedProducerComputation` fires. Gate default-on status on the harness
   differentials plus the both-poles regression
   (`RepeatedDeltaComputationTests.convDelta*` and
   `RotaryEmbeddingGradientTests.subsetGradient*`).

The next concrete step is **step 2** (the selection capability + guard-based
recognition, with its unit tests), since step 1 is complete.
