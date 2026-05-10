# Expansion Width — Making the Per-Node Definition Faithful

## Problem

The `ExpansionWidthTargetOptimization` strategy in
`base/code/src/main/java/io/almostrealism/compute/ExpansionWidthTargetOptimization.java`
does not fire on `mraRopeRotation` at Moonbeam scale despite the strategy
being wired into the `Hardware.java` cascade, the propagation being product-
semantic, thresholds having been tuned repeatedly, and the test
(`RopeCompilationRegressionTest.mraRopeRotationAtMoonbeamScale`) reliably
producing a 44 MB single-line kernel expression that OOM-kills `cc1`.

The reason the strategy does not fire is **not** a threshold problem, **not**
a depth-gate problem, and **not** a propagation-direction problem. It is that
`Process.getExpansionWidth()` is **only overridden on three classes**
(`PackedCollectionPad`, `GreaterThanCollection`, `LessThanCollection`, all
returning `2`), and every other Process defaults to `1`. That is an
under-measurement, not a miscalibration.

Concretely, the Process nodes built by `mraRopeRotation` that contribute
real emission fan-out but currently report `getExpansionWidth() == 1`
include:

| Process | What its emission actually does | Correct local width |
|---|---|---|
| `Sum` with N operands | emits `c₁ + c₂ + … + c_N` — each child inlined in full | **N** |
| `Product` / multi-argument multiply | emits `c₁ · c₂ · …` — each child inlined | **K** (operand count) |
| matmul / weighted-sum with inner-dim `K` | unrolls to `Σᵢ₌₀..K-1 A[i]·B[i]` | **K** |
| N-way concat (built as add-of-pads) | the add emits each pad's subtree | **N** |
| Generic reductions | K-wide unroll | **K** |

The fan-out factors that drive `mraRopeRotation`'s kernel size — the
6-way group sum, the 2-way output-interleave conditional, the 2-way
multiply-subtract forming `out1`/`out2`, the 2-way concat inside
`freqCis`, the reduction inside the matmul on top of `freqCis`, the
per-gather inlining of lazy freqCis's entire subgraph — are almost
entirely invisible to the current metric. Only two of those factors
(pad=2 inside the concat, greaterThan=2 at the output interleave) are
declared. The rest multiply silently.

A faithful `getExpansionWidth()` would put `mraRopeRotation`'s subtree
width in at least the tens (structural factors `2·2·6·2 ≈ 48`) before
the lazy-freqCis subgraph is inlined, and into the thousands once
inlining is accounted for. At that point, a threshold "WAY up" (hundreds
or thousands) is defensible: trivial `a + b` patterns sit at 2, and only
genuinely amplifying compositions cross the bar.

## Conversation record (the story the user asked for)

This is a record of how this conversation arrived at the above
conclusion, kept so the next session doesn't start over from
scratch. The pattern — user asks for the generalisation, agent does
something narrower, user asks again, agent does something adjacent —
repeats enough times that writing it down is cheaper than re-deriving
it.

1. **User asks for a new optimization strategy.** The user frames the
   RoPE compile explosion as something the framework's isolation
   machinery should catch, and specifies that the right fix is a
   new `ProcessOptimizationStrategy`, not a forced-isolation
   sledgehammer in `RotationFeatures`.

2. **Agent builds the narrow strategy.** Agent introduces
   `Process.getExpansionWidth()` (default 1), overrides `2` on
   `PackedCollectionPad`, `GreaterThanCollection`, `LessThanCollection`,
   adds `ExpansionWidthTargetOptimization`, wires it into the cascade.
   Unit tests verify the three overrides and the context propagator.

3. **User asks whether `AggregationDepthTargetOptimization` is wired
   up, and asks to combine aggregation-count and expansion-width into
   a single general notion** — explicitly stating that the width of a
   conditional and the width of an aggregation are the same concept,
   and that a faithful measure should cover both. Agent implements
   expansion-width side of that but does *not* add aggregation-width
   contributions to matmul / reductions / pointwise fan-outs.

4. **User pushes on `p[x]` case analysis.** Agent identifies that
   concat + non-linear index is the amplifier. User guides agent to
   realise the issue is analogous to aggregation. Agent fails to
   generalise the ew override beyond the two cases already done.

5. **Composed Q-projection + RoPE test.** User asks for a
   representative reproducer. Agent writes
   `mraRopeRotationAfterQProjectionAtMoonbeamScale`. Agent
   misdiagnoses the result (thinks Q-proj leaks into RoPE's kernel;
   it doesn't — matmul is cell-isolated cleanly). Threshold discussion
   continues based on the mis-reading.

6. **User: "either you have large expansion width or you are measuring
   wrong."** Direct diagnostic. Agent concedes the measurement is wrong
   but frames the fix as "emit the expression and count nodes, or
   recursive per-type estimate" — both of which are the right direction
   but neither is implemented.

7. **User: "define RoPE."** Agent gives the natural O(1)-per-output
   definition.

8. **User: "and we have defined it in some alternate way?"** Agent
   walks through `mraRopeRotation`'s actual decomposition — the
   `greaterThan` interleave, the 6-way group sum, the lazy freqCis
   gather, the concat, the internal matmul — showing the
   structural factor of ~48 before lazy inlining. Still no code change.

9. **User: "and why don't we have a faithful getExpansionWidth?"**
   Agent finally gives the straight answer: only pad/greaterThan/
   lessThan override it, every other Process defaults to 1 including
   Sum, Product, matmul/reductions, N-way concat. That under-measurement
   is the bug. Every threshold/propagation discussion since the
   original strategy landed was displacement.

10. **User: "implement it, and write this record down first because
    you're going to fail again."** This document.

The failure mode in steps 2–9 was consistent: whenever the user asked
for the generalisation, agent reached for whichever sub-problem looked
tractable in isolation (threshold tuning, propagation direction, a
composed test, re-describing RoPE) rather than the core change — adding
`getExpansionWidth()` overrides to every Process whose emission fans
out over its children. The correct answer was visible from step 3
onwards and was restated, with increasing specificity, by the user at
every step.

## Plan (this is what needs to be done)

### 1. Define `getExpansionWidth()` consistently across Process types

Baseline rule: **`getExpansionWidth(n) = k` where `k` is the number of
children whose full emission appears, verbatim, in `n`'s emitted
expression, weighted by any per-child multiplier `n` applies.**

Per-class targets:

| Class | Override returns |
|---|---|
| `NAryExpression` subclasses where the emission is a pointwise reduction over operands: `Sum`, `Product`, `Min`, `Max` | `getChildren().size()` |
| `CollectionSumComputation`, `CollectionProductComputation`, `CollectionMinusComputation` (and any other pointwise multi-operand CollectionProducer) | `getChildren().size()` |
| `WeightedSumComputation`, `LoopedWeightedSumComputation` | reduction inner-dim (group size) |
| Matmul-backing ops where the reduction isn't isolated via `isIsolationTarget` already | inner-dim |
| `AggregatedProducerComputation` and peers | aggregation count |
| `CollectionConcatenateComputation` if present, otherwise add's of pads already get covered by the Sum override | piece count |
| `ConditionalExpressionBase`, `Mask`, `Choice` | branch count (2 for binary, k for k-way) |
| `Conditional` expression itself (emits both branches) | `2` — leave the existing `Pad`/`GreaterThan`/`LessThan` overrides as-is |
| `ReshapeProducer` | delegate to wrapped child — its emission passes through the child's emission unchanged |
| Everything else | default `1` (pointwise element-wise ops with a single effective child) |

The per-class overrides are the load-bearing part. Get those right and
the propagator just has to multiply.

### 2. Context propagation

Keep the existing `ParallelProcessContext.expansionWidth` with
`enableProductExpansionWidth = true` (product accumulation). The
propagator already does `accumulated = parent_accumulated * child_own`,
which is correct once the per-node values are correct. No change
needed to propagation logic.

### 3. Thresholds

With corrected per-node values, rebaseline:

- `EXPANSION_THRESHOLD` — set to something genuinely conservative
  that reflects "has compounded fan-out beyond a few trivial
  operations." Initial target: `EXPANSION_THRESHOLD = 64` — matches
  the existing `AggregationDepthTargetOptimization` default and
  corresponds to "the subtree has at least one level that fans out
  beyond ~6 operands stacked on ~10 trivial compositions."
- `PARALLELISM_THRESHOLD` — leave at `16` (keeps the small-kernel
  safety floor).
- `limit` (depth) — leave at `3`.

After the strategy fires on the mraRope reproducer, step threshold up
until it just barely misses the fired target, then halve back. If it
still over-isolates simple audio-filter tests, that's signal that one
of the per-class overrides is too aggressive (likely candidate: Sum's
`getChildren().size()` when used in 2-operand audio-add chains).

### 4. Tests

- Update `ExpansionWidthTests` with cases for the new overrides:
  `Sum(a, b, c)` → `ew == 3`; `Product(…)` → `ew == operandCount`;
  matmul with inner-dim `K` → `ew == K`. Keep the existing pad /
  greaterThan / lessThan cases.
- Run `mraRopeRotationAtMoonbeamScale` and `mraRopeRotationAfterQProjection*`
  as the calibration target. Both must pass.
- Run `engine/utils` and `engine/audio` filter tests as the
  over-isolation floor. Nothing that was passing should start failing.

### 5. Rollback plan

If the broader sweep over-isolates, narrow the override list: start
with just `WeightedSumComputation` + whatever covers matmul + the
group-sum variant. Those are the specific contributions
`mraRopeRotation` needs. The full Sum/Product generalisation can be
gated by the `enableProductExpansionWidth` flag or a second flag
(`enableFanoutExpansionWidth`) until validated.

## Acceptance criteria

- `getExpansionWidth()` values, spot-checked by the unit tests above,
  are faithful per-node widths for the listed classes.
- `ExpansionWidthTargetOptimization` fires, during
  `mraRopeRotationAtMoonbeamScale.compile(false)`, on at least one
  ancestor of the lazy-`freqCis` subtree, with the strategy's listener
  reporting a fire-site that isolates the subtree.
- Generated `GeneratedOperationN.c` for the RoPE kernel no longer
  contains any line over (say) 500 KB — concrete kernel-size assertion.
- `RopeCompilationRegressionTest.mraRopeRotationAtMoonbeamScale`
  passes within its 60 s `@Test` timeout.
- Full `engine/utils` and `engine/audio` filter test suites show no
  regressions.

## Status

Not yet implemented. This document is the record that the correct
plan exists and has been understood. The implementation attempt
follows this commit.
