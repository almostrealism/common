# Process Optimization Pipeline

## Overview

This document explains how process trees are optimized before compilation. The
optimization pipeline analyzes parallelism and memory usage to decide which child
processes should be **inlined** (embedded in the parent's kernel) and which should be
**isolated** (compiled as separate kernels).

For how process trees are constructed, see
[computation-graph-to-process-tree.md](computation-graph-to-process-tree.md).
For how optimized trees are compiled to native code, see
[backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md).

## Why Optimization Matters

Without optimization, a computation graph compiles naively — every producer's expression
tree is inlined into a single kernel. This causes problems at scale:

- **Expression explosion** — A parent with parallelism 4096 containing a child with
  parallelism 64 replicates the child's expression tree 64 times in the generated kernel
- **Register pressure** — Large inlined trees exceed GPU register limits
- **Memory bandwidth waste** — Computations that could share results instead recompute them

The optimization pipeline solves this by selectively **isolating** child processes into
separate kernels, converting expression inlining into buffer reads.

## The optimize() Pipeline

Optimization is recursive. When `optimize()` is called on a `ParallelProcess`:

```
ParallelProcess.optimize(ProcessContext ctx)
│
├── 1. Create ParallelProcessContext
│      Captures current parallelism, aggregation count, depth
│
├── 2. Recursively optimize each child
│      child.optimize(childContext)
│
├── 3. Check explicit isolation targets
│      Process.explicitIsolationTargets (for debugging/testing)
│
└── 4. Delegate to optimization strategy
       strategy.optimize(ctx, parent, children, childProcessor)
       → Returns restructured tree (or null for no change)
```

### ProcessContext — Optimization State

`ProcessContext` carries optimization state through the tree traversal:

```java
// base/relation/src/.../compute/ProcessContext.java
public interface ProcessContext {
    int getTreeDepth();                            // Current depth in tree
    ProcessOptimizationStrategy getOptimizationStrategy();  // Active strategy
}
```

**`ProcessContextBase`** provides the default implementation with configurable strategy.
The default strategy is set in a static initializer:

```java
// Default strategy chain
CascadingOptimizationStrategy:
  1. ReplicationMismatchOptimization   (specific case — handles replication)
  2. ParallelismTargetOptimization     (general fallback — scoring-based)
```

### ParallelProcessContext — Extended State

`ParallelProcessContext` (`base/relation/src/.../compute/ParallelProcessContext.java`)
extends `ProcessContext` with parallelism-specific state:

- **`parallelism`** — The parent's parallelism count
- **`aggregationCount`** — How many inputs are aggregated per output
- **`fixedCount`** — Whether parallelism is statically known
- **`variableCount`** — Whether parallelism varies at runtime

This context is passed to each child during recursive optimization, allowing children
to make decisions based on their parent's parallelism.

## Optimization Strategies

### ProcessOptimizationStrategy Interface

```java
// base/relation/src/.../compute/ProcessOptimizationStrategy.java
public interface ProcessOptimizationStrategy {
    <P extends Process<?, ?>, T> Process<P, T> optimize(
        ProcessContext ctx,
        Process<P, T> parent,
        Collection<P> children,
        Function<Collection<P>, Stream<P>> childProcessor
    );
}
```

**Return value semantics:**
- **Non-null** — The strategy made a decision; use the returned tree
- **`null`** — The strategy deferred; try the next strategy in the cascade

### CascadingOptimizationStrategy

Chains multiple strategies. The first strategy to return a non-null result wins.

```java
// base/relation/src/.../compute/CascadingOptimizationStrategy.java
new CascadingOptimizationStrategy(
    new ReplicationMismatchOptimization(),   // Try specific case first
    new ParallelismTargetOptimization()      // Fall back to general scoring
);
```

**Design rationale:** Specific strategies detect known problematic patterns efficiently.
The general strategy handles everything else via scoring. This avoids the general
strategy making suboptimal decisions for cases the specific strategies handle better.

### ReplicationMismatchOptimization

**Problem solved:** When a parent has parallelism N and a child has parallelism M where
M is much smaller than N, the child's expression tree is replicated N/M times in the
generated kernel. This wastes registers and instruction cache.

**Example:** A `MultiOrderFilter` with 4096 parallelism containing 41-tap filter
coefficients (parallelism 41). Without isolation, the sin/cos computations for
coefficients are replicated 4096 times in the kernel.

```java
// base/relation/src/.../compute/ReplicationMismatchOptimization.java
```

**Decision logic:**
1. Compute replication ratio: `parentParallelism / childParallelism`
2. If ratio >= `replicationThreshold` (default: 8x), the child is a mismatch candidate
3. Selectively isolate mismatched children into separate kernels
4. Respect `Process.isolationPermitted()` — some processes cannot be isolated

**Returns:**
- **Non-null** — At least one child was isolated; returns restructured tree
- **`null`** — No mismatches found; defers to the next strategy

### ParallelismTargetOptimization

The general-purpose strategy that uses a **scoring function** to decide whether to
isolate children.

```java
// base/relation/src/.../compute/ParallelismTargetOptimization.java
```

**Decision flow:**

```
Is there only one child with matching count?
  → YES: No isolation (nothing to gain)

Is context parallelism >= max child parallelism? (if enableContextualCount)
  → YES: No isolation (parent already handles it)

Is max child parallelism > maxCount (2^20)?
  → YES: No isolation (avoid fragmentation at very high parallelism)

Is max child parallelism > targetCount (2^17) with sufficient context? (if enableNarrowMax)
  → YES: No isolation

Is alternative score worse than current score?
  → YES: No isolation (isolation would make things worse)

Is current score at least 4x worse than alternative? (unless explicit targets)
  → NO: No isolation (marginal improvement not worth the overhead)
```

**Scoring formula:**

```
score = parallelismValue(count) - memoryCost(size)

parallelismValue(count) = 1 + 4096 × log₂(count)
    Logarithmic — diminishing returns on parallelism

memoryCost(size) = size^1.5 / 4096
    Super-linear — cache pressure grows faster than linearly
```

**Intuition:** More parallelism is good (GPU utilization), but larger memory footprint
is bad (cache pressure, bandwidth). The scoring function balances these two factors.

**Configurable thresholds** (static fields on `ParallelismSettings`):

| Threshold | Default | Purpose |
|-----------|---------|---------|
| `minCount` | 256 (2^8) | Below this, isolation rarely helps |
| `targetCount` | 131072 (2^17) | Target for narrowing decisions |
| `maxCount` | 1048576 (2^20) | Above this, avoid fragmentation |
| `enableNarrowMax` | `true` | Enable target-based narrowing |
| `enableContextualCount` | `false` | Enable context-aware count comparison |

### ParallelismSettings — Scoring Utilities

`ParallelismSettings` (`base/relation/src/.../compute/ParallelismSettings.java`) provides
the scoring functions used by optimization strategies:

```java
// Logarithmic value — diminishing returns
static double parallelismValue(long count)  // 1 + 4096 * log2(count)

// Super-linear cost — cache pressure
static double memoryCost(long size)          // size^1.5 / 4096

// Combined score
static double score(long parallelism, long size)

// Batch scoring for analysis
static DoubleStream scores(Stream<Process<?, ?>> processes)
```

## Optimization in Action

### Example: Matrix Multiplication Layer

```
Before optimization:
OperationList (parallelism: 4096)
├── matmul (parallelism: 4096, output: 4096 floats)
│   ├── weights (parallelism: 1, output: 4M floats)    ← mismatch!
│   └── input (parallelism: 4096, output: 4096 floats)
└── bias_add (parallelism: 4096, output: 4096 floats)

Step 1 — ReplicationMismatchOptimization:
  weights has parallelism 1, parent has 4096
  Ratio = 4096/1 = 4096x > threshold (8x)
  → Isolate weights into separate kernel

After ReplicationMismatchOptimization:
OperationList (parallelism: 4096)
├── [isolated] weights → buffer_0
├── matmul (parallelism: 4096)
│   ├── buffer_0 (reads from isolated weights)
│   └── input (parallelism: 4096)
└── bias_add (parallelism: 4096)
```

### Example: No Optimization Needed

```
OperationList (parallelism: 4096)
├── elementwise_add (parallelism: 4096)
├── elementwise_mul (parallelism: 4096)
└── elementwise_relu (parallelism: 4096)

All children have matching parallelism.
ReplicationMismatchOptimization → null (no mismatches)
ParallelismTargetOptimization → no isolation (single matching count)
Result: All operations inline into one kernel.
```

## Explicit Isolation Targets

For debugging and testing, `Process.explicitIsolationTargets` allows fine-grained control
over which processes are isolated:

```java
// Force isolation of specific processes
Process.explicitIsolationTargets = List.of(
    p -> p instanceof WeightComputation,
    p -> p.getOutputSize() > 1_000_000
);
```

These predicates are checked during `ParallelProcess.optimize()` before the strategy
is consulted. They override the strategy's decision.

## When Optimization Is Required vs. Optional

**Required:**
- Before calling `Process.get()` on any non-trivial process tree
- When process trees contain children with different parallelism counts
- When total expression tree size would exceed backend limits

**Optional but recommended:**
- Simple element-wise operations with uniform parallelism
- Single-operation process trees with no children

**Always call `Process.optimize()` before `Process.get()`.** The `OperationList.get()`
method handles this internally, but if you are working with raw `Process` trees, you
must call `optimize()` explicitly.

## Common Pitfalls

**Do not skip optimization to "save time."** Unoptimized trees produce correct but
potentially very slow code. A 4096x replication of a trigonometric expression can turn
a 10μs kernel into a 100ms kernel.

**Do not manually isolate processes.** Let the optimization strategies decide. Manual
isolation bypasses the scoring function and can produce worse results than no isolation
at all.

**Do not modify thresholds without benchmarking.** The default thresholds are tuned for
typical workloads. Changing them affects all process trees globally.

## Related Files

- `ProcessOptimizationStrategy.java` (`base/relation/src/.../compute/`) — Strategy interface
- `ParallelismTargetOptimization.java` (`base/relation/src/.../compute/`) — Scoring-based strategy
- `ReplicationMismatchOptimization.java` (`base/relation/src/.../compute/`) — Mismatch detection
- `CascadingOptimizationStrategy.java` (`base/relation/src/.../compute/`) — Strategy chaining
- `ParallelProcessContext.java` (`base/relation/src/.../compute/`) — Context propagation
- `ParallelismSettings.java` (`base/relation/src/.../compute/`) — Scoring functions and thresholds

## See Also

- [computation-graph-to-process-tree.md](computation-graph-to-process-tree.md) — How process trees are built
- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md) — How optimized trees compile to native code
- [kernel-count-propagation.md](kernel-count-propagation.md) — How parallelism counts flow through operations
- [operationlist-optimization-flags.md](operationlist-optimization-flags.md) — OperationList-level compilation flags
