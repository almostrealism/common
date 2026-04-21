# Expansion Width Optimization

## Context and Origin (why this plan exists)

The MIDI model test suites (`MoonbeamMidiTest`, `MidiTrainingTest`,
`MoonbeamFineTuningTest`, `SkyTntMidiTest`) started failing on
`feature/skytnt-midi-model` after commit `99bc5d0b8` (2026-04-15,
*"Adjusted RotationFeatures methods to return CollectionProducer."*).

That commit changed `RotationFeatures.computeRopeFreqs` from returning an
evaluated `PackedCollection` (a materialised buffer) to a lazy
`CollectionProducer` (an unevaluated graph). Downstream users —
`ropeRotation`, `mraRopeRotation`, and the RoPE consumers in
`SkyTntMidi`/`AttentionFeatures` — continued to use it as the collection
argument of a gather (`c(shape, freqCis, cosIdx)`), and the compiled C
kernel exploded to tens of megabytes on a single line, hanging
`cc1`/`clang -cc1` in native compilation.

The blow-up was papered over in `mraRopeRotation` (2026-04-20, see
§"Existing workaround" below) by manually replacing six lazy
`CollectionProducer` index maps and all per-group masks with
`PackedCollection` + `setMem` loops computed at model-construction time —
the same pattern `ropeRotation` already used. `RopeCompilationRegressionTest.mraRopeRotationWithLazyFreqCis`
now passes (~37 s) with that workaround in place.

PR #196 landed a fail-loud `getCountLong() <= 0` check that used to be
silently tolerated by an `if (ctx > 0)` guard; the same producer chain
that explodes the kernel also hits that check, so some CI runs surface
as `HardwareException: Cannot compile greaterThan` / `subset` rather
than a hung compiler. Those are wrappers around the same root cause.

**What's missing.** The workaround is local to `mraRopeRotation` and
relies on a human having noticed the pattern; `ropeRotation`,
`SkyTntMidi` (PDSL-dispatched), `AttentionFeatures.sequenceAttention`,
any future consumer, and every other place where a non-trivial lazy
producer gets inlined through a gather or a conditional-emitting
operation will walk into the same compile-time blow-up. This plan is
the generalised fix: give the framework a way to recognise this pattern
during `Process.optimize()` and isolate the exploding sub-expression
automatically, the same way `AggregationDepthTargetOptimization` was
designed to handle reductions.

## Problem

Compile failures in `MoonbeamMidiTest`, `MidiTrainingTest`, and
`SkyTntMidiTest` (and anything downstream that composes RoPE, concat
over large lazy chains, or gathers with non-affine indices) surface as:

- `Hardware Native compiler failure (1) on org.almostrealism.generated.GeneratedOperationN`
- `Killed / gcc: fatal error: Killed signal terminated program cc1`
- `TestTimedOut test timed out after 60000 milliseconds`
- `Hardware Cannot compile subset / greaterThan` (expression simplification
  bailing out)

All are symptoms of the same root cause: the generated C kernel contains a
single enormous expression. At `seqLen=16` with `AR_HARDWARE_DRIVER=native`
the generated `GeneratedOperation1.c` had a **44,386,601-character expression
on one line**; `clang -cc1` climbed past 2.4 GB RSS before being killed.

## Root Cause (as of 2026-04-20)

`RotationFeatures.computeRopeFreqs` returns a lazy `CollectionProducer`:

```
concat_dim2(
    reshape(cos(matmul(positions, invFreq))),
    reshape(sin(matmul(positions, invFreq))))
```

`RotationFeatures.ropeRotation` and `mraRopeRotation` use this lazy `freqCis`
as the collection argument of a gather: `c(shape, freqCis, cosIdx)` and
`c(shape, freqCis, sinIdx)`. The gather's lambda is
`args[1].getValueAt(args[2].getValueAt(idx).toInt())` — `freqCis.getValueAt(j)`
is inlined into the kernel's emitted expression **at every output element**.

Two properties of this specific `p[x]` make it explode where almost every
other `p[x]` does not:

1. **`p` is a `concat`, not a buffer.** `Concat.getValueAt(j)` emits a
   ternary `Conditional(j < cut, first[j], second[j - cut])`. A ternary keeps
   both branches in the expression tree unless the compiler can statically
   prove which side fires. For a buffer-backed `p`, `getValueAt` is a single
   memory reference — no branching.
2. **`x` is a non-linear computed index**
   (`(indices mod freqDim) * 2 + positionScalar`). The `mod` and the scalar
   producer dependency make `j` non-affine in the kernel's output slot, so
   the concat's `j < cut` predicate cannot be statically resolved, and
   neither branch is dropped.

`mraRopeRotation` then wraps the whole thing in a `greaterThan` — another
ternary that keeps both branches — so at every one of 48 output slots each
`freqCis` gather is multiplied by 2 (inner concat) × 2 (outer greaterThan),
spread across every (group × cos/sin). That is the 44 MB.

## Why the existing cascade misses this

Active cascade (Hardware.java): `ParallelismDiversityOptimization →
TraversableDepthTargetOptimization → ParallelismTargetOptimization`.

- `ParallelismDiversityOptimization` requires ≥8 distinct child-parallelism
  values and an average 64× ratio. mraRopeRotation has 2–3 distinct
  parallelisms, nowhere near threshold.
- `TraversableDepthTargetOptimization` counts Computation+TraversableExpression
  nodes along the deepest Process path. `freqCis` Process-depth is ~6–8,
  right at the limit of 8. At the gather level, `max(6, 3) + 1 = 7 ≤ 8` — it
  does not fire there. It *does* fire higher up (at Sum-over-groups), but
  isolating the 6 Products does not break the embedding that lives *inside*
  each Product — the gather with lazy freqCis is still there.
- `ParallelismTargetOptimization` is parallelism-based, not expression-size.

The explosion lives in expression embedding (amplification through
`getValueAt`), not in Process-tree depth or parallelism ratios. No current
strategy sees it.

## Existing workaround (to retire once the general fix lands)

`RotationFeatures.mraRopeRotation` was modified in-place on 2026-04-20 to
replace six lazy `CollectionProducer` index maps (`cosRelIndexMap`,
`sinRelIndexMap`, `x1IndexMap`, `x2IndexMap`, `outputSourceMap`,
`componentMap`) and all per-group masks with `PackedCollection` +
`setMem` arrays computed at model-construction time. All materialised
collections are added to a `captured` list so the kernel holds
references to them for their lifetime.

This mirrors what `ropeRotation` already did for its equivalent maps,
and it makes the generated kernel compile (`mraRopeRotationWithLazyFreqCis`
passes ~37 s) — but it is a **point fix** that addresses the specific
kernel `mraRopeRotation` constructs. It does not:

- protect `ropeRotation` (already uses `setMem`), `SkyTntMidi`,
  `AttentionFeatures.sequenceAttention`, or future RoPE-like layers
  from the same blow-up when a non-trivial lazy producer is passed to
  their gather-target / conditional-consuming slot;
- cover the `GRUDecoder` change in the same commit that removed
  `.evaluate()` from `h[l] = cp(output).subset(shape(dh), l * dh)`, which
  leaves an open latent lazy graph waiting to explode under the same
  conditions;
- give the framework any general mechanism to notice the pattern and
  isolate automatically, so every new caller that falls into the same
  shape will hit the same cc1-hang before anyone realises what happened.

Once the expansion-width strategy in this plan is wired up and actually
reaches the producers in question (see §"Empirical finding" below), the
`setMem` workaround in `mraRopeRotation` should be reverted and the
strategy must continue to keep the tests passing on its own. Leaving the
workaround in place after the general fix lands will hide future
regressions in the strategy itself.

## Prior art: `AggregationDepthTargetOptimization`

`AggregationDepthTargetOptimization` (in `base/code/`, **not currently wired
into any live cascade** — verified 2026-04-20) handles the analogous problem
for reductions. Its mechanic:

1. A Process carries an "aggregation count" — how many inputs are read to
   produce one output (element-wise = 1, matmul inner-dim N = N).
2. `ParallelProcessContext.aggregationCount` accumulates this as the tree is
   walked (currently via max).
3. The strategy isolates children when `aggregationCount > 64 AND depth > 12
   AND all children have parallelism ≥ 128`.

This is exactly the shape we want — but reductions are not the only kind of
expression-embedding amplifier.

## Design: expansion width

Generalise aggregation count into **expansion width**: the factor by which
inlining a producer's `getValueAt` multiplies the emitted expression size
per consumer site.

| Producer kind         | Expansion width                        |
|-----------------------|----------------------------------------|
| Element-wise          | 1                                      |
| Aggregation (matmul, reduce, sum) | N (reduction width)        |
| Concat of N pieces    | N (branches in the emitted conditional) |
| GreaterThan / LessThan / Conditional | 2 (two branch sides)    |
| Choice (k-way)        | k                                      |
| Mask                  | 2                                      |
| Buffer read           | 1                                      |

Each of these produces an expression whose size is proportional to the
expansion width: a reduction unrolls N terms per output; an N-way concat
emits N branches per gather; a ternary emits 2 branches per use.

The user's observation: *"If I have a loop of 4 over a loop of 2: my
expression would become 4 pairs for a total of width 8 if it was embedded
all the way down with no isolation."* — so stacked aggregations also
compound. Whether the accumulation should be max (current behaviour for
aggregations) or product is an open empirical question; we preserve it as a
tuning knob and ship with max initially to stay compatible with prior
behaviour, with a path to product-semantics later.

## Plan

### 1. Determine AggregationDepthTargetOptimization usage — DONE

Not in any active cascade. Safe to generalise.

### 2. Introduce `Process.getExpansionWidth()`

- Default: `return 1;` on `Process`.
- Override:
  - `CollectionConcatenateComputation` → number of concatenated pieces.
  - `GreaterThanCollection`, `LessThanCollection` → 2.
  - Matmul / reduction-emitting computations → inner reduction width (this
    reuses the existing aggregation-count hook where present).
  - `ConditionalExpressionBase` subclasses, `Mask`, `Choice`: audit and
    override where the emitted expression branches.

### 3. Extend `ParallelProcessContext` with `expansionWidth`

- Add field, getter, and propagation path through the existing
  `ParallelProcessContext.of(ctx, c, ...)` factory chain.
- For initial implementation use max semantics (matches existing
  aggregation behaviour) so we don't introduce regressions.
- Leave a TODO and a `static boolean` switch for trying product semantics
  later without a class rewrite.

### 4. Introduce `ExpansionWidthTargetOptimization`

- Structural twin of `AggregationDepthTargetOptimization`:
  `expansionWidth > THRESHOLD AND depth > limit AND no child below
  PARALLELISM_THRESHOLD → generate(parent, children, true)`.
- Thresholds begin at the existing aggregation values (64 / 12 / 128) and
  are tuning knobs.

### 5. Cascade wiring

- In `Hardware.java`, replace `TraversableDepthTargetOptimization` (or
  insert before it) with `ExpansionWidthTargetOptimization` once tests
  demonstrate it subsumes the aggregation-only case without regressions.
- Delete `AggregationDepthTargetOptimization` once the new strategy is
  known to cover it; keep a grep-audit in this document's notes.

### 6. Tests for expansion-width computation

Unit tests in `base/relation` or `engine/utils`:

- Element-wise ops: `getExpansionWidth() == 1`.
- Concat of 3 pieces: `getExpansionWidth() == 3`.
- Greater-than: `getExpansionWidth() == 2`.
- Matmul with inner=N: `getExpansionWidth() == N`.
- Composite trees: `ParallelProcessContext` propagation yields the correct
  accumulated value under max semantics, and a toggle-on test verifies
  product semantics.

These do not need to hit hardware; they exercise the hook methods and the
context propagator directly.

### 7. Regression tests at the real shape

- `engine/ml/src/test/java/org/almostrealism/ml/RopeCompilationRegressionTest.mraRopeRotationAtMoonbeamScale`
  currently reproduces the compile explosion. After the new strategy is
  wired in, this test must pass without the generated C containing
  megabyte-scale lines.
- A companion assertion on the emitted C file size (or on the compiled
  kernel's `countNodes`) would make the regression harder to reintroduce.
  Low priority but on the docket.

### 8. Explore standard-set inclusion

- Once the expansion-width strategy passes the narrow regression tests,
  run the broader ML / audio test suites to confirm it does not over-
  isolate. Instrument the strategy's listener to count fire-sites per test
  and inspect any surprising hits.
- Decide between (a) fully replacing `AggregationDepthTargetOptimization`,
  or (b) keeping both in the cascade with the new one first, if there's
  evidence the old one still pays for itself on any current workload.

## Open empirical questions (park for after initial implementation)

- Max vs product accumulation.
- Thresholds for expansion width, depth, and parallelism floor.
- Whether expansion width should be expressed as a long (size of fanout) or
  a more structured metric (e.g. number of distinct branches observed along
  the path).
- Whether to merge `aggregationCount` into `expansionWidth` immediately in
  the context, or keep them as parallel fields and deprecate the old one
  later.

## Risks

- **False positives** — an expansion-width strategy firing on cheap
  conditionals (e.g., a masked scalar choice) could add kernel launch
  overhead. Mitigated by the parallelism-floor check and the depth
  threshold.
- **Interaction with existing strategies** — `TraversableDepthTargetOptimization`
  isolates children uniformly when Process-tree depth exceeds 8; the new
  strategy isolates when expansion width compounds. Overlap is possible.
  Cascade ordering decides precedence.
- **Over-isolation on reductions that were previously handled by
  AggregationDepthTargetOptimization in design but never in practice
  (because it was unused).** The existing code paths may have grown to
  assume no such isolation happens. Mitigation: gradual rollout, watch
  kernel counts and latencies in the broader test run.

## Status (2026-04-21) — ReshapeProducer.optimize fix working, all tests pass

### Compilation error introduced and fixed (2026-04-21)

The commit `cfdd25e10` (Fix ReshapeProducer.optimize Strategy Skip) broke compilation in
`engine/utils/src/test/java/io/almostrealism/compute/test/ReshapeProducerStrategyTests.java`
with three errors:

1. **Lines 70 & 105 — "incompatible types: invalid functional descriptor for lambda expression"**
   `ProcessOptimizationStrategy.optimize()` is a generic method (`<P extends Process<?, ?>, T>`).
   Java prohibits lambdas from implementing interfaces whose single abstract method has type
   parameters. The test used lambda syntax to create the strategy; fixed by replacing both
   lambdas with explicit anonymous class instances that override the generic method with the
   correct signature.

2. **Line 112 — "type CollectionProducer does not take parameters"**
   `CollectionProducer` is a raw interface (no type parameter declared). `CollectionProducer<?>`
   is not valid Java; fixed by changing the declaration to the raw type `CollectionProducer`.

After applying the fix, all modules affected by the prior commit compile cleanly:
`ar-code`, `ar-relation`, `ar-hardware`, `ar-algebra`, `ar-utils`, `ar-ml`.

### ReshapeProducer.optimize fix status: WORKING

The fix to `ReshapeProducer.optimize(ProcessContext)` introduced in commit `cfdd25e10` is
correct and all associated tests pass. The override now:
1. Creates a `ParallelProcessContext` via `createContext(ctx)`.
2. Recursively optimizes the single child (`innerProducer`) within that context.
3. Invokes `strategy.optimize(context, this, optimizedChildren, ...)` — the step that
   was previously missing — so the strategy cascade fires on the `ReshapeProducer` node itself.
4. Falls back to `generateReplacement(optimizedChildren)` when the strategy returns `null`.

### Test results (2026-04-21) — all pass

- `ReshapeProducerStrategyTests.strategyInvokedOnGreaterThanDirectly` — **PASS**
  (baseline: strategy fires on GreaterThanCollection when it is the direct entry point)
- `ReshapeProducerStrategyTests.strategyReachesReshapeProducerAfterFix` — **PASS**
  (regression guard: strategy is now invoked on ReshapeProducer node when it wraps a
  GreaterThanCollection)
- `ExpansionWidthTests` (all 7) — **PASS**

Total: 9 tests, 0 failures, 0 errors.

---

## Status (2026-04-20)

- Reproducer `mraRopeRotationAtMoonbeamScale` present in
  `engine/ml/src/test/java/org/almostrealism/ml/RopeCompilationRegressionTest.java`
  at `seqLen=16` with 6 head groups. Hangs/OOMs cc1 on the current codebase,
  confirming the explosion at sub-production scale.
- `CollectionFeatures.c(shape, collection, index)` was patched (commit
  `4d30dc06c`) to convert the gathered value to `int` before using it as an
  index — that fixes a separate type leak observed en route to this issue,
  but does not address the expansion-width blow-up.

### What was built (2026-04-20)

1. `Process.getExpansionWidth()` — default `1`, overridden on
   `PackedCollectionPad`, `GreaterThanCollection`, `LessThanCollection`
   (each returns `2` — two-branch Conditional emitted). Audit of other
   candidate classes (concat via N-way, Choice, Mask, matmul reductions) is
   future work.
2. `ParallelProcessContext.expansionWidth` accumulator, with multiplicative
   (product) propagation enabled by default via
   `ParallelProcessContext.enableProductExpansionWidth = true`. Max
   semantics still available via the toggle for empirical comparison.
3. `ExpansionWidthTargetOptimization` strategy, structural twin of the
   never-wired `AggregationDepthTargetOptimization`. Thresholds:
   `EXPANSION_THRESHOLD = 2`, `PARALLELISM_THRESHOLD = 16`, `limit = 12`.
4. Unit tests (`engine/utils/src/test/java/io/almostrealism/compute/test/
   ExpansionWidthTests.java`) verifying per-producer width, max vs. product
   propagation, and single-child context pass-through. All 7 pass.
5. Strategy registered in the Hardware default cascade
   (`base/hardware/src/main/java/org/almostrealism/hardware/Hardware.java`)
   just before `TraversableDepthTargetOptimization`.

### Empirical finding: the strategy is never recursively invoked on the producers that actually cause the blow-up

Instrumenting the strategy with a file-flushed diagnostic (via
`ExpansionWidthTargetOptimization.enableDiagnostics` &rarr;
`/tmp/ar-ew-diag.log`) and running `mraRopeRotationAtMoonbeamScale`
produced exactly **one** invocation during the entire compile, at the
top-level `OperationList` (`depth=1`, `ew=1`, `belowFloor=3`). The
reproducer continues to time out at 60 s / OOM-kill `cc1`.

Root cause of the coverage gap, from code inspection:

- `CompiledModel.compile` calls `forward.flatten().optimize()` — this
  invokes the cascade on the outer `OperationList`.
- `OperationList.getChildren()`
  (`base/hardware/.../OperationList.java:969`) maps each entry via
  `o instanceof Process ? (Process<?,?>) o : Process.of(o)`. Bare
  `Supplier<Runnable>` entries become `Process.of(supplier)` wrappers.
- `Process.of(...)` returns a bare `Process<>` anonymous subclass &mdash;
  **not** a `ParallelProcess`. Its `optimize(ctx)` falls through to the
  default no-op on `Process`, so recursion stops at the wrapper.
- The `CollectionProducer`-based computations that actually build the
  exploding expression (`PackedCollectionPad`,
  `GreaterThanCollection`, the gather, etc.) live inside the kernels
  produced from those wrappers. They are compiled via
  `NativeCompiler.compile(...)` at `Runnable.run()` time, and by that
  point the expression tree is frozen &mdash; the optimisation cascade
  is not invoked on them.

So: the strategy machinery is correctly wired *to the top-level
`OperationList`*, but the producers that amplify the expression never
pass through the cascade.

### Follow-up required for the strategy to actually fire on producers

Two options, not yet done:

1. **Optimise individual producers before they are wrapped into the
   forward pass.** `CellList.flatten()` / `Cell.push(Producer)` would
   need to call `Process.optimized(producer)` before converting the
   producer to a `Runnable` supplier. Localised change; preserves the
   current top-level optimize pass.
2. **Make `Process.of(supplier)` preserve `ParallelProcess`-ness when
   its input is a `ParallelProcess`.** Broader change; affects anywhere
   `Process.of(...)` is used as a wrapper. Potentially surprising
   behaviour for existing callers.

Both options need validation against the broader ML/audio test suites
before being landed; neither is tackled in this pass.

### Related follow-up (tracked here so the issue doc can retire)

- **`GRUDecoder.java`** (same commit `99bc5d0b8` that introduced the
  lazy `freqCis`) removed `.evaluate()` from hidden-state extraction:
  `h[l] = cp(output).subset(shape(dh), l * dh);`. That flips `h` from
  `PackedCollection[]` to `CollectionProducer[]`. Downstream code that
  treats `h[l]` as a concrete buffer will silently build a deeper lazy
  graph and hit the same blow-up under the right conditions. Either
  restore `.evaluate()` or confirm every consumer of `h[l]` is lazy-safe.
- **Targeted diagnostic fail-loud.** A `getCountLong() <= 0` check
  inside `GreaterThanCollection` and `PackedCollectionSubset` would
  surface the broken producer at the source instead of wrapping it as a
  generic `HardwareException: Cannot compile subset / greaterThan` at
  kernel compile time. Would have shortened this investigation by hours.

### Artefacts preserved for further work

- `/tmp/ar-ew-diag.log` &mdash; captured diagnostic output showing the
  one-and-only invocation at the outer OperationList.
- `ExpansionWidthTargetOptimization.enableDiagnostics` / `.diagnosticsFile`
  &mdash; the same mechanism can be re-enabled to observe behaviour after
  either follow-up option is implemented.
- Four runs confirmed the gap under varying thresholds: `f4361ff1`
  (defaults), `24e20910` (product semantics + threshold 2), `6db86339`
  (+ parallelism floor lowered to 16), `9882159a` / `83cd2c75`
  (instrumented). All timed out at the same cc1-hang point.

## Where the Process Tree Is Broken (2026-04-21)

Catalogues every identified site where either (a) a bare `Supplier<Runnable>`
(non-`Process`) enters an `OperationList`, or (b) the cascade enters a `Process`
node but the `ProcessOptimizationStrategy` is never invoked on it or its
descendants. This is the structural reason the `ExpansionWidthTargetOptimization`
fires only once and never reaches the `GreaterThanCollection` / gather nodes that
cause the kernel blow-up.

### Sites that add bare `Supplier<Runnable>` to an OperationList

| File:Line | Description | Judgment |
|---|---|---|
| `base/hardware/.../OperationList.java:969–975` | `getChildren()` maps each entry via `o instanceof Process ? (Process<?,?>) o : Process.of(o)`. Bare lambdas become `Process.of(supplier)` wrappers — not `ParallelProcess`, so `optimize(ctx)` is the default no-op and cascade halts. | Legitimate hub design: `OperationList` was meant to hold arbitrary `Supplier<Runnable>`. The fix requires either making `Process.of()` preserve `ParallelProcess`-ness when its input is a `ParallelProcess`, or pre-optimising producers before converting them to `Runnable` suppliers. |
| `domain/graph/.../Cell.java:335–362` | `Cell.of(Factor func).push(in)` returns `r.push(func.getResultant(protein))`; if the operator returns a plain `Supplier<Runnable>` lambda it is not a `Process` and becomes `Process.of()` in the parent list. | Legitimate cell abstraction. Any cell whose operator lambda does not return a `Process` loses optimisation context at that boundary. |
| `domain/graph/.../Cell.java:377–404` | `Cell.of(Function<T, Supplier<Runnable>> func).push(in)` — same pattern. | Same judgment as above. |
| `engine/audio/.../CellList.java:926` | `tick()` adds `r.push(c(0.0))` for each root cell to a `CellList Tick` OperationList; whether the result is a `Process` depends on the concrete cell type. | Not in the mraRopeRotation reproducer's path; same structural pattern applies to the audio tick path. |
| `domain/graph/.../CompiledModel.java:299` | `forward.add(cells.get(i).push(in.get(i).get()))` — each layer's push result is added to the forward `OperationList`. For `DefaultCellularLayer` the result is itself an `OperationList` (which IS a `Process`), but other cell implementations may return plain lambdas. | Mixed: `DefaultCellularLayer`'s path is fine (returns `OperationList`); the risk is other cell types. |

### Sites where the cascade enters a Process but the strategy is skipped

| File:Line | Description | Judgment |
|---|---|---|
| `compute/algebra/.../ReshapeProducer.java:441–448` | `ReshapeProducer.optimize(ctx)` is a custom override that calls `optimize(ctx, innerProducer)` directly but does **not** call `ParallelProcess.super.optimize(ctx)`. The `CascadingOptimizationStrategy` is never invoked for the `ReshapeProducer` node itself. | Bug: the override was likely written to avoid creating a child context for a single-input reshape, but it also silently skips the strategy. This is the specific chokepoint that prevents `GreaterThanCollection` and gather nodes inside `mraRopeRotation` from ever being seen by `ExpansionWidthTargetOptimization`. |
| `base/hardware/.../MemoryDataCopy.java:141` | `MemoryDataCopy` implements `Process` but not `ParallelProcess`. Its `optimize(ctx)` inherits the default no-op on `Process` and returns `this`. It appears as a sibling to `Assignment` in the flattened forward pass. | Legitimate leaf: `MemoryDataCopy` is a single kernel call with no sub-expression tree to optimise. Halting the cascade here is correct. |

### Specific call path for `mraRopeRotationAtMoonbeamScale` reproducer

1. `CompiledModel.compile()` (`domain/graph/.../CompiledModel.java:296–302`)
   calls `forward.flatten().optimize()` — cascade fires once on the outer
   `OperationList`.
2. `forward` contains `[Assignment, MemoryDataCopy]` after `flatten()`.
   `MemoryDataCopy` is a leaf (no-op on optimize). `Assignment` IS a
   `ComputableParallelProcess` — cascade recurses into it.
3. `Assignment.getChildren()` (via `ComputationBase.getChildren()`) returns
   `[destination, traverse(axis, result)]`. The destination is typically
   a `p(output)` reference; `traverse(axis, result)` returns a
   `ReshapeProducer`.
4. **`ReshapeProducer.optimize(ctx)` (lines 441–448) is the chokepoint.**
   It calls `optimize(ctx, innerProducer)` for the single inner child but
   does NOT call `ParallelProcess.super.optimize(ctx)`, so the strategy is
   never invoked on `ReshapeProducer`. The inner producer is visited, but
   any strategy decision for the `ReshapeProducer` level is skipped.
5. Below the `ReshapeProducer` the computation tree contains the
   `GreaterThanCollection` (expansion width = 2) and the gather operations
   over the lazy `freqCis` producer. These nodes are never presented to
   `ExpansionWidthTargetOptimization`.

**Fix required:** `ReshapeProducer.optimize(ctx)` must call
`ParallelProcess.super.optimize(ctx)` (or invoke the strategy explicitly)
so that the strategy gets a chance to fire on the `ReshapeProducer` node
before the custom single-child recursion proceeds. Until this is fixed,
`ExpansionWidthTargetOptimization` cannot protect any computation that
passes through a `reshape` / `traverse` on its way to the kernel.

---

## Post-ReshapeProducer-fix Diagnosis of mraRopeRotationAtMoonbeamScale

Run date: 2026-04-21. The `ReshapeProducer.optimize` fix from commit `cfdd25e10` is
in place. The two minimal strategy tests pass. This section documents what happens
when the full reproducer is run with the JUnit timeout raised to 600 s.

### How the investigation was run

- `RopeCompilationRegressionTest.mraRopeRotationAtMoonbeamScale` was run with
  `@Test(timeout = 600000)` (10 minutes) via the MCP test runner
  (`engine/ml`, profile `pipeline`).
- `HardwareOperator.enableLargeInstructionSetMonitoring = true` and
  `instructionSetOutputDir = "/tmp/ar-moonbeam-kernels"` were set in the test
  to capture generated C files > 50 KB.
- An attempt was made to enable `ExpansionWidthTargetOptimization.enableDiagnostics`
  via reflection (the class is in `ar-code` which is not on `engine/ml`'s compile
  classpath). The reflection threw `ClassNotFoundException` at runtime:
  `io.almostrealism.compute.ExpansionWidthTargetOptimization` is not on the
  forked test JVM's runtime classpath from the `engine/ml` module. Diagnostics
  could not be enabled for this run.
- Test and diagnostic flags were restored to their original state (timeout = 60 s,
  monitoring disabled) after the run.

### Strategy invocation count and sites

**Could not be determined from this run** — the `ClassNotFoundException` prevented
the `enableDiagnostics` flag from being set. Based on the prior run (2026-04-20,
before the ReshapeProducer fix, documented above) and code analysis:

- Before the fix: exactly **1** strategy invocation, at the outer `OperationList`
  (`depth=1`, `ew=1`, `belowFloor=3`). The strategy did not fire.
- After the fix: the strategy is now also invoked on the `ReshapeProducer` node
  (because `ReshapeProducer.optimize(ctx)` now calls the strategy). However, at
  the `ReshapeProducer` level the accumulated context has `depth ≈ 3` and
  `ew ≈ 1` (no high-expansion-width ancestors above it). Both conditions of the
  strategy fail: `depth > 12` is false, and `ew > 2` is false (the
  `GreaterThanCollection` with `ew=2` is BELOW the `ReshapeProducer`, not above
  it — it has not yet been encountered walking top-down). The strategy does not
  fire. The kernel is not isolated.

### Generated C kernel count and largest kernel size

- **Total generated `.c` files**: 6 (4 tiny utility kernels + 2 computation kernels)
  - `org.almostrealism.c.Free.c` — 247 bytes
  - `org.almostrealism.c.Malloc.c` — 267 bytes
  - `org.almostrealism.c.NativeRead.c` — 497 bytes
  - `org.almostrealism.c.NativeWrite.c` — 480 bytes
  - `org.almostrealism.generated.GeneratedOperation0.c` — **980 bytes** (trivial)
  - `org.almostrealism.generated.GeneratedOperation1.c` — **46,416,371 bytes (44.3 MB)**
- **Longest line in GeneratedOperation1.c**: **44,386,601 characters (44.3 MB)** —
  the entire kernel expression is on a single line.
- This is **identical to the pre-fix measurement** from 2026-04-20 (the planning
  doc records "a 44,386,601-character expression on one line"). The
  `ReshapeProducer.optimize` fix had zero effect on the generated kernel size.

### Whether `cc1` completes or is OOM-killed

`cc1` was **OOM-killed** (exit code 1) after approximately **155 seconds** of
native compilation. Observed memory growth:

- At ~91 s total run time: `cc1` RSS = 3,065 MB (3.1 GB)
- At ~128 s total run time: `cc1` RSS = 3,638 MB (3.7 GB)
- At ~167 s total run time: `cc1` was killed

The test method ran for **184.748 seconds** before failing with:

```
HardwareException: Native compiler failure (1) on org.almostrealism.generated.GeneratedOperation1
```

The failure originates at `CompiledModel.forward()` (lazy kernel compilation), not
at `model.compile(false)`. The `optimize()` cascade runs during `compile()` —
the kernel C code is generated and passed to `cc1` lazily on the first `forward()`
invocation.

**The `@Test(timeout=60000)` was masking the real failure mode.** With the old
60-second timeout, JUnit killed the test thread before `cc1` finished dying. The
reported failure was `TestTimedOut` (a timeout exception). With the timeout raised
to 600 s, the true failure is `HardwareException: Native compiler failure (1)` —
the OOM kill of `cc1`.

### Root cause of why the fix is insufficient

The `ReshapeProducer.optimize` fix is necessary but not sufficient. The strategy is
now invoked at one additional node, but it still never fires because:

1. **The depth threshold (`limit = 12`) is not met at any relevant node.** The call
   path from root to `ReshapeProducer` has depth ≈ 3–4. `depth > 12` is always
   false at the nodes that matter.

2. **The accumulated expansion width at those depths is ≈ 1.** Expansion width
   accumulates top-down: starting from the `OperationList` root (ew=1) through
   `Assignment` (ew=1) to `ReshapeProducer` (ew=1). The `GreaterThanCollection`
   (own ew=2) is a child of `ReshapeProducer`; it is encountered only when the
   cascade recurses INTO the children. By the time the cascade reaches a node
   where ew has grown to 2, depth is still only ≈ 4, nowhere near 12.

3. **The firing condition requires BOTH `ew > 2` AND `depth > 12` simultaneously.**
   In this computation graph those two conditions are never simultaneously true. The
   high-expansion-width nodes sit at shallow depth, and by the time the cascade
   reaches deep enough, the accumulated ew has not grown further (the gather and
   matmul nodes below do not carry high expansion widths in the accumulated context).

### Proposed next step

The evidence points to a **threshold mismatch**: the strategy's depth floor is
calibrated for much deeper process trees than what `mraRopeRotation` produces.
There are two viable paths:

**Option A — Lower the depth limit.** Reduce `ExpansionWidthTargetOptimization.limit`
from `12` to a value small enough (e.g. `2`) that the strategy fires at the
`ReshapeProducer` depth. This alone is not enough: the accumulated `ew` at depth 2
is still ≈ 1, so `ew > EXPANSION_THRESHOLD` (= `ew > 2`) also fails. Both the
depth floor and the threshold need adjustment together.

**Option B — Add a child-own-ew check (recommended).** Augment the firing condition
to also trigger when any child's `getExpansionWidth()` exceeds a threshold,
independent of the accumulated context `ew`. Specifically: if any direct child of
the current node returns `getExpansionWidth() > 1`, isolate that child. This would
fire at the `ReshapeProducer` level because its child (`GreaterThanCollection`)
returns `getExpansionWidth() = 2`. This is the most targeted fix: "if you are about
to inline a branching or aggregating producer into the parent's kernel, isolate it
first regardless of how deep you are."

**Option C — Diagnose from `engine/utils` instead of `engine/ml`.** The
`ExpansionWidthTargetOptimization` class is on the `engine/utils` classpath (that
is where `ExpansionWidthTests` live). A diagnostic test in `engine/utils` that
replicates the `mraRopeRotationAtMoonbeamScale` reproducer would be able to set
`enableDiagnostics = true` directly, enabling a clean observation of invocation
count, depths, and expansion widths without reflection. This should be done before
implementing any threshold change so the exact invocation profile is known.

**Recommendation:** Implement Option C first (move the reproducer or add a
cross-module diagnostic test in `engine/utils`) to get the actual strategy
invocation profile with diagnostics enabled. Then implement Option B as the primary
fix. Option A alone is insufficient and may cause excessive isolation of cheap
conditionals.
