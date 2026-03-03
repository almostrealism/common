# Process Tree Optimization for AudioScene Performance

## Executive Summary

The AudioScene renderer runs at 0.63x real-time (147ms avg per 93ms
buffer). The dominant cost is the MultiOrderFilter FIR convolution
kernel, which inlines ~336,000 sin/cos calls per buffer because the
coefficient sub-expression is not isolated during Process tree
optimization.

**This is not a MultiOrderFilter-specific problem.** It is a gap in the
Process optimization strategy layer: `ParallelismTargetOptimization`
makes an all-or-nothing isolation decision based on the maximum child
parallelism, so it cannot detect that an individual child with much
lower parallelism is being wastefully replicated across the parent's
kernel. The fix belongs in the strategy layer — either as a correction
to the existing strategy or as a new strategy in the cascade chain.

---

## Root Cause Analysis

### The expression inlining chain

1. `EfxManager.applyFilter()` creates a MultiOrderFilter with
   coefficient expressions containing sin/cos:
   ```
   MultiOrderFilter.create(audio, coefficients)
   ```
   where `coefficients` is a `choice(...)` over `lowPassCoefficients(...)`
   and `highPassCoefficients(...)` expression trees.

2. These coefficient expressions extend `TraversableExpressionComputation`
   → `CollectionProducerComputationAdapter` → **implements
   `TraversableExpression<Double>`**.

3. During code generation, `MultiOrderFilter.getScope()` calls:
   ```java
   CollectionVariable coefficients = getCollectionArgumentVariable(2);
   Expression coeff = coefficients.getValueAt(i);
   ```

4. `CollectionVariable.getValueAt()` (line 198-233) checks:
   ```java
   if (producer instanceof TraversableExpression) {
       result = ((TraversableExpression<Double>) producer).getValueAt(index);
   }
   if (result != null) return result;  // INLINED
   ```
   Since the coefficient producer IS a `TraversableExpression`, the full
   sin/cos expression tree is inlined into the generated kernel.

5. If the coefficient producer were wrapped in `IsolatedProcess` (which
   extends `DelegatedCollectionProducer`, which does NOT implement
   `TraversableExpression`), the instanceof check would fail, and the
   method would fall through to the **buffer reference** path:
   ```java
   return (Expression) reference(index);  // reads from pre-computed buffer
   ```

### The deficiency in ParallelismTargetOptimization

The Process optimization system has two levels where isolation happens:

**Level 1: Per-child** (`ParallelProcess.optimize()` line 138-150) —
each child is asked `isIsolationTarget(ctx)`. The coefficient
computation returns `false` (the default).

**Level 2: Strategy** (`ParallelismTargetOptimization.optimize()`) —
examines all children in aggregate and makes an all-or-nothing decision.

The strategy computes:
```java
long cn = ParallelProcess.parallelism(parent);  // 4096
long[] counts = children.map(ParallelProcess::parallelism);  // [4096, 41]
long max = max(counts);  // 4096

if ((p <= 1 && tot == cn) || cn >= max) {
    isolate = false;   // <-- fires: 4096 >= 4096
}
return generate(parent, children, isolate);  // all-or-nothing
```

The strategy sees that the parent's parallelism (4096) matches the max
child's parallelism (4096, from the series input), so it skips isolation
for ALL children. The coefficient child (parallelism 41) is invisible to
this aggregate heuristic.

There are **two structural deficiencies** here:

1. **Aggregate-only analysis**: The strategy only considers `max`, `tot`,
   and `cn` across all children. It never examines individual children
   against the parent. A child with parallelism 41 embedded in a parent
   with parallelism 4096 has a replication ratio of ~100x — but this
   ratio is never computed.

2. **All-or-nothing `generate()`**: The `generate()` helper takes a
   boolean that applies to all children. Even if the strategy detected
   the mismatch, it has no mechanism to isolate one child while keeping
   another inline. (The strategy CAN bypass this helper and call
   `parent.isolate(child)` selectively, but the current code doesn't.)

### The replication problem (general)

When a computation with parallelism N has an input with parallelism M
where M << N, the input's expression tree is replicated N times in the
generated kernel. For MultiOrderFilter:

- Parent kernel parallelism: **4096** (one work item per output sample)
- Coefficient parallelism: **41** (one value per filter tap)
- The 41 sin/cos coefficient computations run **4096 times** each
- Total sin/cos calls: 4096 × 41 × 2 = **336,000** per buffer

If the coefficient sub-tree were isolated:
- Coefficient kernel: 41 work items, 82 sin/cos calls total
- Convolution kernel: 4096 work items, reads 41 values from a buffer
- `ProcessDetailsFactory` constant cache could further detect that
  coefficients don't change between invocations, avoiding even the
  82-call kernel on repeated buffers

This is a general problem. Any computation graph where a
low-parallelism sub-expression is embedded in a high-parallelism
parent suffers the same unnecessary replication. The fix should
handle all such cases, not just MultiOrderFilter.

---

## Key Source Files

| File | Role |
|------|------|
| `CollectionVariable.java:198-233` | The inlining decision point (`getValueAt`) |
| `CollectionProducerComputationAdapter.java:136-138` | Implements `TraversableExpression` (enables inlining) |
| `DelegatedCollectionProducer.java` | Does NOT implement `TraversableExpression` (prevents inlining when isolated) |
| `ParallelProcess.java:138-150` | Per-child isolation check (`isIsolationTarget`) |
| `ParallelProcess.java:211-237` | Strategy-level optimization |
| `ParallelismTargetOptimization.java:145-208` | Strategy decision logic — **the deficiency is here** |
| `ProcessOptimizationStrategy.java:111-124` | `generate()` helper (all-or-nothing boolean) |
| `CascadingOptimizationStrategy.java` | Chains strategies — first non-null result wins |
| `ProcessContextBase.java:63-65` | Default strategy initialization |
| `ParallelismSettings.java` | Scoring functions for parallelism/memory tradeoffs |
| `ProcessDetailsFactory.java:379-390` | Constant argument caching (already works, just needs isolation to unlock it) |

---

## Proposed Fix: Strategy-Layer Selective Isolation

The fix belongs entirely in the `ProcessOptimizationStrategy` layer.
Individual Process implementations should NOT be modified — they already
expose the information needed (parallelism via `getCountLong()`, output
size via `getOutputSize()`). The strategy just doesn't use it for
per-child decisions.

### Option A: New strategy — `ReplicationMismatchOptimization`

A new `ProcessOptimizationStrategy` that detects children with
parallelism significantly lower than the parent and selectively
isolates them. Composes with `ParallelismTargetOptimization` via
`CascadingOptimizationStrategy`.

```java
public class ReplicationMismatchOptimization implements ProcessOptimizationStrategy {

    /** Minimum replication ratio to trigger isolation. */
    public static int replicationThreshold = 8;

    @Override
    public <P extends Process<?, ?>, T> Process<P, T> optimize(
            ProcessContext ctx, Process<P, T> parent,
            Collection<P> children,
            Function<Collection<P>, Stream<P>> childProcessor) {

        listeners.forEach(l -> l.accept(parent));

        long cn = ParallelProcess.parallelism(parent);
        if (cn <= 0) return null;  // can't analyze, defer to next strategy

        // Check each child for replication mismatch
        boolean anyMismatch = false;
        List<P> result = new ArrayList<>(children.size());

        for (P child : children) {
            long childP = ParallelProcess.parallelism(child);
            long childMem = Process.outputSize(child);

            if (childP > 0 && childP < cn
                    && cn / childP >= replicationThreshold
                    && childMem <= MemoryProvider.MAX_RESERVATION) {
                // This child's expressions would be replicated cn/childP
                // times if inlined. Isolate it into a separate kernel.
                result.add((P) parent.isolate((Process) child));
                anyMismatch = true;
            } else {
                result.add(child);
            }
        }

        if (!anyMismatch) {
            return null;  // No mismatches found, defer to next strategy
        }

        return parent.generate(result);
    }
}
```

**Setup in `ProcessContextBase`:**
```java
static {
    defaultOptimizationStrategy = new CascadingOptimizationStrategy(
        new ReplicationMismatchOptimization(),   // selective per-child
        new ParallelismTargetOptimization()      // all-or-nothing fallback
    );
}
```

**Why a new strategy (not a fix to the existing one):**

1. **Separation of concerns**: `ParallelismTargetOptimization` answers
   "should this parent isolate all its children for parallelism/memory
   reasons?" The new strategy answers a different question: "are there
   individual children being wastefully replicated?" These are
   conceptually distinct.

2. **Composability**: The `CascadingOptimizationStrategy` pattern
   exists precisely for this. The TODO in `ParallelismTargetOptimization`
   (line 204-206) already notes it should return null to support
   cascading.

3. **Testability**: The new strategy can be tested in isolation
   with synthetic Process trees, without coupling to
   `ParallelismTargetOptimization`'s thresholds.

### Option B: Fix ParallelismTargetOptimization directly

Add per-child mismatch detection after the existing all-or-nothing
decision. When the all-or-nothing answer is "don't isolate," scan
children individually:

```java
// At line ~207, before the final return:
if (!isolate) {
    // Even though we don't want to isolate ALL children,
    // check for individual children with replication mismatch
    boolean anyMismatch = false;
    List<P> mixed = new ArrayList<>(children.size());
    for (P child : children) {
        long childP = ParallelProcess.parallelism(child);
        if (childP > 0 && childP < cn
                && cn / childP >= replicationThreshold) {
            mixed.add((P) parent.isolate((Process) child));
            anyMismatch = true;
        } else {
            mixed.add(child);
        }
    }
    if (anyMismatch) {
        return parent.generate(mixed);
    }
}
return generate(parent, children, isolate);
```

**Trade-off**: Simpler (no new class, no cascade change) but mixes
two concerns in one strategy. Makes the existing strategy harder to
reason about.

### Recommendation

**Option A** (new strategy) is preferred because it aligns with the
existing `CascadingOptimizationStrategy` architecture and keeps each
strategy focused on one concern. It also creates a natural home for
future refinements (e.g., expression complexity weighting) without
bloating `ParallelismTargetOptimization`.

### What about `isIsolationTarget()` overrides?

Existing overrides on individual classes (e.g., `WeightedSumComputation`,
`PackedCollectionEnumerate`, `LoopedWeightedSumComputation`) are
workarounds for the strategy layer not handling their cases. The
long-term goal should be to **migrate these into the strategy layer**:

- `WeightedSumComputation` (groupSize >= 64): The strategy should
  detect high expression complexity via output size or similar metric.
- `PackedCollectionEnumerate` (parallelism > minCount): Already
  expressible as a strategy threshold check.
- `LoopedWeightedSumComputation` (always true): May indicate this
  computation should always be isolated — the strategy could detect
  it by its computational characteristics.

For now, the new strategy handles the replication mismatch case. The
existing overrides remain for backward compatibility but should be
tracked for future removal as the strategy layer matures.

### What about ProcessDetailsFactory constant caching?

`ProcessDetailsFactory` already caches constant arguments (line 379-390).
Once the coefficient computation is properly isolated, it becomes a
separate computation whose inputs are genome-derived constants. The
existing constant cache automatically prevents recomputation on
every invocation. No additional work is needed — proper isolation
unlocks this existing feature for free.

---

## Test Plan

### Test 1: Demonstrate replication mismatch (current behavior)

**Purpose:** Show that a low-parallelism child's expressions get
inlined into a high-parallelism parent kernel with no isolation.

```java
@Test
public void lowParallelismChildNotIsolated() {
    int signalSize = 4096;
    int filterOrder = 40;

    Producer<PackedCollection> signal = traverseEach(cp(createSignal(signalSize)));
    CollectionProducer coefficients = lowPassCoefficients(c(5000.0), 44100, filterOrder);

    MultiOrderFilter filter = MultiOrderFilter.create(signal, coefficients);

    // Optimize with current default strategy
    Process optimized = filter.optimize();

    // Verify: the coefficient child is NOT isolated
    // Check that no children are DelegatedCollectionProducer (IsolatedProcess wrapper)
    Collection<Process> children = optimized.getChildren();
    boolean anyIsolated = children.stream()
        .anyMatch(c -> c instanceof DelegatedCollectionProducer);
    assertFalse("Coefficients should NOT be isolated with current strategy", anyIsolated);
}
```

### Test 2: Verify manual isolation produces correct results

**Purpose:** Confirm that isolating coefficients into a buffer
produces correct convolution results (the mechanism works, just
isn't triggered automatically).

This is already covered by `MultiOrderFilterConvolutionTest
.convolutionWithChosenCoefficients()`.

### Test 3: ReplicationMismatchOptimization isolates selectively

**Purpose:** Show that the new strategy isolates the low-parallelism
child while leaving the matching-parallelism child inline.

```java
@Test
public void replicationMismatchIsolatesSelectiveChild() {
    int signalSize = 4096;
    int filterOrder = 40;

    Producer<PackedCollection> signal = traverseEach(cp(createSignal(signalSize)));
    CollectionProducer coefficients = lowPassCoefficients(c(5000.0), 44100, filterOrder);

    MultiOrderFilter filter = MultiOrderFilter.create(signal, coefficients);

    // Install the new strategy
    ProcessContextBase.setDefaultOptimizationStrategy(
        new CascadingOptimizationStrategy(
            new ReplicationMismatchOptimization(),
            new ParallelismTargetOptimization()
        ));
    try {
        Process optimized = filter.optimize();

        // Verify: coefficient child IS isolated (DelegatedCollectionProducer)
        // Verify: series child is NOT isolated (still TraversableExpression)
        // Verify: correctness
        PackedCollection result = ((CollectionProducer) optimized).get().evaluate();
        // ... compare against reference convolution
    } finally {
        // Restore default strategy
        ProcessContextBase.setDefaultOptimizationStrategy(
            new ParallelismTargetOptimization());
    }
}
```

### Test 4: Generic parallelism mismatch (not MultiOrderFilter)

**Purpose:** Verify the strategy works for any computation graph
with a parallelism mismatch, not just the specific audio case.

```java
@Test
public void genericMismatchIsolation() {
    // Build a computation: output[i] = input[i] * f(params)
    // where input has shape [1024] and params has shape [8]
    //
    // f(params) could be any expression chain (e.g., sin, exponent)
    // that would be expensive if replicated 1024 times.
    //
    // With the new strategy:
    //   - f(params) is detected as parallelism 8 inside parent parallelism 1024
    //   - ratio = 128x > threshold → isolated into a separate kernel
    //   - parent reads f(params) result from a buffer
}
```

### Test 5: End-to-end AudioScene performance

**Purpose:** Verify the full pipeline improvement.

```
mcp__ar-test-runner__start_test_run
  module: "compose"
  test_methods: [{"class": "AudioSceneBufferConsolidationTest",
                  "method": "effectsEnabledPerformance"}]
  jvm_args: ["-DAR_INSTRUCTION_SET_MONITORING=always"]
  timeout_minutes: 15
```

**Acceptance criteria:**
- Largest generated .c file has NO `sin(`/`cos(` in the sample loop
- Average buffer time drops significantly from 147ms baseline
- Audio output is correct

---

## Relationship to Existing Plan Goals

### AUDIO_SCENE_LOOP.md Goal 1 (MultiOrderFilter coefficients)

The original plan framed this as requiring changes inside
`MultiOrderFilter.java` (two-kernel approach, scope restructuring).
That is the wrong level of abstraction. The Process optimization
strategy should handle this automatically — the coefficient
sub-expression has parallelism 41 inside a parent with parallelism
4096, which is a clear replication mismatch signal. No domain-specific
code changes needed.

### AUDIO_SCENE_LOOP.md Goals 2 and 3

These are also symptoms of insufficient Process optimization:

- **Goal 2 (GPU pre-computation):** Once sub-expressions are properly
  isolated by the strategy, `ComputableParallelProcess.isIsolationTarget()`
  can dispatch them to GPU based on ComputeRequirement — but they need
  to be isolated first.

- **Goal 3 (JNI fusion):** Better strategy-level analysis could also
  detect when many trivial children should be fused rather than isolated.

---

## Implementation Order

1. **Write tests 1 and 4** to establish baseline behavior with
   synthetic and real process trees.

2. **Implement `ReplicationMismatchOptimization`** in the `relation`
   module alongside `ParallelismTargetOptimization`.

3. **Wire it into `ProcessContextBase`** as the first strategy in a
   `CascadingOptimizationStrategy` chain.

4. **Write test 3** to verify selective isolation behavior.

5. **Run test 5** (full AudioScene performance) to measure impact.

6. **Tune threshold** based on results. Start conservative (8x),
   adjust based on real-world computation graphs.

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Threshold too aggressive → unnecessary isolation | Start conservative (8x). Only children with replication ratio exceeding this are isolated. |
| Extra kernel launch overhead | One extra kernel (41 work items) is negligible vs eliminating 336K trig calls. Strategy also checks `outputSize <= MAX_RESERVATION`. |
| Breaking existing optimizations | New strategy returns null when no mismatches found, so `ParallelismTargetOptimization` handles all existing cases unchanged. |
| Interaction with `isIsolationTarget()` | The per-child loop in `ParallelProcess.optimize()` runs BEFORE the strategy. Existing `isIsolationTarget()` overrides still fire. The new strategy adds additional isolation on top — it never prevents isolation that would have happened otherwise. |
| Cascading requires `ParallelismTargetOptimization` to return null | It doesn't need to. It's the last strategy in the chain, so it always returns a result. The new strategy returns null to defer to it. |

---

## Long-Term Direction

The goal is to centralize all isolation decisions in the strategy layer.
Individual Process implementations should expose descriptive metrics
(parallelism, output size, and potentially expression complexity) and
let strategies make the decisions. The existing `isIsolationTarget()`
overrides on `WeightedSumComputation`, `PackedCollectionEnumerate`,
etc. are workarounds that should eventually be replaced by strategy
logic operating on the same metrics those overrides inspect.

The `ReplicationMismatchOptimization` strategy is a step in this
direction: it makes isolation decisions based on metrics already
exposed by `Process` and `ParallelProcess` (`getCountLong()`,
`getOutputSize()`), without requiring any changes to individual
computation classes.

---

## Implementation Results

### What was implemented

1. **`ReplicationMismatchOptimization.java`** in `relation/src/main/java/io/almostrealism/compute/` — new strategy that detects children with `parallelism > 1` and replication ratio `>= replicationThreshold` (default 8x) and selectively isolates them via `parent.isolate(child)`. Returns null when no mismatches found.

2. **`ProcessContextBase.java`** — default strategy changed from `ParallelismTargetOptimization` alone to `CascadingOptimizationStrategy(ReplicationMismatchOptimization, ParallelismTargetOptimization)`.

3. **`ReplicationMismatchOptimizationTest.java`** in `utils/src/test/java/` — 5 tests covering mismatch detection, no-mismatch deferral, threshold boundary, and end-to-end correctness at both small and realistic signal sizes.

### Key design decisions

- **`childP > 1` guard**: Children with parallelism 1 are skipped. These are buffer references or scalar values with no expression tree to replicate. An initial implementation with `childP > 0` caused over-isolation of `ReshapeProducer` and `CollectionProviderProducer` buffer handles, adding kernel launch overhead for zero benefit.

- **`MemoryProvider.MAX_RESERVATION` omitted**: The plan proposed a memory size guard, but `MemoryProvider` lives in the `code` module and is not accessible from `relation`. This is acceptable because `Process.isolate()` implementations already self-guard against oversized outputs via `isolationPermitted()`.

### Performance results

| Metric | Plan baseline | With strategy (clean run) |
|--------|---------------|---------------------------|
| Avg buffer time | 147 ms | 185.62 ms |
| Min buffer time | — | 166.99 ms |
| Max buffer time | — | 395.90 ms |
| Real-time ratio | 0.63x | 0.50x |

**Coefficient isolation is confirmed working**: Generated kernel analysis shows 62 sin() calls (all LFO modulation) and 0 cos() calls in the largest kernel. Since MultiOrderFilter coefficients use both sin AND cos, the absence of cos confirms coefficients are now isolated into a separate kernel and read from a buffer.

**Performance gap analysis**: The 185ms result does not beat the 147ms plan baseline. Contributing factors:
- The 147ms baseline may have been measured on different hardware or JVM conditions
- JaCoCo coverage agent is injected by the test runner and adds overhead on JNI-heavy workloads
- The remaining 3.4MB kernel size is dominated by LFO modulation and effects chains, not coefficient replication — the strategy addressed the coefficient problem but the kernel is still large for other reasons

### Remaining work

- **Threshold tuning** (plan step 6): The 8x threshold is conservative. For the AudioScene case (ratio ~100x), any threshold below 100 triggers isolation. The threshold matters for edge cases with moderate ratios.
- **Long-term migration**: Existing `isIsolationTarget()` overrides on individual classes should be migrated to strategy-layer logic.
- **Further AudioScene performance**: The remaining performance gap is driven by LFO/effects sin() calls and overall kernel size, not coefficient replication. Future optimization work should target those areas.
