# Survey: how element-wise operations reconcile operands of different sizes

**Status:** investigation only. No production code is changed by the branch that adds this
document; the only code change alongside it is a set of *characterisation* tests
(`engine/utils/.../OperandSizeBroadcastCharacterizationTests.java`) that pin today's behaviour so
a later change has something concrete to flip.

**Purpose.** Binary element-wise operations (`add`, `multiply`, `max`, and the rest built through
`CollectionFeatures.compute(...)`) today accept two operands whose sizes are not
broadcast-compatible, producing a plausible-looking result instead of failing. This document
describes exactly what rule the code implements, inventories the operations and the production
call sites that depend on it, records what existing tests exercise, and lays out concrete
candidate rules so the owner can choose one consistent with the stated principle:

> Fail fast. If the shape is wrong, the user is making a mistake. The failure should happen
> when the computation is created, or at compile time only if there is no other option, and
> never at run time. Be strict up front and loose after compilation.

Every claim below cites a file and line, a generated kernel listing, or a test result. Inferred
statements are marked **(inferred)**.

---

## 1. Mechanism

### 1.1 The path a binary element-wise operation takes

`add`, `subtract`, `multiply`/`product`, `divide`, `pow`, `min`, `max`, `mod`, and the 4-argument
comparison builders all route through `CollectionFeatures.compute(...)`:

- `compute(String, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>>, ...)`
  — `compute/algebra/src/main/java/org/almostrealism/collect/CollectionFeatures.java:676-698`. The
  output shape of the inner `DefaultTraversableExpressionComputation` is `largestTotalSize(args)`
  (`CollectionFeatures.java:696`).
- `compute(BiFunction<TraversalPolicy, List<Producer>, P>, Function<List<String>,String>, ...)`
  — `CollectionFeatures.java:713-742`. This calls `alignTraversalAxes(args, processor)`
  (`:719`) and then, if the aligned result's leading count does not already equal
  `highestCount(args)`, traverses it to the axis whose count matches (`:727-738`).

`largestTotalSize` and `highestCount` are defined in
`compute/algebra/src/main/java/org/almostrealism/collect/CollectionTraversalFeatures.java`:

- `largestTotalSize` (`:186-202`) returns the shape with the greatest `getTotalSizeLong()` — the
  operand with the most elements wins the output shape.
- `highestCount` (`:233-244`) / `lowestCount` (`:212-223`) return the max/min leading count.
- `outputShape(...)` (`:255-270`) is the standalone mirror of the `:727-738` adjustment.

`alignTraversalAxes` (`CollectionTraversalFeatures.java:173-176`) delegates to
`CollectionTraversalAxisAlignment.align(...)`.

### 1.2 The alignment algorithm — where size reconciliation happens

The algorithm lives on the general class
`base/code/src/main/java/io/almostrealism/collect/TraversalAxisAlignment.java` (method `align`,
`:99-156`); the collection-specific subclass is
`compute/algebra/src/main/java/org/almostrealism/collect/CollectionTraversalAxisAlignment.java`.
`align` runs two passes over the distinct operand shapes:

**First pass — matching traversal axis, equal total size (`TraversalAxisAlignment.java:109-122`).**
For each candidate target shape it asks every operand for a
`compatibleAxis(target, requireIdenticalTotalSize)`
(`base/code/.../TraversalPolicy.java:1496-1506`): an axis at which the operand's per-item size
equals the target's, and — when `requireIdenticalTotalSize` is set — whose total sizes are also
equal. If every operand has such an axis, each is traversed to it (`alignAxis` → `traverse`,
`CollectionTraversalAxisAlignment.java:54-57`) and combined. **No wrapping or tiling happens on
this pass**; it is the equal-total-size case (e.g. `(2,3)` against `(6)`).
`requireIdenticalTotalSize` is `enableStrictSizes || no operand has more than one dimension`
(`:101-102`).

**Second pass — repeat to fill the largest shape (`TraversalAxisAlignment.java:124-153`).** If no
common compatible axis exists, the algorithm takes the shape with the largest total size as the
target and, for each operand:

- aligns it to `matchDepth(target)` — the number of leading dimensions whose lengths already agree
  (`TraversalPolicy.java:1515-1524`); then
- computes `repetitionsToFill(target)` (`TraversalPolicy.java:1537-1552`):
  `target.totalSize / operand.totalSize`, or `0` when `enableDivisibleSizes` is set and the sizes
  are not an exact multiple (`:1542-1544`);
- if that count is `> 1`, repeats the operand with `repeat(count, operand)` (`expand`,
  `CollectionTraversalAxisAlignment.java:68-75`, which calls `SlicingFeatures.repeat`,
  `SlicingFeatures.java:266-273`).

So on the second pass there are three outcomes for an operand smaller than the target:

| `repetitionsToFill` | what happens to the operand | example (runtime-confirmed, §4) |
|---|---|---|
| `== 1` | already the target total size; only traversed | — |
| `> 1` (divisible) | **tiled** by `repeat(...)` to the target size | `(2) → (4)`, `(1,k,1) → (1,k,N)` |
| `== 0` (indivisible, `enableDivisibleSizes`) | **left short**, no expand | `(3) → (4)` |

An operand that is left short — or one that `repeat` tiled but whose per-item addressing still
reaches past its length — is then read **modulo its own total size** at access time.

### 1.3 How the kernel indexes an operand shorter than the output

The modulo read is in `CollectionVariable.getValueAt`
(`base/code/src/main/java/io/almostrealism/collect/CollectionVariable.java:235-254`):

```java
if (getShape().getTotalSize() == 1 && fixedCount) {
    index = e(0);                                              // line 242-243: a scalar reads element 0
} else if (getShape().getSize() != 1 || fixedCount) {
    index = index.toInt().imod(getShape().getTotalSize());    // line 245: WRAP modulo own length
}
```

A size-1 operand (a scalar) always reads element 0 and therefore broadcasts correctly. Any other
operand has its index taken `imod(totalSize)`, so an output index past the operand's length wraps
to the start. `UniformCollectionExpression.getValueAt`
(`base/code/.../UniformCollectionExpression.java:90-94`) hands the *same* output index to every
operand, so the wrap is per-operand and invisible to the operation itself.

### 1.4 Generated kernel for one wrapping case

The characterisation test `profileVectorRowWrap` runs `cp(pack(0.5,1.5,2.0,3.0)).add(cp(pack(10,20,30)))`
— a `(4)` operand added to a `(3)` operand — under an `OperationProfile`, saving
`engine/utils/results/operandSizeWrapVectorRowAdd.xml`. The generated C/JNI kernel body for the
`add` node (read from that profile) is:

```c
// output _v0 (size 4); both operands share one aggregated argument buffer _v1:
//   _v1[0..3]   = the (4) operand   (per-argument offset 0)
//   _v1[4..6]   = the (3) operand   (per-argument offset +4)
for (long long global_id = global_index ; global_id < global_total; global_id += 2) {
    _v0[global_id + _v0Offset] = _v1[(global_id % 3) + _v1Offset + 4] + _v1[global_id + _v1Offset];
}
```

The decisive term is `_v1[(global_id % 3) + _v1Offset + 4]`: the `(3)` operand is read at
`global_id % 3` — exactly the `imod(totalSize = 3)` from §1.3 — and the `+ 4` is the per-argument
offset of that operand inside the shared buffer. `global_total` is 4 (the larger operand's size,
from `largestTotalSize`). This is the kernel-level proof that the smaller operand is reused modulo
its own length.

> **Tooling note.** The task asks for this listing to be read through `ar-profile-analyzer`. In
> this environment that tool is non-functional: `load_profile`/`list_children`/`search_operations`
> return `{"error":"Java CLI failed:"}` for every profile, including ones already committed under
> `engine/utils/results/`. The listing above was therefore read directly from the saved profile
> XML. This was reported as harness feedback.

### 1.5 The axis-selection defect (which axis a vector aligns to)

For a matrix operand, `matchDepth` (§1.2) decides which axis a vector aligns to, and that is
decided purely by which dimension length the vector happens to equal:

- `(2,3)` against a `(3)` vector: `matchDepth((3),(2,3)) = 0` (lengths differ at axis 0), so the
  vector tiles along the *trailing* axis — the same row added to every matrix row.
- `(2,3)` against a `(2)` vector: `matchDepth((2),(2,3)) = 1` (leading lengths agree), so the
  vector aligns to the *leading* axis — a different offset per matrix row.

For a square `(n,n)` operand both a trailing and a leading `(n)` vector would match, and which the
caller gets is decided by coincidence of sizes. This is the second defect under review. **(inferred
from the `matchDepth`/`repetitionsToFill` definitions; the `(2,3)` cases are runtime-confirmed in
§4, the square-matrix ambiguity is not separately exercised.)**

### 1.6 Variable (batch) counts

`expand` only repeats a producer whose count is fixed:
`CollectionTraversalAxisAlignment.expand` (`:68-75`) calls `repeat(...)` only when
`enableVariableRepeat || Countable.isFixedCount(producer)`; `enableVariableRepeat` defaults to
`false` (`:38`). A producer whose count is not known until evaluation is therefore **not** repeated
and is returned unchanged, leaving the size reconciliation to the modulo read at run time. This is
the one place where the current mechanism defers a size decision to run time. **(inferred from the
`expand` guard; not separately exercised at run time in §4.)**

### 1.7 The current rule, stated precisely

Given two operands reaching a `compute(...)`-based element-wise operation, today's code:

1. picks the output shape as the operand of **largest total size**, with its leading count raised
   to the **highest count** across operands (`CollectionFeatures.java:696, 727-738`);
2. if all operands expose a **common compatible traversal axis at equal total size**, traverses
   each to it (no wrap);
3. otherwise, for each smaller operand, **tiles** it with `repeat(...)` when its size divides the
   target, and **leaves it short** when it does not;
4. reads any operand shorter than the output **modulo its own total size**
   (`CollectionVariable.java:245`), except a size-1 scalar, which reads element 0 and broadcasts
   correctly (`:242-243`).

Note that `TraversalPolicy.enableStrictSizes` and `enableDivisibleSizes` are **both already `true`**
by default (`TraversalPolicy.java:63, 66`). They do **not** prevent the wrap: `enableStrictSizes`
only forces `requireIdenticalTotalSize` and lowers `minDepth` in the matching passes, and
`enableDivisibleSizes` only turns an indivisible tile into "leave the operand short" (which is
still read modulo). Neither makes any operation throw on a non-broadcastable pair. **"Strict" today
does not mean "fail fast."**

---

## 2. Inventory of operations

"compute path" means the operation reconciles sizes through §1 (align → tile/wrap → modulo).
File references are in `compute/algebra/src/main/java/org/almostrealism/collect/`.

| Operation | Defined at | Size reconciliation | Behaviour on incompatible non-scalar sizes |
|---|---|---|---|
| `add(a,b)` | `ArithmeticFeatures.java:93-95` → `add(List)` `:130-156` | compute path | tiles/wraps per §1 |
| `add(List)` | `ArithmeticFeatures.java:130-156` | constant-folds if all constant (`:135-141`), else compute path | tiles/wraps per §1 |
| `subtract(a,b)` | `ArithmeticFeatures.java:182-184` = `add(a, minus(b))` | compute path (via `add`) | tiles/wraps per §1 |
| `multiply`/`product` | `ArithmeticFeatures.java:255-257, 342-353` | compute path | tiles/wraps per §1 |
| `divide(a,b)` | `ArithmeticFeatures.java:423-435` | compute path | tiles/wraps per §1 |
| `pow(base,exp)` | `ArithmeticFeatures.java:538-559` | compute path | tiles/wraps per §1 |
| `min`/`max(a,b)` | `ArithmeticFeatures.java:630-652` (`UniformCollectionExpression`) | compute path | tiles/wraps per §1 |
| `mod(a,b)` | `ArithmeticFeatures.java:674-677` | compute path | tiles/wraps per §1 |
| `bound(a,min,max)` | `ArithmeticFeatures.java:689-691` = `min(max(a,c(min)),c(max))` | scalars only (via `min`/`max`) | scalar limits broadcast correctly |
| `equals`/`greaterThan`/`lessThan`/`and` (4-arg) | `ComparisonFeatures.java:60-67, 93-101, 171-179, 248-256` | compute path | tiles/wraps per §1 |
| **`subtractIgnoreZero(a,b)`** | `ArithmeticFeatures.java:211-228` | **hand-rolled; throws** | `traverseEach` the size-1 side; otherwise `throw new IllegalArgumentException("Cannot subtract a collection of size …")` (`:222-223`) — note it compares `getSize()` (per-item), not total size |
| **`greaterThanConditional(a,b,…)`** | `ComparisonFeatures.java:128-144` | **hand-rolled; collapses** | `if (shape(a).getSize() == shape(b).getSize()) shape = shape(a); else shape = shape(1)` (`:133-137`) — a mismatch collapses the result to a single element |

Unary operations (`floor`, `abs`, `rectify`, `sum`, `mean`, aggregating `max`/`indexOfMax`) take a
single operand and do not reconcile two sizes; they are out of scope.

The two **hand-rolled** rows are the only operations that already reconcile sizes differently.
`subtractIgnoreZero` is the one that already fails fast on a mismatch — the precedent closest to
the owner's principle. `greaterThanConditional`'s silent collapse to `shape(1)` is the same
mistake the 2026-08 `min`/`max` fix removed when it moved those onto the compute path, and is a
candidate for the same treatment.

---

## 3. Inventory of reliance (production call sites)

**Key distinction.** A call that passes differently-sized operands *and relies on §1 to reconcile
them* is different from a call that **pre-tiles** an operand with an explicit `repeat(...)` so that
equal-sized operands reach the operation. The latter does **not** depend on the implicit mechanism
at all — the operands are already equal when they arrive, the first/second passes see a trivial
match, and a strict rule would leave the call untouched. The static search below found that *almost
every* differently-shaped call site in the tree is of the pre-tiled kind. §4 confirms at run time
which sites actually enter the reconciliation path.

**How the search was done.** Across all modules' `src/main` (not tests): grep for element-wise
operators (`.add(`, `.subtract(`, `.multiply(`, `.divide(`, `.max(`/`.min(`, `.mod(`, `.pow(`, and
the free `add(...)`/`multiply(...)`), and for `.repeat(` adjacent to them; read the surrounding
code to infer operand shapes; plus the runtime diagnostic of §4. **What it can miss:** operands
whose sizes are only known at evaluation (variable/batch counts, §1.6); mismatches introduced
inside helper methods so the call site looks equal-shaped; and any module whose tests were not run
in §4 (most of them — see §4's coverage statement).

### 3.1 Genuine reliance on the implicit path — reduction broadcast (runtime-confirmed)

| Site | Shapes | Category | Under a strict rule |
|---|---|---|---|
| `domain/graph/.../NormalizationLayerFeatures.java:458` — `in.subtractMean(2).divide(in.variance(2).add(c(eps)).sqrt())` | data `(b, k, N)` ÷ `(b, k, 1)` | trailing size-1 reduction broadcast | survives any rule that keeps size-1-axis broadcast; must change under an "equal-or-scalar only" rule |
| `domain/graph/.../NormalizationLayerFeatures.java:642` — `rows.divide(rows.pow(2.0).mean(2).add(c(epsilon)).sqrt())` (RMSNorm) | `(b, k, N)` ÷ `(b, k, 1)` | trailing size-1 reduction broadcast | as above |

These are the sites §4 observed actually entering the second pass: the smaller operand has an
explicit **trailing dimension of 1** (a per-row mean/variance), and `repetitionsToFill` tiles it to
`N`. This is conventional size-1-axis broadcasting, not an arbitrary-size wrap. Every reconciliation
event observed in `NormTests` + `SoftmaxTests` was of this exact shape family `(1,k,1) → (1,k,N)`
(§4).

### 3.2 Not reliant — operands pre-tiled with explicit `repeat(...)` (equal when they arrive)

These pass an explicit `repeat(...)` so the operand is already the target size; the implicit path
sees a trivial match and a strict rule would not touch them. Representative sites:

| Site | Shapes | Category |
|---|---|---|
| `domain/graph/.../LayerFeatures.java:878` — `y.add(cp(bias).reshape(out).repeat(0, rows))` | `(rows,out)` + tiled `(rows,out)` | legitimate trailing-axis bias, pre-tiled |
| `domain/graph/.../LayerFeatures.java:1091` — `result.add(repeat(rows, p(biases))…)` | `(rows,out)` + tiled `(rows,out)` | legitimate trailing-axis bias, pre-tiled |
| `engine/ml/.../TransformerResamplingFeatures.java:298, 516` | 3D/2D bias, pre-tiled to equal | legitimate trailing-axis bias, pre-tiled |
| `engine/ml/.../AdaptiveLayerNormFeatures.java:121, 141, 218` | per-block/conditioning params, pre-tiled to equal | legitimate leading-axis (batch) broadcast, pre-tiled |
| `engine/ml/.../LearnedTokenFeatures.java:65`, `engine/ml/.../audio/SoftNormBottleneck.java:144-145`, `engine/ml/.../audio/StableAudio3Conditioner.java:111` | per-token / per-sequence params, pre-tiled | legitimate batch broadcast, pre-tiled |

### 3.3 Not reliant — deliberate tiling via explicit `repeat(...)` then arithmetic

The author knew the sizes differed and engineered the tile explicitly; the arithmetic then sees
equal sizes. Representative sites:

| Site | Shapes | Category |
|---|---|---|
| `studio/compose/.../dsl/audio/MultiChannelDspFeatures.java:142-144, 558, 563-564, 580-581` | per-channel `(channels)` → `(channels, signalSize)` | deliberate per-sample tiling |
| `studio/compose/.../arrange/MixdownManagerPdslAdapter.java:704-709` | allpass tap table, `(bins)` → `(bins, tail)` | deliberate outer-product tiling |
| `engine/ml/.../RotationFeatures.java:489-490` | RoPE freqs → `(batch, heads, seq, rotaryDim)` | deliberate tiling across batch/heads |
| `engine/ml/.../AttentionFeatures.java:600` | causal mask `(seqLen)` → `(heads,1,seqLen)` | deliberate tiling across heads |
| `compute/algebra/.../computations/CollectionSumComputation.java:234` | grouped weights `(groups)` → `(groups, size)` | deliberate tiling of group weights |

### 3.4 Apparently accidental reliance

None found by static reading. No production site was observed passing two non-scalar operands of
genuinely unrelated sizes to an element-wise op without either a scalar, a size-1 axis, or an
explicit `repeat(...)`. This is an absence of evidence from a bounded search, not a proof of
absence — see §3's "what it can miss" and §4's coverage statement.

---

## 4. Evidence from tests

To find reliance that static reading misses, a temporary diagnostic was added locally in the
second pass of `TraversalAxisAlignment.align` (logging operand shape, target shape, and repeat
count whenever a **non-scalar** operand is reconciled to a larger output) and bounded selections of
existing test classes were run. **The diagnostic is not part of the committed change.**

**Classes run (engine/utils, a few at a time):**

- `OperandSizeBroadcastCharacterizationTests` (the new characterisation tests) — 7/7 pass. Observed
  reconciliations: `(3)→(4) repeat=0 wrap`; `(2)→(4) repeat=2 tile`; `(3)→(2,3) repeat=2 tile`;
  `(2)→(2,3) repeat=3 tile`. The three scalar cases produced **no** reconciliation event (scalars
  take the `:242-243` path, never the second pass). This is the direct confirmation that only the
  indivisible vector case (`(4)+(3)`) uses the modulo wrap; the divisible cases are `repeat(...)`
  tiles.
- `ClampBroadcastTests` + `LayersTests` — 12 run, **zero** reconciliation events. The clamp
  (`min`/`max`/`bound`) cases are scalar-or-equal, and the dense-layer bias adds are pre-tiled to
  equal size (§3.2), so none enters the second pass.
- `NormTests` + `SoftmaxTests` — 48 run, **many** reconciliation events, **all** of the form
  `(1,k,1) → (1,k,N) tile` (observed `N` ∈ {2,4,8,16,20,25,30,32,5488}; `k` ∈ {4,6}), arising from
  `add`/`subtract`/`divide` (`ArithmeticFeatures.java:143`, `:432`). **No `wrap` (indivisible)
  event occurred.** This is the runtime basis for §3.1: the real, heavy production reliance is
  trailing size-1-axis reduction broadcast, which a conventional broadcasting rule permits.

**Coverage statement — what was NOT exercised.** These runs cover `engine/utils` collection, layer,
norm, and softmax tests only. They do **not** exercise: the audio DSP paths in `studio/compose`
(`MultiChannelDspFeatures`, `MixdownManagerPdslAdapter`); attention/RoPE/resampling paths in
`engine/ml` (which require weights and were not run); `domain/color`, `domain/heredity`,
`domain/physics`, `compute/geometry`, `compute/stats`, `compute/time`; or any variable-count
(§1.6) path. The §3.2/§3.3 sites are classified from static reading, not from a diagnostic hit.
Do **not** read these bounded runs as full coverage — broad verification is the CI pipeline's job.

---

## 5. Candidate rules

All three enforce at **creation time** (when the producer is built), consistent with the stated
principle, and none defers to run time. Each notes how it treats a variable (unknown) count so the
failure is not pushed to evaluation.

### Rule A — equal total size or scalar only; everything else needs an explicit `repeat(...)`

- **Rule.** A binary element-wise operation requires the two operands to have equal total size, or
  one of them to be a scalar (total size 1). Any other pair throws at creation. Callers that want
  tiling or broadcast write an explicit `repeat(...)`/`reshape(...)` first.
- **Rejected where.** In `TraversalAxisAlignment.align` (or at the `compute(...)` boundary): if the
  first pass finds no equal-total-size compatible axis and no operand is scalar, throw instead of
  running the second pass.
- **Variable counts.** A variable-count operand is accepted only against an equal-count or scalar
  operand; the shape contract is checked symbolically at creation from the declared
  `TraversalPolicy`, not the runtime length, so an unknown count never reaches a modulo read.
  **(inferred.)**
- **Call sites that must change.** The §3.1 norm/RMSNorm reductions (2 sites, plus any other
  size-1-axis reduction broadcast across the tree that §4 did not reach) would each need an
  explicit `repeat(...)` on the mean/variance operand. The §3.2/§3.3 sites are unaffected (already
  equal). Estimated code churn: small in the sites found, but **unbounded by this survey** because
  size-1-axis broadcast is idiomatic and the bounded §4 runs only sampled it.
- **Risks.** Strictest and simplest to reason about, but it rejects conventional size-1-axis
  broadcast, which is the single most common legitimate use (every normalization and softmax). It
  pushes `repeat(...)` boilerplate into a lot of numerically-dense code and is the most likely to
  surface latent reliance only when an un-run module is compiled.

### Rule B — conventional trailing-axis broadcast; reject everything else

- **Rule.** Align shapes by trailing axis (NumPy semantics): for each axis from the trailing end,
  the lengths must be equal or one must be 1; a length-1 axis broadcasts. Any pair not
  broadcast-compatible this way throws at creation. No wrap, no largest-total-size tiling of a
  non-size-1 axis.
- **Rejected where.** Replace the second pass of `align` with a trailing-axis compatibility check:
  compute the broadcast shape if every axis is equal-or-1, else throw. The modulo read in
  `CollectionVariable.getValueAt:245` becomes reachable only for a true size-1 axis (where it is
  equivalent to reading element 0 along that axis) and can be tightened accordingly.
- **Variable counts.** A broadcast axis of declared length 1 is the only one allowed to differ, so
  a variable *count* (leading axis) is handled as a normal broadcast dimension decided from the
  declared shape at creation — no run-time branch. **(inferred.)**
- **Call sites that must change.** The §3.1 reductions **survive unchanged** — `(b,k,1)` against
  `(b,k,N)` is exactly a trailing size-1 broadcast, which is what §4 showed them to be. The §3.2/§3.3
  explicit-`repeat` sites survive (equal shapes are trivially broadcast-compatible). What breaks:
  the indivisible wrap (`(4)+(3)`) and any coincidental leading-axis `(2,3)+(2)` case — i.e. the two
  defects this survey is about — now throw. Estimated churn: near zero for legitimate sites,
  precisely because the real reliance is size-1-axis.
- **Risks.** Changes the alignment for the ambiguous `(n,n)+(n)` case (§1.5): leading-axis tiling
  that happens to work today would now require `reshape` to a size-1 trailing axis. This is the
  intended correction but will surface anywhere code leaned on the coincidence. Slightly more
  implementation work than A.

### Rule C — Rule B plus an explicitly named batch-count case

- **Rule.** As Rule B for trailing axes, and additionally allow a declared **batch** mismatch to be
  named explicitly: an operation variant (or an explicit `repeat`/`broadcast` on the leading axis)
  states "this operand is per-item, that one is per-batch." A bare leading-axis mismatch still
  throws; only the named form is accepted.
- **Rejected where.** Same as Rule B for the trailing axes; the leading-axis batch case is routed
  through an explicit, named API (e.g. the existing `AlgebraFeatures.broadcast`/`broadcastSum`,
  `TraversalPolicy.broadcast(axis,length)` at `TraversalPolicy.java:828`) so the intent is in the
  source.
- **Variable counts.** The batch (leading) axis is exactly where variable counts live; making it an
  explicit, named contract means the batch dimension is declared by the caller rather than inferred
  from a size coincidence, which removes the §1.6 run-time-deferred case entirely. **(inferred.)**
- **Call sites that must change.** Same trailing-axis survivors as Rule B. The §3.2 leading-axis
  batch broadcasts (`AdaptiveLayerNormFeatures`, token/conditioning params) already use an explicit
  `repeat(0, batchSize)`, so they map directly onto the named form with little change. New churn is
  limited to any place that relied on an *implicit* leading-axis match (none observed, but §4 did
  not reach the ML/audio modules).
- **Risks.** The most expressive and the most faithful to the "be strict and explicit up front"
  principle, but the largest surface to design and document (a named batch API and its delta rules),
  and it must be reconciled with the existing `AlgebraFeatures.broadcast*` entry points so there is
  one broadcast vocabulary, not two.

---

## 6. Recommendation and open questions

### Recommendation

Adopt **Rule B** (conventional trailing-axis broadcast, reject everything else), and keep Rule C's
named batch API in reserve as a follow-up if a leading-axis batch mismatch ever needs to be implicit.

Reasons:

1. **It matches the owner's principle without breaking the real reliance.** §4 shows that the only
   heavy production use of the implicit path is trailing size-1-axis reduction broadcast
   (`(b,k,1) → (b,k,N)`), which Rule B keeps. The two things it rejects — the indivisible modulo
   wrap and the coincidental leading-axis alignment — are precisely the two defects this survey
   documents. The failure moves to creation time, as required.
2. **It is a well-understood contract.** "Trailing-axis broadcast, size-1 axes broadcast" is the
   convention every numerical library uses; it needs no project-specific explanation and no new
   vocabulary, unlike Rule C.
3. **Rule A is too strict for the code that exists.** It would force `repeat(...)` into every
   normalization and softmax for no correctness gain, and would be the most likely to surface
   latent reliance late (at compile time in an un-run module). Rule B gets the fail-fast behaviour
   where it matters while leaving idiomatic size-1 broadcast alone.

Implementation note: the change is concentrated in the second pass of
`TraversalAxisAlignment.align` and the reconciliation flags on `TraversalPolicy`. The
characterisation tests added with this survey (`OperandSizeBroadcastCharacterizationTests`) pin the
current wrap/tile/ambiguous behaviour so that, when Rule B lands, each asserting case can be flipped
to expect an exception (or, for the size-1 cases, to keep broadcasting). `subtractIgnoreZero`
(`ArithmeticFeatures.java:222-223`) already throws and is the existing precedent to mirror.

### Open questions for the owner

1. **Size-1-axis broadcast — keep it?** Rule B keeps conventional size-1-axis broadcast (what norm
   and softmax use). If the intent is the strictest "equal or scalar only" (Rule A), the norm paths
   must gain explicit `repeat(...)` — confirm which.
2. **Leading-axis / batch broadcast — implicit or named?** Today a `(2,3)+(2)` leading-axis match
   is implicit and ambiguous (§1.5). Rule B rejects it; Rule C would allow it through a named batch
   API. Which should the batch case be?
3. **Variable (unknown) counts (§1.6).** When an operand's count is unknown until evaluation, should
   the shape contract be validated symbolically at creation (recommended, and required to honour
   "never at run time"), accepting that some real-size mismatches can only be caught at evaluation?
   Or should variable-count operands be disallowed from broadcasting entirely?
4. **`greaterThanConditional` (`ComparisonFeatures.java:128-144`).** Its silent collapse to
   `shape(1)` on a mismatch is the same bug the 2026-08 `min`/`max` change removed. Should it move
   onto the chosen rule's path in the same change, or be handled separately?
5. **Coverage.** §4 exercised only a slice of `engine/utils`. Before a strict rule lands, should a
   wider diagnostic sweep (audio DSP, attention, color, heredity, physics, geometry, stats, time)
   run in CI to enumerate every site that enters the reconciliation path, so no latent reliance
   breaks silently at compile time?
