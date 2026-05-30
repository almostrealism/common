# Subset-delta sparsity: convDelta vs rotary

## Problem

`feature/lora-gradients` changed `PackedCollectionSubset.delta`'s direct case
(`d(subset(x))/dx`, input matches target) to emit a
`SubsetProjectionComputation` — an explicit conditional Jacobian
`J[i,j] = (j == source(i) ? 1 : 0)`. This fixed
`RotaryEmbeddingGradientTests#subsetGradientMedium` but caused
`RepeatedDeltaComputationTests.convDeltaMedium` to time out reliably.

## Root cause (profiled via ar-jmx, 2026-05-30)

The convDelta timeout is **runtime kernel execution**, not compile time.
Thread dump: the test thread waits in `evaluate()` while four
`pool-2-thread-*` workers peg 100% CPU for 120s+ inside
`GeneratedOperation0.apply` (native). The generated kernel is small
(41 lines) but has an inner `for (_82_i < 288)` reduction whose body
recomputes the projection's nested modular index math every iteration —
a dense contraction of a mostly-zero Jacobian.

## Why master is fast and lora-gradients is slow

A subset's gradient is a selection: output `i` ← input `source(i)`.

- **Master** returns the direct-case delta as `identity(...)` wrapped in an
  `IndexProjectionProducerComputation`. `IdentityMatrixComputation` is
  recognised via `Algebraic.isIdentity`, so the downstream chain-rule
  contraction (`AggregatedProducerComputation.delta` → enumerate + aggregate)
  **collapses `I·v → v`**. No dense sum. Fast for convDelta.
- **lora-gradients** returns `SubsetProjectionComputation`, explicit
  `Conditional(idx == expected, 1, 0)` *values*. These are opaque to
  `Algebraic.isIdentity`, so the contraction densely sums all `count` (288)
  terms. Slow for convDelta.

## Empirical: the two representations trade failure modes

Tested on current branch state (`experiment/subset-delta-indexproj`,
reverting `PackedCollectionSubset.delta` to master's parent path):

| representation | convDelta (contracted) | rotary (terminal) |
|---|---|---|
| parent identity index-projection (master) | PASS (identity collapses) | TIMEOUT — compile-time, `Sum.value → Quotient.value → Sum.value` symbolic eval of the dense identity |
| `SubsetProjectionComputation` (lora) | TIMEOUT — runtime dense conditional sum | PASS (direct O(output) eval, no identity) |

`subsetGradientTiny` passes either way; only the medium scale exposes it.

So neither representation wins both. The difference is **terminal vs
contracted**, which is not knowable at `delta()` time:
- contracted (convDelta) wants the identity that collapses under matmul;
- terminal (rotary) wants the direct conditional with no dense identity
  intermediate.

## The real fix locus — empirically narrowed (2026-05-30)

The contraction is `sum_j (j == source(i) ? g[j] : 0)`, which equals
`g[source(i)]` — a gather.

A first hypothesis put the collapse in the `Sum.simplify` masked-sum branch
(`Sum.enableGenerateReordering` / `generateReordering` /
`MaskedSumReorderingTests`). **This was tested and ruled out.** A one-shot
diagnostic was added to that branch (log on every all-single-index-masked
sum) and `convDeltaSmall` was run with it enabled: the diagnostic **never
fired**. So the masked-sum / kernel-traversal machinery the earlier branch
work was built around is a *different code path that convDelta never
reaches.*

The dense 288-term work is in the **aggregation / repeated-computation
loop**: `AggregatedProducerComputation extends
TraversableRepeatedProducerComputation` generates a kernel `for` loop whose
per-iteration summand inlines the subset-projection conditional
`Conditional(inputIdx == expectedInputIdx, …)`. The loop accumulates
`sum over localIndex of summand(localIndex)`, and the conditional makes all
but one iteration contribute zero — but the loop still runs all `count`
(288) iterations doing the modular index math each time.

So the collapse must happen at the **repeated-loop level**, not the flat
`Sum` level:
> a repeated/aggregated reduction `sum over localIndex of
> Mask(f(localIndex) == c, value)`, whose guard is satisfiable by exactly one
> `localIndex` and where `c` is invariant in the loop, collapses to the
> summand evaluated at that `localIndex` (a direct gather), eliminating the
> loop.

This fixes **both**:
- convDelta: the aggregation loop collapses to a gather (O(output)).
- rotary: stays the fast terminal `SubsetProjectionComputation` path.

### Scope / risk

The locus is `TraversableRepeatedProducerComputation` /
`AggregatedProducerComputation` (and the `Repeated` scope generation) — core
reduction machinery, not the bounded `PackedCollectionSubset.delta` change
first scoped, and *not* the `Sum`/kernel-traversal masked-sum machinery
(ruled out). It carries correctness risk for the whole reduction/gradient
path and needs careful regression coverage (gradient isolation tests, the
aggregation tests, plus convDelta and rotary).

## The precise gap (2026-05-30, narrowed to one method)

The aggregation reduction machinery **already has** the loop-collapse
optimisation we want: `AggregatedProducerComputation` with
`replaceLoop = true` (set by `CollectionSumComputation`, propagated through
`AggregatedProducerComputation.delta`). In `prepareScope` it calls
`inputArg.uniqueNonZeroOffset(row, ref, index)`; when that returns a unique
offset, `getScope` emits a **single direct read** instead of the `count`-term
loop (lines 346-356). That is exactly the gather collapse.

For convDelta this path **fires but fails**: with
`AggregatedProducerComputation.enableLogging = true`, convDeltaSmall logs

```
Unable to determine unique offset for AggregatedProducerComputation:112 (72 items)
```

(72 = s·s·c = 3·3·8 for convDeltaSmall). So `uniqueNonZeroOffset` returns
`null` for the subset-projection contraction, and it falls back to the dense
loop.

`uniqueNonZeroOffset`'s default (in `TraversableExpression`) builds an
`ExpressionMatrix` of the values and, as a last step, calls
`ExpressionMatrix.uniqueMatchingOffset` with the predicate
`e -> e.doubleValue().orElse(-1.0) != 0.0` — i.e. "is this cell a constant
non-zero?". A diagnostic placed in `uniqueMatchingOffset` **never fired**, so
the `null` is returned **earlier** in the default chain
(`TraversableExpression.uniqueNonZeroOffset`, lines ~169-203): either
`ExpressionMatrix.create(...)` returns null, the
`getValueAt(...) instanceof InstanceReference` guard bails, or
`indices.apply(this::getValueAt)` returns null for the masked target.

### The fix

Make `uniqueNonZeroOffset` recognise a masked cell. The subset-projection
contraction cell is `Mask(localIndex == c(row), value)` — structurally zero
unless the guard holds. Its zero-ness is decided by the **guard**, not by
`doubleValue()` of the (non-constant) masked value. The recognition should:
find, per row, the single `localIndex` for which the mask guard is satisfiable
(solve `localIndex == c(row)` ⇒ offset `c(row)`), and return that as the
unique offset — letting the **existing** `replaceLoop`/`uniqueIndex` collapse
in `AggregatedProducerComputation.getScope` emit the direct gather.

This is the smallest correct change: it reuses the existing collapse vehicle
and only teaches the offset-recognition to see through a single-index mask.
The exact early-return in `TraversableExpression.uniqueNonZeroOffset` that
fires for this case still needs to be pinned (one more instrument cycle), and
the recognition must be validated with a **collapse-on-vs-off differential
test** (same computation, both ways, assert identical output) because a wrong
offset yields silently incorrect gradients.

## Why the loop-collapse can't see the selection: isolation boundary (2026-05-30)

The existing loop-collapse (`AggregatedProducerComputation` +
`uniqueNonZeroOffset`) was instrumented end-to-end on convDeltaSmall. The
delegation chain for the contraction input is:

```
CollectionVariable.uniqueNonZeroOffset
  -> producer = ReshapeProducer (traversable)
       -> ReshapeProducer.inner = IsolatedProcess  (traversable = FALSE)
       -> returns null
  -> null  => "Unable to determine unique offset" => dense loop emitted
```

The contraction input — `(conv.multiply(filter)).delta(target)`, reshaped —
has been wrapped in an **`IsolatedProcess`**. Isolation deliberately breaks
expression embedding ("Only `IsolatedProcess` breaks expression embedding"),
so `uniqueNonZeroOffset` cannot see through it to the masked-selection
structure, returns null, and the loop is not collapsed.

The isolation is **intentional and load-bearing**: it is precisely what
prevents the exponential expression-tree growth that the gradient machinery
exists to avoid (and that `SubsetProjectionComputation`'s own javadoc cites as
its reason for existing). So this is an architectural tension, not a local
bug:

> Isolation prevents expression explosion (needed) but makes the
> subexpression opaque, hiding the selection structure that the gather
> collapse requires.

A correct fix must either (a) recognise and collapse the selection **before**
isolation happens, or (b) let isolation carry a "this is a selection /
unique-non-zero-offset is X" annotation through the boundary so the collapse
can still fire. Both are multi-subsystem changes (delta construction +
isolation + the aggregation collapse) with silent-gradient correctness risk.

### Two independent gaps (both confirmed empirically)

Disabling isolation entirely (temporary gate in `ParallelProcess.optimize`
to skip `isolate(process)`) and re-running convDeltaSmall **still** logs
`Unable to determine unique offset`. So there are two separate blockers, and
**both** must be fixed:

1. **Isolation opacity.** With isolation on, the contraction input is an
   `IsolatedProcess` and `uniqueNonZeroOffset` returns null at the boundary
   (`ReshapeProducer.inner = IsolatedProcess, trav=false`).
2. **Recognition.** Even with isolation *off*, `uniqueNonZeroOffset` still
   returns null for the masked subset projection. The default chain
   (`TraversableExpression.uniqueNonZeroOffset` →
   `ExpressionMatrix.uniqueMatchingOffset`) decides a cell's non-zero-ness with
   the predicate `e -> e.doubleValue().orElse(-1.0) != 0.0`. The masked cell's
   value (`filter·g`, a buffer read) is non-constant, so the predicate reports
   "non-zero" in **every** column and no unique offset is found. The mask's
   **guard** (`col == source(row)`) is what actually decides zero-ness, and it
   is ignored.

Ordering matters: `uniqueNonZeroOffset` runs in `prepareScope`, which is
**after** `optimize()` isolates. So fixing recognition (gap 2) alone does not
help the contracted case — by the time it runs, the input is already opaque.

The complete fix therefore requires:

- **(a) Recognition**: teach `uniqueNonZeroOffset` / `uniqueMatchingOffset` to
  recognise a `Mask(col == c(row), value)` cell and take the unique offset from
  the **guard** (`c(row)`), not from `doubleValue()` of the value. Contained,
  unit-testable in isolation.
- **(b) Pre-isolation collapse**: move the selection collapse so it runs
  **before** the subexpression is isolated — e.g. an `optimize()` override on
  `AggregatedProducerComputation` that, when the (still-visible) reduction is a
  collapsible selection, rewrites itself to a gather
  (`IndexProjectionProducerComputation` reading the input at the unique
  offset) instead of letting the dense child be isolated. This is the harder,
  higher-risk part (it changes the gradient graph; needs a collapse-on-vs-off
  differential test).

### Net assessment

The failure spans four subsystems, peeled in order:
`Sum.simplify` (ruled out) → aggregation loop → `uniqueNonZeroOffset`
recognition → **isolation boundary** (the actual blocker). There is no bounded
change that fixes convDelta without one of:

1. The deep fix above (collapse-before-isolation or selection-through-isolation).
2. A pragmatic gate/revert that trades the medium-scale rotary capability:
   revert `PackedCollectionSubset.delta` to master's identity-projection
   (convDelta returns to master's ~4/5 rate) and reduce
   `RotaryEmbeddingGradientTests#subsetGradientMedium` toward the `*Tiny`
   scale that the identity path handles (subsetGradientTiny passes on the
   parent path; only Medium blows the identity up). This is the only
   *bounded, low-risk* way to get convDelta green now.

## Two implementation shapes for the loop-level collapse (superseded by the above)

### A. Targeted: selection-recognition in `AggregatedProducerComputation.delta` (recommended)

The contracted aggregation is built in `AggregatedProducerComputation.delta`
(lines ~461-478): `delta = input.delta(target)` (the subset projection) is
reshaped/transposed/enumerated and fed as the `delta` input to a new
aggregation that sums `count` terms. If `SubsetProjectionComputation` exposed
itself as a *selection* (one nonzero per row at a computable column
`source(i)`), this delta site could build a **gather** of the upstream
gradient by `source(i)` instead of the enumerate+aggregate. Contained to the
aggregation-delta path + the subset projection; rotary (terminal, no
aggregation) is untouched. Lower blast radius; fixes convDelta.

### B. General: masked-reduction loop collapse in `RepeatedProducerComputation.getScope`

Detect, at scope generation, a reduction whose accumulated body is a
single-index mask `Mask(g(localIndex) == c, value)` with `c` loop-invariant
and `g` affine/invertible, and emit a direct assignment `out = value` at
`localIndex = g⁻¹(c)`, eliminating the loop. More general (any selection
reduction), but touches core reduction scope generation and requires affine
guard inversion — highest blast radius and the most correctness-sensitive.

**Recommendation:** A first (contained, fixes the actual failing case), with B
as a possible later generalisation. Either way, build correctness tests
*first* — compare the collapsed result against the dense reduction across
several subset shapes — before enabling, because an incorrect collapse yields
silently wrong gradients.

## Alternatives considered (and why deferred)

- **New `Algebraic` selection method + contraction wiring**: the contraction
  is `AggregatedProducerComputation.delta`'s enumerate+aggregate, not a clean
  `matmul`, so wiring a `isSelection`/`getSelectionIndex` recognition there is
  as invasive as the Sum collapse and less general.
- **Size-gate the two paths** in `PackedCollectionSubset.delta`: quick and
  contained, but a heuristic on input size; convDelta's direct subset
  (≈18432) is actually *larger* than rotary's (≈4096), so a naive
  `inputSize²` threshold does not cleanly separate them — the distinguishing
  axis is terminal-vs-contracted, not size.
- **Revert + relax rotary test**: fastest; convDelta returns to master rate;
  abandons the medium-scale rotary-subset-gradient capability.

## Status (2026-05-30)

- Root cause and the trade confirmed empirically.
- Chosen direction: build the masked-sum → gather collapse (selection
  recognition) so both cases pass.
- Implementation not yet started; locus is the Sum masked-sum simplification
  / kernel-traversal collapse.
