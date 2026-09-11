# Convolution compile time — where it actually goes

> **Resolved on `feature/cl-profile-perf` (2026-09-08).** The investigation below stands
> as written; the levers it ranked were implemented and measured, and the outcome is
> recorded in the *Resolution* section at the end. Read the analysis first, then the
> resolution.

## Summary

`RepeatedDeltaComputationTests.convDeltaSmall` spends **98.6% of its runtime in
kernel-series detection during expression simplification**. The kernel that work
ultimately produces executes in **90 microseconds**.

This is not GPU time, not native compilation, and not a deficiency in the emitted
code. It is JVM-side analysis: for each candidate subexpression, the series
provider evaluates that expression at *every kernel index* — 1,327,104 of them for
this test — materialises the result, and then asks whether the resulting sequence
matches one of three recognised forms. Three quarters of that work finds nothing
and is discarded.

The same mechanism was independently identified for `DiffusionFeaturesTests.upsample`
in an earlier investigation on `feature/lora-gradients`, on the forward path rather
than the gradient path. These are the two tests that fail reliably on the OpenCL
lane, and they share a cause.

## Evidence

From `engine/utils/results/convDeltaSmall.xml`, read with `ar-profile-analyzer`
(the profile is written by the test itself via `initKernelMetrics`):

| | |
|---|---|
| Test total | 172.1s |
| One node, `f_collectionProductComputation_7209` — `multiply (4608, 288)[axis=2\|1327104x1]` | 169.9s (**98.8%**) |
| Of that node, `kernelSeries [...]` stage time | 169.7s (**99.9%**) |
| That node's own compile time | 150ms |
| That node's run time | **90µs** |
| `expressionCacheMatch_*` stage time, all entries combined | under 2ms |

The stage label is `kernelSeries [depth/nodes, found]`, where `found` records
whether a simplification was returned
(`KernelSeriesProvider.getSeries`, base/code/src/main/java/io/almostrealism/kernel/KernelSeriesProvider.java).
Splitting the 25 probes by that flag:

| Outcome | Probes | Time | Share |
|---|---|---|---|
| Found a simplification | 5 | 44.3s | 26% |
| Found nothing | 20 | **125.4s** | **74%** |

The single most expensive probe, `kernelSeries [17/234, true]`, costs 25.6s. The
cheapest that still did real work, `[4/15, true]`, costs 1.7s. Probes over trivial
expressions (`[1/3]`, `[2/5]`, `[3/7]`) cost microseconds, so cost tracks
`index count × node count` closely.

Note also what is *absent*: `expressionCacheMatch_*` entries total under two
milliseconds across the whole node. The expression cache is not the problem here.

## The shape of the problem

`convDeltaSmall` is `n=1, c=8, h=w=6, f=4`, kernel `3×3`
(`RepeatedDeltaComputationTests.convDelta`):

- input `1×8×6×6` = **288**
- output `4×4×4` = **64**
- Jacobian = 64 × 288 = **18,432** — and the final `sum (64, 288, 1)` node confirms
  this is the intended result
- the dominant multiply is `(4608, 288)` = **1,327,104** = 18,432 × **72**

That 72 is `c × s²` = 8 × 9: one element per (output, input-channel, kernel-tap)
triple, before the reduction that sums the taps away. **The delta materialises an
intermediate 72× larger than the Jacobian it reduces to**, and every position in
that intermediate is a kernel index that series detection then enumerates over,
25 times.

`DiffusionFeaturesTests.upsample` is `batch=4, channels=56, 14×14` upsampled to
28×28 — 4 × 56 × 28 × 28 = **175,616** kernel indices, on the forward path with no
gradient involved. Smaller index space, same cost structure.

## The mechanism, in code

1. `Scope`/`Sum.simplify` calls `KernelStructureContext.simplify`, which reaches
   `KernelSeriesProvider.getSeries(Expression)`.
2. `getSeries(Expression, Index)` determines the index length and calls
   `Expression.sequence(index, len, limit)`.
3. `Expression.sequence` (base/code/src/main/java/io/almostrealism/expression/Expression.java)
   evaluates the expression at every index:

   ```java
   seq = ArrayIndexSequence.of(type, IntStream.range(0, Math.toIntExact(len)).parallel()
           .mapToObj(i -> value(new IndexValues().put(index, i))).toArray(Number[]::new));
   ```

   The full sequence is materialised eagerly, as boxed `Number` objects, **before
   any matching is attempted**. There is no sampling and no early abort.
4. `IndexSequence.getExpression(Expression, boolean)` then tries to match the
   sequence against exactly three forms:
   - a constant;
   - a two-valued sequence whose non-zero value occupies a single index or one
     contiguous run, emitted as a `Mask`;
   - an arithmetic progression, optionally with a granularity and a modulus.
5. If none match, `getSeries` returns null and the caller keeps the original
   expression. Every one of those `len` evaluations is discarded.

The matching in step 4 is cheap — the arithmetic check already breaks out of its
loop on the first mismatch. **The expense is entirely in step 3**, which is paid in
full regardless of whether step 4 can possibly succeed.

## The four hypotheses in the brief

**"Is it a `Process::optimize` issue — are our isolation strategies not addressing
the need?"** Partly, and indirectly. Isolation determines how large each compiled
kernel's index space is, and the cost here is linear in that. Splitting the delta
into smaller isolated kernels would shrink every probe proportionally. But
isolation is a lever on the input to the problem rather than the problem: even a
well-isolated kernel pays `indices × nodes` per probe with no bound relating that
product to any budget. I did not establish what `optimize()` currently does with
this graph; see *Not established* below.

**"Is it compilation overhead that should be short-circuited?"** **Yes — this is
the finding.** 98.6% of the test is one analysis pass whose output is a kernel that
runs in 90µs, and 74% of that analysis returns nothing at all. This is the highest
-value target and the rest of this document is mostly about it.

**"Should we have a dedicated Computation for this rather than
`WeightedSumComputation`?"** The premise does not hold for `convDeltaSmall`:
**there is no `WeightedSumComputation` in the hot path.** Searching the profile for
`weighted` returns nothing. The dominant node's children are
`reshape(f_packedCollectionRepeat_7204)` and
`reshape(reshape(f_indexProjectionProducerComputation_7208))` — detail
`projectDelta{->(4608, 288)}` — reduced by `f_aggregatedProducerComputation_7214`.
So the structure is `repeat × projectDelta`, then an aggregated sum. A dedicated
computation is still worth considering, but for a different reason than suspected:
not because `WeightedSumComputation` is slow, but because a purpose-built conv-delta
computation could emit the 18,432-entry Jacobian directly instead of the 1,327,104
-element intermediate, removing the 72× factor from every downstream analysis.

**"Or is `WeightedSumComputation` doing something inefficient?"** Not here. Whatever
it does elsewhere, it is not in this profile, and the emitted kernel for the hot node
is fast. The inefficiency is in analysing the index arithmetic, not in performing it.

## Candidate fixes, in the order I would try them

### 1. Refute cheaply before enumerating (highest value, lowest risk)

All three recognised forms can be *refuted* from a small sample:

- constant — two differing samples refute it;
- two-valued mask — three distinct values refute it;
- arithmetic progression — for a fixed granularity, three points refute it.

Only *confirmation* needs the full sequence. Since 74% of probes are refutations
that currently pay full price, evaluate a bounded prefix or stride sample first (a
few dozen points), run the same form tests over the sample, and only materialise the
full sequence when a form is still plausible. This is a cheap necessary condition in
front of an expensive sufficient one, and it preserves every simplification found
today.

The care needed is in granularity and modulus: `getGranularity()` and `getMod()` are
computed from the full sequence, so a sampled pre-filter must either sample on a
stride compatible with plausible granularities or restrict the pre-filter to the
granularity-1 case and let the rest fall through to the current path.

### 2. Derive the series symbolically instead of empirically

The expressions being probed are index arithmetic — `Sum`, `Product`, `Quotient`,
`Mod`, `Mask` over a kernel index. Whether such an expression is affine in the
index, and its slope and intercept, is derivable structurally in `O(nodes)` rather
than by sampling `O(nodes × indices)` points. An affine analysis would answer the
arithmetic-progression case exactly, and answer it for the *successful* probes too —
which the sampling fix in (1) cannot help, and which still cost 44.3s here.

This is the larger piece of work and the one most likely to need the follow-on
model's resources. It is also the one that would make the cost independent of index
count, which is the property that would keep this from recurring at larger sizes.

### 3. Reuse probe results

`ScopeSettings.enableKernelSeqCache` is **`false`** by default, so the sequence cache
in `Expression.sequence` is inert. `KernelSeriesCache.defaultMaxExpressions` is 16.
Twenty-five probes occur on a single node, and the `[depth/nodes]` shapes recur
across the profile, which suggests some are structurally identical. Worth measuring
how many of the 25 are duplicates before investing — if the answer is "few", this
lever is small.

### 4. Budget the probe on `indices × nodes`

`Expression.sequence` bails only on `len > limit`, where the limit resolves through
`KernelSeriesCache.getSequenceComputationLimit()` to
`ScopeSettings.sequenceComputationLimit` = `ParallelismTargetOptimization.maxCount << 2`
= **4,194,304**. `convDeltaSmall` needs 1,327,104 and `upsample` 175,616, so **the
existing gate does not fire for either test**. The gate is also on length alone,
ignoring expression size, even though the profile shows cost tracking the product.

Lowering `sequenceComputationLimit` is the one-line change available today, and it is
worth running as an experiment to bound the prize — but it is blunt. It would
discard the 5 probes that *did* simplify along with the 20 that did not, and the
generated kernel would be correspondingly worse. Treat it as a measurement, not a
fix. A budget on `len × countNodes()` would at least be the right shape.

### 5. Shrink the expression itself

Removing the 72× intermediate — emitting the sparse conv Jacobian directly rather
than a dense product that is immediately reduced — would shrink every probe by the
same factor and would help the run side too. This is the "dedicated Computation"
idea from the brief, and it is the most invasive. It is also the only lever that
addresses `projectDelta` producing a `(4608, 288)` shape for an 18,432-entry result.

## Not established

- **I did not profile `upsample` myself.** Its mechanism is taken from the earlier
  `feature/lora-gradients` investigation, which found the same
  `Sum.simplify → getSeries → Expression.sequence` hot path by thread dump. The index
  count is arithmetic from the test parameters. A profile should be captured before
  relying on it.
- **I did not determine what `Process.optimize()` does with this graph** — whether
  the delta is isolated at all, and what the isolation boundaries are. That bears
  directly on lever 5 and partly on 1.
- **I did not measure any fix.** Nothing here has a before/after number attached,
  deliberately: see below.
- **Whether the 5 successful probes matter.** They cost 44.3s and produce
  simplifications, but nobody has measured what the kernel looks like without them.
  If they turn out not to affect the emitted code much, the calculus for lever 4
  changes completely.

## A warning about measuring this

The ROCm host these tests fail on cannot support single-run A/B comparison. Measured
on it today, one unchanged configuration produced 289s, 631s and 652s across three
runs of the same test class — a 2.25× spread — because the machine is shared with CI
runners and other agents. A control run that should have reproduced a baseline
overshot it by 76%.

Any performance claim from that host needs repeated, interleaved runs with the
machine's load recorded alongside each number. `mcp__ar-test-runner__start_test_run`
takes a `repetitions` parameter and `get_run_timing` reports the statistics; that is
the right instrument. The profile-based evidence in this document does not have that
problem — stage attribution within a single run is a ratio, not a wall-clock
comparison, and 98.6% of a run is not a measurement artefact.

## Suggested first experiments

1. Capture a profile for `DiffusionFeaturesTests.upsample` the same way
   `convDeltaSmall` does, and confirm the `kernelSeries` share on the forward path.
2. Count how many of the 25 probes on the hot node are structurally identical, to
   size lever 3.
3. Set `ScopeSettings.sequenceComputationLimit` below 1,327,104 and re-run
   `convDeltaSmall`: this bounds the available prize and reveals what the 5
   successful probes were buying, in one cheap run.
4. Prototype lever 1 over the granularity-1 case only, and measure the change in the
   `kernelSeries [*, false]` share — that number is the direct success metric, and it
   is readable from the profile without any wall-clock comparison.

## Resolution

Implemented on `feature/cl-profile-perf`. Three levers landed, in the order they
paid off, and every one of them is generic to kernel compilation rather than
specific to the two tests.

### What changed

**Block evaluation with early abort (levers 1 and 3 of the list above, generalised).**
`Expression.sequence` no longer evaluates the tree once per index through boxed
`value(IndexValues)` calls. `IndexRange` (in `io.almostrealism.sequence`) describes a
block of consecutive index values, and `Expression.values(IndexRange)` evaluates every
node once per block into a primitive `double[]`, with a per-block identity memo so a
sub-expression shared by many paths of the DAG is computed once. Integer nodes compute
in exact `long` arithmetic and refuse to round: a value past 2<sup>53</sup> raises
`IndexRange.InexactValueException` and the caller falls back to point evaluation. The
per-class `sequence()` overrides in `Product`, `Quotient`, `Mod`, `Minus` and
`Comparison` are gone; each class instead overrides `computeValues(IndexRange)`, the
block counterpart of `computeValue(IndexValues)`.

`KernelSeriesMatcher` is now a streaming recogniser. It consumes values block by block
and tracks the viability of each recognised form (constant, single-run mask,
arithmetic progression with granularity and period); the moment none remains, it
stops. `KernelSeriesMatcher.consume` drives the blocks — the first alone, then rounds
of doubling size up to the common pool's parallelism — so a refuted probe costs one
block and a confirmed one is enumerated at full parallelism. `IndexSequence.getExpression`
delegates to the same matcher, so there is one recognition algorithm. The matcher also
accepts a granularity that does not divide the sequence length, which the old code
rejected without reason; every position is verified regardless.

`KernelSeriesProvider` was restructured around this. Recognition is generic and lives
in the interface; storage of a sequence as a kernel-resident lookup table is the
provider's own capability, expressed by `isSeriesStorable` and `referenceSeries`, which
only `KernelSeriesCache` implements. A probe that cannot be stored is abandoned as soon
as it is refuted; one that could be is enumerated once and offered for storage.
`KernelSeriesCache` keys its per-kernel caches by the expression itself (structural
equality) instead of by a rendered string.

**Structural derivation (lever 2).** `Expression.arithmeticSequence(Index, long)`
derives the progression an integer expression follows from its structure alone,
composing exact rules on `ArithmeticIndexSequence` (`plus`, `scaled`, `negated`,
`dividedExactly`, `modExactly`) across sums, products by constants, quotients and
moduli by constants, negations, casts, constants and indices, in time proportional to
the expression rather than to the kernel. `arithmeticTerms` lets a modulus drop the
terms of a sum that are multiples of it and lets a quotient drop a remainder bounded
below a factor of the divisor, which is exactly the mixed-radix shape convolution
index arithmetic takes. The provider tries this first and confirms the result on about
two thousand sampled positions spread over the range before using it; a disagreement
is logged and falls back to enumeration. It never produced one.

**Construction folds (lever 5, at the expression level).** `Quotient.create` now folds
`(a * c) / d` to `a * (c / d)` when `d` divides `c` and to `a / (d / c)` when `c`
divides `d`, for either sign, and drops bounded remainder terms from a sum numerator.
`ArithmeticGenerator.create` returns zero for a zero scale, and its division coarsens
the granularity when the divisor is a multiple of the scale. These shrink the emitted
kernels as well as removing probes.

### Measured outcome

Same host as the investigation above, same tests, profile totals from the tests' own
`OperationProfileNode` files. The pre-change `upsample` figure is a wall-clock run of
the pre-change jars built from `git archive HEAD`, since the profiled variant of that
test did not exist before.

| | Before | After |
|---|---|---|
| `convDeltaSmall`, profile total | 172.1s | 1.15s |
| `convDeltaSmall`, hot node `multiply (4608, 288)` | 169.9s | 0.98s |
| `upsample`, test elapsed | 233.1s | 4.0s |
| `upsample`, profile total | (not captured) | 3.98s |
| `RepeatedDeltaComputationTests` + 28 other codegen classes at depth 2 | (minutes) | 316 tests in 50s |

Of the three levers, block evaluation with early abort carried the bulk (172s to 3.4s
and 233s to 8s), derivation took `upsample` from 8s to 3.7s by making its ~500
successful small probes free, and the construction folds took `convDeltaSmall` from
3.5s to 1.15s by removing its remaining mixed-radix probes at the source.

### What remains, and why it was left

- **Late refutations.** About 0.8s of `upsample` is probes such as
  `((k % 784) / 28) * 28 + (k / 43904) * 43904 + (k % 28)`, which look like a
  progression of period 784 for 43,904 positions before the second term breaks it.
  Early abort cannot fire sooner without a symbolic proof that a sum of incompatible
  progressions is not itself a progression, and that proof is not sound in general
  (the mixed-radix identity `((k % 784) / 28) * 28 + k % 28 == k % 784` is a
  counterexample). Deliberately not attempted.
- **Non-derivable successes.** `convDeltaSmall` still fully enumerates three probes
  (0.14–0.34s each) whose sequences are progressions only because deeper mixed-radix
  digit decompositions cancel. A normal form for such decompositions would derive
  them; it is a research item, not a bug.
- **Where the un-folded `Mod` nodes come from.** `Mod.of` already folds
  `(x * 72 + y) % 72` to `y % 72`, yet such nodes reached the probe. Their
  construction path bypasses the factory (`ArithmeticGenerator.add/multiply/divide`
  build `Sum`, `Product` and `Quotient` through raw constructors, and the factories
  delegate to those methods). Derivation now handles the probe regardless, so the
  origin was not chased further.
- **The 72× intermediate** (`projectDelta` producing `(4608, 288)` for an 18,432-entry
  Jacobian) is untouched; it is now a run-side and memory question rather than a
  compile-time one.

### New tests

`IndexRangeEvaluationTests`, `KernelSeriesMatcherTests` and
`ArithmeticSequenceDerivationTests` in `engine/utils` cover block-versus-point
agreement on random index arithmetic (including across block boundaries and over a
kernel index child), the memoisation of shared nodes, the exact-value fallback, every
recognised form and its refutation, agreement between streamed and materialised
recognition, early abort, that derived progressions agree with evaluation at every
position (295 of 400 random integer expressions are derived), that the provider
derives without enumerating, and the construction folds. `DiffusionFeaturesTests.upsampleProfile`
now writes the forward-path profile the first suggested experiment above asked for.
