# Expression DAG Interning — Exploration Plan

## Goal

Decide whether **hash-consing Expression nodes** (turning the Expression tree
into a shared DAG via construction-time interning) is a net win for this
codebase, by building the minimum infrastructure required to *measure* the
tradeoff rather than by committing to the change up front.

Three dimensions matter and must all be evaluated together:

- **Memory** — how many redundant Expression objects exist in a representative
  workload, and how much of the heap they account for.
- **Compute** — does removing the redundancy shrink simplification / compile
  time, and how much does the intern lookup itself cost on every construction?
- **Maintenance complexity** — what fraction of the ~50 Expression subclasses
  need to participate, how invasive is the change to subclass constructors,
  what breaks if a subclass is missed.

This plan is explicitly an *investigation*, not an implementation
commitment. Phase 4 is a decision gate; we may choose to back out and
pursue `Let`/`Bind` or lambda-abstraction instead, or to drop the whole
direction. The benchmarks built in Phase 1 are valuable regardless of
which way Phase 4 goes — they let us evaluate every later proposal in
this design space against a fixed reference workload.

## Background

The `feature/lora-gradients` branch revealed two related issues in
`KernelTraversalOperationGenerator`:

1. The lookup-table cache was keyed by `IdentityHashMap<String, …>` on
   freshly-rendered expression strings, so the cache literally never hit.
2. Even if it had hit, building the cache key cost O(treeSize) per call.

The fix on that branch switched the key to `HashMap<Expression<?>, …>`,
relying on `Expression.equals` (O(1) early reject via cached metrics) and
`Expression.hashCode` (O(1), packed structural hash). Cache hit ratio in
`MaskedSumReorderingTests.reorderingCacheAmortisesRepeatedSimplify` went
from 0.97 to 0.27.

That fix raises a question we deferred: **how often do structurally
equal Expression subtrees coexist in memory?** If the answer is "a lot,"
hash-consing those subtrees should reduce both heap pressure and the
amount of redundant simplification/equality work done across the
compile pipeline. If the answer is "rarely," interning is pure overhead.

We don't currently know the answer, and we don't have the instrumentation
to measure it. Phase 1 builds that instrumentation.

## What we already have

The codebase contains pieces of the deduplication machinery already, and
the plan needs to respect them rather than reinvent:

- `Expression.compare(Expression)` (Expression.java:1874-1890) —
  structural equality. Uses cached `type`, `treeDepth()`, `countNodes()`,
  packed `hash`, then recurses. Short-circuits in O(1) on non-match.
- `Expression.hashCode()` (Expression.java:1932-1947) — packs
  `hash + nodeCount + depth + childCount` into 32 bits. All cached
  fields; no tree walk.
- `Expression.process(e)` (Expression.java:1963-1977) — already calls
  `ExpressionCache.match(e)` at the end. This is the hook many Expression
  factory methods funnel through.
- `ExpressionCache` (`base/code/.../scope/ExpressionCache.java`) — a
  thread-local, explicitly scope-activated (`use(...)`), per-depth
  frequency cache. Only fires for expressions matching
  `ScopeSettings.isExpressionCacheTarget(...)`. Used in exactly two
  places today (one commented out, one in `Expression.process`).
- `FrequencyCache` (under it) — the underlying eviction policy. Bounded
  size, frequency-aware eviction.

`ExpressionCache` is the closest thing to a working dedupe table in
the codebase. It is *not* a hash-cons table: it's opt-in, thread-local,
frequency-filtered, and runs only inside an active scope. Hash-consing
would generalise this to "every construction is automatically deduped,
unconditionally, with a global or per-Computation table."

## Hypothesis

We expect the measurements to land approximately here. The
benchmarks in Phase 1 confirm or refute each.

| dimension | expectation | rationale |
|---|---|---|
| live duplicate ratio | 5-30% of nodes are structurally equal to another live node | chain-rule expansion produces many identical sub-Jacobians; sparse-projection deltas tend to produce repeated mask shapes |
| memory saving from interning | 2-10% of heap held by Expression objects in a model training step | dominated by leaves and shallow shared sub-trees |
| equals/hashCode cost on hot paths | dominated by hash, with `equals` recursion rare due to early reject | already true today per the existing cached metrics |
| compile-time impact at convDelta scale | small improvement (5-20%) once tables are warm | the cache fix on `lora-gradients` already captured most of the easy win |
| intern lookup overhead per construction | O(microseconds) per construction at a hit rate above some break-even | hash is O(1), equals is O(1) for non-match, O(treeSize) only on the rare match-on-hash-collision path |

A negative outcome (e.g. duplicate ratio < 1%, or per-construction overhead
> savings) is fine and informative. We will not chase a win that isn't there.

## Design space (variation points)

Each of these is a knob the prototype can flip independently. The matrix is
large; Phase 2 picks one starting configuration, Phase 3 varies the ones
that matter.

1. **Where the intern hook fires.**
   - At every Expression subclass constructor (~50 subclasses).
   - At `Expression.process(e)` only (current `ExpressionCache` policy).
   - At a new `Expression.intern(this)` static helper called by each
     subclass's factory method.
   - Hybrid: only intern below a complexity threshold (small, common
     subtrees), let large ones stay unique.

2. **Table semantics.**
   - Strong references — table holds the canonical copy forever
     (bounded LRU to avoid leaks).
   - Weak references — `Map<Expression, WeakReference<Expression>>`
     with a `ReferenceQueue` for cleanup. Lets unused expressions
     get collected. More complex lifecycle.
   - Soft references — GC-pressure-driven eviction. Simpler than
     weak but less predictable.

3. **Table scope.**
   - Global (one JVM-wide table). Simpler reasoning, requires
     concurrent map.
   - Per-Computation. Matches how `KernelTraversalOperationGenerator`
     already scopes its cache. Avoids cross-pollination but loses
     sharing across compiles.
   - Thread-local with explicit activation. This is what
     `ExpressionCache` already does.

4. **Granularity.**
   - Intern every node.
   - Intern only subtrees above a node-count threshold.
   - Intern only subtrees whose children are themselves interned
     (bottom-up only). This matters because the equality check is
     cheap iff the children compare by `==` — for a fully-interned
     tree, `compare` becomes a constant-time identity check at each
     level.

5. **Existing `ExpressionCache` integration.**
   - Replace `ExpressionCache` with the intern table.
   - Layer the intern table under `ExpressionCache` (cache still
     does frequency filtering on top).
   - Treat them as independent and benchmark both.

## Plan

### Phase 1 — Instrumentation and baselines

Goal: be able to *answer* the empirical questions before changing any
production code.

#### 1.1 Live-duplicate scanner

Add a debugging utility (in a new file under `base/code/.../scope/`,
e.g. `ExpressionDuplicationScanner`) that, given a set of root
Expressions, walks each and reports:

- Total node count across all roots.
- Distinct-by-`equals` node count.
- Live duplicate ratio.
- A histogram of duplication by tree depth and node class.

Driven by a manual test call site, not wired into anything by default.
Used to characterise specific workloads.

#### 1.2 Reference workloads

The standard reference set used by every later measurement in this plan.
Every workload was hand-checked to confirm it actually exercises
`Process.optimize()` / `OperationList.optimize()` / `CompiledModel.compile()`
— without that step the Expression tree we observe is the raw unoptimised
version and is not representative of what the framework actually compiles
in practice.

| # | test | timeout | shape | optimization path |
|---|---|---|---|---|
| 1 | `RepeatedDeltaComputationTests.convDeltaSmall` | 7 min | pathological sparse-Jacobian | explicit `Process.optimized` |
| 2 | `LayersTests.rmsnorm` | 30 s | per-element norm via `SequentialBlock` | `OperationList.optimize()` (LayersTests.java:114,119) |
| 3 | `RotationTests.rotateHalf` | 30 s | RoPE forward (gather + concat + sin/cos) | `CompiledModel.compile()` |
| 4 | `MultiOrderFilterTest.lowPass` (`optimized=true`) | 60 s | IIR audio filter (recurrence) | explicit `Process.optimized` (MultiOrderFilterTest.java:49) |
| 5 | `DenseLayerTests.denseBatch` | 120 s | dense layer forward+backward, batched | `Model` / `ModelOptimizer` |
| 6 | `AttentionTests.attentionKeys` | 120 s | scaled attention (shared Q/K/V projections) | `CompiledModel.compile(false)` (AttentionTests.java:190) |

Coverage rationale:
- (1) pins the bad end of the cost curve.
- (2) is the cheapest representative — a per-element forward layer with
  weight broadcast. Small Expression trees; baseline noise level.
- (3) is the RoPE forward shape: gather + concat + transcendentals.
  This shape is known to be a compile hotspot
  (see `EXPANSION_WIDTH_OPTIMIZATION.md`) so it's likely to have
  shareable sub-trees if anything does.
- (4) is the only recurrent computation in the set. If interning
  doesn't help here while it helps everywhere else, that's a
  meaningful signal about scope.
- (5) covers dense matmul + backprop. The "normal" gradient case
  (i.e. no sparse-Jacobian pathology).
- (6) covers attention. The three Q/K/V projections share their input
  tensor and have identical Expression shape; this is the case where
  cross-Computation interning is most likely to pay off.

Excluded (with reasoning):
- `MatmulPathTest.smallOutputMatmul` — calls `result.get(); result.evaluate();`
  without `Process.optimize`. The resulting Expression tree is the raw
  pre-optimization form, not what the framework actually compiles.
  Dense-matmul shape is covered transitively by (5) and (6).
- `RotaryEmbeddingGradientTests.ropeFormulaGradientTiny` — exercises
  the optimized pipeline (via `kernelTest`'s default
  `optimized=true`), but its shape overlaps (3) + (5). Add later if
  Phase 3 shows we need a non-pathological gradient with a
  non-dense topology.
- `Llama2InferenceTest` / `Qwen3*` / `MoonbeamMidiTest` etc — multi-minute
  integration tests with weight files. Not Expression-shape representatives.

For each workload, the scanner records:

- Total `Expression` instances ever constructed (instrumented via a
  construction counter).
- Peak live Expression count (snapshot at known points, e.g. just
  after `Process.optimize`).
- Distinct-by-`equals` count at peak.
- Live duplicate ratio = `1 - distinct / live`.
- Histograms of duplication by `Expression` subclass and by `treeDepth()`.

These numbers become the fixed comparison surface — every prototype
variation in Phase 3 reports the same metrics against the same workloads.

#### 1.3 Equality- and hash-cost telemetry

`Expression` already has the `timing` hook (see `Expression.equals`,
`Expression.signature`, `Expression.hashCode`). Run the reference workloads
with timing enabled and dump:

- Total `equals` calls and total time.
- Total `hashCode` calls and total time.
- Total `signature` calls and total time.

This gives us a floor for how much hash-consing could save by collapsing
content equality to identity equality.

#### 1.4 Heap snapshot

Use `ar-jmx` (`mcp__ar-jmx__get_class_histogram`,
`mcp__ar-jmx__analyze_heap_dump`) on a running reference workload to
measure:

- How many Expression instances exist by subclass.
- How much heap they account for.
- (Indirectly) how much of that would be saved by interning.

#### 1.5 Decision metric definitions

Before running Phase 3, lock in what "interning is worth it" means.
Suggested first-pass criteria (revise based on Phase 1 baselines):

- **Memory:** ≥ 5% reduction in peak live Expression nodes.
- **Compile time:** ≥ 5% reduction in `Process.optimize()` wall time
  on each reference workload.
- **Overhead:** intern lookup adds ≤ 10% to total construction time.
- **Maintenance cost:** the change touches ≤ 5 production files outside
  the new intern-table class.

Phase 4 reads these against the Phase 3 data and makes the go/no-go
call.

### Phase 2 — Minimal prototype

Goal: smallest possible intern table that we can A/B against the
no-interning baseline.

Concrete shape:

- New class `ExpressionInternTable` in
  `base/code/.../scope/`. **Do not add to `Expression.java`** — that file
  is already at 2080 lines and over the warning threshold. The intern
  table belongs in its own file alongside `ExpressionCache`.
- One `ConcurrentHashMap<Expression<?>, Expression<?>>` keyed by
  structural equality. Strong references initially (we measure leak risk
  in Phase 3 before going to weak refs).
- One `Expression<?> intern(Expression<?> e)` method.
- A static `ScopeSettings.enableExpressionInterning` flag, default
  `false`. The prototype is opt-in for the duration of the
  investigation.
- Hook the intern call into `Expression.process(e)` (one line change).
  This routes everything that already goes through `process` through
  the table without touching individual subclass constructors. Whether
  this captures enough of the construction surface to be representative
  is itself something Phase 3 measures.

Explicit non-goals for Phase 2:

- Subclass-by-subclass coverage.
- Weak/soft references.
- Multi-table-scope variants.
- Replacing `ExpressionCache`.

### Phase 3 — Measure

For each reference workload, run with `enableExpressionInterning` off
(baseline) and on (prototype). Collect:

- Phase 1.3 telemetry diff (equals/hashCode/signature time).
- Phase 1.4 heap diff.
- `Process.optimize()` wall time diff.
- Per-construction overhead (cumulative time spent in `intern`).
- Live duplicate ratio after the run (Phase 1.1).
- `MaskedSumReorderingTests.reorderingCacheAmortisesRepeatedSimplify`
  warm/cold ratio with interning on.

Write the results into this doc's `## Status` section.

### Phase 4 — Decision gate

Compare against the Phase 1.5 criteria. Possible outcomes:

- **Promote.** Hash-consing meets criteria. Write a follow-up
  implementation plan that covers subclass coverage, weak references,
  and removal of the prototype flag.
- **Refine.** Hash-consing helps in some cases but not others. Plan a
  smarter triggering policy (e.g. depth threshold, per-Computation
  scope) and re-measure.
- **Back out.** Hash-consing doesn't justify its cost. Document the
  findings here and turn attention to `Let/Bind` (Phase 5 of a
  different plan).

## Open empirical questions

These should be answered by Phase 3 data, not by speculation:

- Does the intern table itself dominate cost when most constructions
  are unique?
- Is the dominant duplication intra-Computation (in which case
  per-Computation scope is enough) or cross-Computation (global scope
  needed)?
- Are the duplicated subtrees deep or shallow? Shallow duplication
  argues for an interning-leaves-only policy.
- Does interning interact badly with the `KernelTraversalOperationGenerator`
  cache (which already does its own keying)? The two should compose,
  but the prototype must verify.
- Does the simplifier ever construct an Expression that is later
  mutated? If so, interning breaks. Audit needed.
- For the chain-rule case specifically: do sparse-Jacobian deltas
  produced by `SubsetProjectionComputation` share subtrees in a way
  interning would capture? This is the specific case that motivated the
  whole investigation.

## Risks

- **Mutation.** If any Expression subclass mutates state after
  construction, interning is incorrect. Need a pre-flight audit. The
  existing `init()`-after-construction pattern is suspicious — see
  `Expression.java:235-243` (`protected Expression(..., boolean init, ...)`).
- **Hash collisions at scale.** The packed 32-bit hash has only 16 bits
  of structural-signature entropy, then 10/4/2 bits of metrics.
  Collisions on large workloads aren't pathological (equals still
  short-circuits), but worth checking.
- **Identity assumptions in tests.** Some tests may rely on Expression
  instances being unique. Interning makes `==` true where it wasn't
  before. Find those tests and fix them or document the new invariant.
- **Concurrency.** `ConcurrentHashMap` is fine for the table, but if the
  intern hook runs during a high-throughput construction loop, lock
  contention may show up. Measure under multi-threaded compile.
- **Memory leak from strong references.** Phase 2 uses strong refs for
  simplicity. Without an eviction policy, every distinct Expression
  ever constructed stays live. Phase 3 must measure how badly this
  bites before promoting; weak refs are the obvious mitigation.
- **`ExpressionCache` overlap.** If the intern table is global and
  `ExpressionCache` is thread-local, they may end up doing redundant
  work or fighting over the canonical representative. Phase 3 measures
  whether the two should be merged.
- **Subclass coverage drift.** If new Expression subclasses get added
  later and bypass the intern hook, interning silently misses them.
  Either funnel everything through `Expression.process` or add a
  lint/checkstyle rule.

## Out of scope (separate future plans)

These ideas were discussed in the same session as the impetus for this
plan but are explicitly *not* part of it. Each warrants its own plan
once we have the Phase 1 benchmarks to evaluate them against.

- **`Let`/`Bind` expression form.** Make shared subtrees explicit at
  the expression level so codegen lifts them into local variables.
  Pairs with interning (interning identifies sharing, `Let` makes it
  visible to the renderer).
- **Lambda / pattern abstraction.** Reusable expression templates with
  named parameters. Solves a different problem (structural reuse
  across call sites with different bindings), heavy implementation
  cost.
- **Cross-process or persistent interning.** Sharing canonical
  expressions across JVM invocations. Almost certainly not worth it
  for this codebase.

## Status (2026-05-29) — Phase 2.5 extended interning (KernelIndex + InstanceReference)

The Phase 2 prototype covered only `Constant<?>` subclasses. Phase 2.5
extends `LeafInternTable` to also cover `KernelIndex` and
`InstanceReference`. The investigation surfaced a real
framework-equality dependency that shaped the implementation.

### What the broader test surfaced

A first attempt simply tightened `KernelIndex.compare()` to include
axis + context. Running the broader test set (six representative
classes, 110 tests) surfaced **4 real failures** in
`ExpressionSimplificationTests`:

- `kernelIndexOptions` — `getIndexOptions(kernel())` looks up axis-0
  matches inside an expression by querying with a freshly-built
  no-context `kernel()`. With loose compare it found the
  context-bearing internal index; with strict compare it did not.
- `sequenceMax3` / `sequenceMax4` / `kernelSumMod2` — same pattern
  via `Expression.sequence(...)`: the framework looks up a kernel
  axis ignoring context, and relies on the loose compare.

So the framework's contract is "two `KernelIndex` with the same axis
are interchangeable for lookups; if you need context-specific
behaviour, call simplify." Tightening the compare violated that
contract.

### The fix: dual-table strict secondary key

The compare on `KernelIndex` was reverted. `LeafInternTable` now uses
a strict secondary key for `KernelIndex` only — a small
`KernelIndexKey` inner class with its own equals/hashCode based on
axis + context-by-reference, stored in a second
`ConcurrentHashMap<KernelIndexKey, KernelIndex>`. Expression-level
equality stays exactly as the framework expects; intern lookups are
strict by construction.

`InstanceReference` did not need a compare change — its existing
`compare()` already includes var/pos/index. It is added directly to
the primary table.

### A/B numbers after Phase 2.5

| workload | baseline | Phase 2 (constants) | Phase 2.5 (constants + KernelIndex + InstanceReference) |
|---|---|---|---|
| rmsnorm | total=33 dup=6 ratio=0.182 | total=30 dup=3 ratio=0.100 | **total=30 dup=3 ratio=0.100** (unchanged) |
| attentionKeys | total=44 dup=16 ratio=0.364 | total=41 dup=13 ratio=0.317 | **total=37 dup=9 ratio=0.243** |

On attention, KernelIndex collapsed from 6→1 (loose, 5 dups) to 2→1
(strict, 1 dup) — 4 of the 5 duplicates were context-equivalent and
folded; the remaining 1 represents two genuinely different contexts
that correctly stayed separate. rmsnorm's KernelIndex distinct count
was unchanged (1 → 1) because all its kernel indices already shared a
single context.

`LeafInternTable.size()` after the interned runs: **16 (rmsnorm), 23
(attentionKeys)**. The KernelIndex secondary table holds 1-2 entries
in each, the rest are constants and InstanceReferences.

### Documented limitation — InstanceReference top-level bypass

`InstanceReference` was added to `isInternable` and has 2 structural
duplicates in each workload, but **none of them collapsed**. Cause:
the canonicalisation hook fires in the parent `Expression`
constructor (`children = canonicalize(children)`); leaves only reach
the hook when they are children of another constructed expression.
The remaining duplicate `InstanceReference`s in these workloads are
top-level scope variables / statements — they are not children of
anything we construct, so the hook never sees them.

A future Phase 2.6 could close this gap by hooking the
canonicalisation at the point where statements are added to a scope
or where the compute context dispatches a scope for compilation.
That is out of scope for the current prototype.

### Verification

- **110/110 tests pass** across MaskedSumReorderingTests,
  LeafInternTableTests, ExpressionDuplicationScannerTests,
  ExpressionSimplificationTests, KernelSeriesTests,
  QuotientMultiOperandTest. Both rmsnorm + attention adaptors pass.
- Build validator clean (checkstyle, code_policy, test_timeouts,
  duplicate_code).

### Files changed in this round

- `base/code/src/main/java/io/almostrealism/scope/LeafInternTable.java` —
  added strict secondary table + `KernelIndexKey`; extended
  `isInternable`; updated docs.
- `engine/utils/.../scope/test/LeafInternTableTests.java` —
  added `kernelIndicesAreInternedByStrictKey`, rewrote
  `tableRespectsMaxSizeCap` to test the cap contract directly rather
  than rely on exact `size()` (which now spans two tables), updated
  `findInternableLeaf` to filter on `IntegerConstant` rather than any
  internable.
- `base/code/src/main/java/io/almostrealism/kernel/KernelIndex.java` —
  no net change (an attempted compare tightening was reverted after
  the broader-test surfacing).

---

## Status (2026-05-29) — Phase 2 leaf interning prototype landed

The Phase 2 prototype targets `Constant<?>` subclasses only —
`IntegerConstant`, `DoubleConstant`, `LongConstant`, `BooleanConstant`,
`ConstantValue`. Context-bearing leaves (`KernelIndex`,
`InstanceReference`, `SizeValue`) are deliberately excluded for the
first cut because their {@code compare()} contract does not include
all fields that distinguish a usage site from a different one.

### Implementation

- `base/code/.../scope/LeafInternTable.java` (new) — process-wide
  `ConcurrentHashMap<Expression<?>, Expression<?>>` keyed by structural
  equality. Bounded by `ScopeSettings.maxLeafInternTableSize` (default
  4096); above the cap, lookups still return existing canonicals but
  new entries are not added.
- `base/code/.../scope/ScopeSettings.java` — new flags
  `enableLeafInterning` (default `false`) and `maxLeafInternTableSize`.
- `base/code/.../expression/Expression.java` — one-line hook in the
  children-taking constructor:
  ```java
  this.children = List.of(LeafInternTable.canonicalize(children));
  ```
  When the flag is off this is a no-op early return; when on it
  replaces internable leaves in the children array with their
  canonical instances. Each freshly-allocated leaf still allocates;
  duplicates simply become unreferenced once their parents finish
  construction.
- `engine/utils/.../scope/test/LeafInternTableTests.java` (new) —
  six tests covering: flag-off preserves identity, flag-on collapses
  duplicates, structural equality preserved across the rewrite,
  non-`Constant` leaves are untouched, `DoubleConstant`s intern
  alongside `IntegerConstant`s, the size cap degrades gracefully.

### A/B numbers from real compiles

Both adaptor tests now run baseline (flag off) and interned (flag on)
side-by-side in a single test method. Each run uses a slightly
different shape so the framework's compile cache cannot reuse the
first run's kernels — only concrete dimension constants differ
between the two runs.

| workload | run | shape | total | distinct | dup | ratio | IntegerConstant dup |
|---|---|---|---|---|---|---|---|
| rmsnorm | baseline | SIZE=768 | 33 | 27 | 6 | 0.182 | 3 |
| rmsnorm | interned | SIZE=752 | 30 | 27 | 3 | **0.100** | 0 |
| attentionKeys | baseline | seq=128 | 44 | 28 | 16 | 0.364 | 3 |
| attentionKeys | interned | seq=112 | 41 | 28 | 13 | **0.317** | 0 |

Two invariants hold across both runs and both workloads:

1. **Distinct count is unchanged** (27 → 27, 28 → 28). Identity collapse
   does not collapse structural classes — interning preserves
   semantics.
2. **Every `IntegerConstant` duplicate is eliminated** (3 → 0 in both
   workloads). The prototype does exactly what it claims for the leaf
   types it targets.

`LeafInternTable` size after the interned runs: **6 entries (rmsnorm),
7 entries (attentionKeys)** — well under the 4096 cap, no degradation
behaviour exercised at this scale.

### What's left for attention

The Phase 1 attention numbers showed three big duplicate populations:

- `KernelIndex` 6 → 1 (5 duplicates, ~31% of the total)
- `Product` 7 → 4 (3 duplicates, ~19%)
- `InstanceReference` 7 → 5 (2 duplicates, ~12%)

The Phase 2 prototype does not address any of these — `KernelIndex` and
`InstanceReference` are context-bearing and excluded; `Product` is a
structural node not a leaf. The remaining drop from 0.364 to 0.317 is
exactly what we expected from the leaf-only intervention.

The next natural Phase 2 step is to fix the `compare()` semantics on
`KernelIndex` and `InstanceReference` so they're safely internable,
then re-measure. Structural-subtree interning (Product, Sum, etc.)
should wait until we have a stronger case for it — at present those
duplicates account for a small fraction of the total.

### Files added in this round

- `base/code/src/main/java/io/almostrealism/scope/LeafInternTable.java` (new)
- `engine/utils/src/test/java/io/almostrealism/scope/test/LeafInternTableTests.java` (new)
- `base/code/src/main/java/io/almostrealism/scope/ScopeSettings.java` (two new fields)
- `base/code/src/main/java/io/almostrealism/expression/Expression.java` (one-line constructor hook + one import)
- `engine/utils/.../RmsnormDuplicationProfileTest.java` (extended for baseline/interned A/B)
- `engine/utils/.../AttentionKeysDuplicationProfileTest.java` (same)

---

## Status (2026-05-28) — Phase 1.1 + first 1.2 adaptor (rmsnorm)

- Plan drafted on `feature/expression-dag-intern` (off
  `feature/lora-gradients`).
- Phase 1.2 reference workloads pinned to the 6-test set in §1.2 above.
- Phase 1.1 duplication scanner landed:
  - `base/code/.../scope/ExpressionDuplicationScanner.java` — static walk
    over a set of roots; reports total / distinct / per-class / per-depth
    counts. No instrumentation hooks; safe to call on any in-use graph.
  - `engine/utils/.../scope/test/ExpressionDuplicationScannerTests.java`
    — six self-contained tests; all pass.
  - Notable finding from the synthetic tests: even `Mask.of(...)`
    produces an internal `IntegerConstant(0)` per call (the synthetic
    negative branch of the underlying `Conditional`), so leaf-level
    duplication is observable on trivial inputs. This is exactly the
    kind of cheap-leaf redundancy interning would eliminate.

### Phase 1.2 — snapshot mechanism decision

Three snapshot strategies were considered before any adaptor was built:

| approach | how it works | verdict |
|---|---|---|
| call `OperationList.getScope(ctx)` post-optimize | Walk the optimised Process tree and call `getScope` on each `Computation` leaf | **Rejected.** `getScope` is part of the compile pipeline, not a read-only accessor: it requires `prepareArguments` / `prepareScope` to have run first. Top-level `OperationList.getScope` throws because post-optimise lists contain `ParallelProcess` wrappers, and walking into leaves throws `"multiply is not compiled"`. |
| call `op.get()` to trigger compilation, then walk | The framework's prep step only touches the top-level wrapper; nested sub-Computations are still uncompiled when our walker reaches them. Same `"is not compiled"` failure mode. | **Rejected.** |
| install a static `CompilationTimingListener` on `AbstractComputeContext` | `CompilationTimingListener.recordCompilation(Scope<?>, String, long)` is a default method on the existing profile listener. The framework's compute contexts (CL, Native, Metal) all hand the `Scope` to this listener immediately before code emission. | **Adopted.** Real Expression trees from a real compile, no production-code changes. |

The static listener approach uses the durable entrypoint the profile
system already exposes — the same hook `OperationProfileNode` uses to
capture rendered source text, but with the `Scope`-taking overload that
gives us the structural tree. The listener fires once per compiled
kernel.

### First two adaptors — landed

Both use the same listener-based template: install a
`CompilationTimingListener` on `AbstractComputeContext.compilationTimingListener`,
drive the workload, scan every captured `Scope` with
`ScopeExpressionCollector` + `ExpressionDuplicationScanner`.

**rmsnorm forward**, SIZE=768, cpu path (mirrors `LayersTests.rmsnorm`):

```
captured-scopes=3  expression-roots=10
total=33 distinct=27 dup=6 ratio=0.182

InstanceReference  10 → 8  (dup 2)
IntegerConstant     6 → 3  (dup 3)
DoubleConstant      5 → 5  (dup 0)
Product             3 → 3
KernelIndex         2 → 1  (dup 1)
Sum                 2 → 2
Quotient            2 → 2
...
```

**attention keys**, seq=128 heads=12 head=64 (mirrors
`AttentionTests.attentionKeys`):

```
captured-scopes=2  expression-roots=10
total=44 distinct=28 dup=16 ratio=0.364

IntegerConstant     8 → 5  (dup 3)
Product             7 → 4  (dup 3)
InstanceReference   7 → 5  (dup 2)
KernelIndex         6 → 1  (dup 5)     ← strongest single-class win
SizeValue           3 → 1  (dup 2)
DoubleConstant      2 → 2
Mod                 2 → 2
KernelIndexChild    2 → 1  (dup 1)
...
```

### Interpretation

The captured node counts (33, 44) are small for these workload sizes
because the framework's compile pipeline emits *parametric loop bodies*,
not fully-expanded per-element graphs. The duplication ratios are
therefore **within-kernel-source** redundancy — the metric that maps
directly to "how much smaller would the emitted kernels be under
interning?"

Two consistent signals across both workloads:

1. **Duplication is leaf-dominated.** `IntegerConstant`,
   `InstanceReference`, `KernelIndex`, `SizeValue` together account for
   essentially all the duplicated mass. `Sum`, `Product`, `Quotient`,
   `Mod` — the structural nodes interning could in principle help with
   — are mostly distinct at this snapshot depth. The handful of
   duplicate `Product` nodes on attention are non-trivial but a
   minority.
2. **Attention has ~2× the duplication of rmsnorm**, driven mainly by
   shared kernel-index patterns across the two captured kernels. This
   matches the a-priori expectation that workloads with multiple
   independent reductions over shared data have more cross-kernel
   sharing — but the absolute scale of the win is still modest.

### Phase 1 → Phase 2 conclusion

Two real workloads is enough signal to commit to a Phase 2 prototype
direction without running the remaining four adaptors. The biggest
practical win is exactly the one already hypothesised in discussion:
**intern common leaves — constants, kernel indices, instance
references** — before attempting any structural-subtree interning.
That's a sharper, cheaper Phase 2 than the original "intern everything"
sketch.

The remaining four adaptors (`RotationTests.rotateHalf`,
`MultiOrderFilterTest.lowPass`, `DenseLayerTests.denseBatch`,
`RepeatedDeltaComputationTests.convDeltaSmall`) can be added on demand
to validate the Phase 2 prototype's effect on workload diversity, but
they are not blocking the prototype.

### Files changed in this round

- `base/code/src/main/java/io/almostrealism/scope/ExpressionDuplicationScanner.java` (new)
- `base/code/src/main/java/io/almostrealism/scope/ScopeExpressionCollector.java` (new)
- `engine/utils/src/test/java/io/almostrealism/scope/test/ExpressionDuplicationScannerTests.java` (new)
- `engine/utils/src/test/java/io/almostrealism/scope/test/RmsnormDuplicationProfileTest.java` (new)
- `engine/utils/src/test/java/io/almostrealism/scope/test/AttentionKeysDuplicationProfileTest.java` (new)
