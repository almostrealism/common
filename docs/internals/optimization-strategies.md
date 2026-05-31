# Optimization Strategies

## Overview

The Process optimization system uses pluggable strategies to determine when and how
to restructure process trees. Strategies implement the `ProcessOptimizationStrategy`
interface and can be composed using `CascadingOptimizationStrategy`.

## Strategy Interface

```java
public interface ProcessOptimizationStrategy {
    /**
     * Optimize a process tree.
     *
     * @param ctx Current optimization context
     * @param parent The parent process being optimized
     * @param children The child processes
     * @param childProcessor Function to stream/filter children
     * @return Optimized process, or null if strategy doesn't apply
     */
    <P extends Process<?, ?>, T> Process<P, T> optimize(
        ProcessContext ctx,
        Process<P, T> parent,
        Collection<P> children,
        Function<Collection<P>, Stream<P>> childProcessor);
}
```

**Key Contract:**
- Return optimized process if strategy applies
- Return `null` if strategy doesn't apply (for cascading)
- Use `generate(parent, children, isolateChildren)` helper for consistent results

## Available Strategies

### ParallelismTargetOptimization (Default)

The default strategy based on parallelism thresholds and scoring.

**Location:** `relation/src/main/java/io/almostrealism/compute/ParallelismTargetOptimization.java`

**Configuration:**
```java
ParallelismTargetOptimization.minCount = 256;        // 2^8
ParallelismTargetOptimization.targetCount = 131072;  // 2^17
ParallelismTargetOptimization.maxCount = 1048576;    // 2^20
ParallelismTargetOptimization.enableNarrowMax = true;
ParallelismTargetOptimization.enableContextualCount = false;
```

**Decision Logic:**

```
1. If <= 1 child OR total parallelism equals parent's:
   → SKIP ISOLATION (nothing to optimize)

2. If enableContextualCount AND max child <= context parallelism:
   → SKIP ISOLATION (context already handles parallelism)

3. If max child > maxCount (1M):
   → SKIP ISOLATION (avoid fragmentation of large operations)

4. If enableNarrowMax AND max child > targetCount (131K) AND context >= minCount (256):
   → SKIP ISOLATION (context has sufficient parallelism)

5. Calculate scores:
   currentScore = sum(parallelismValue(child) - memoryCost(child))
   isolatedScore = calculated from isolated configuration

6. If isolatedScore < currentScore:
   → SKIP ISOLATION (isolation makes things worse)

7. If isolatedScore > 4x currentScore (and no explicit targets):
   → SKIP ISOLATION (suspicious improvement, likely calculation error)

8. Otherwise:
   → ISOLATE CHILDREN
```

**When It Works Well:**
- Medium-sized operations (256 - 1M parallelism)
- Balanced memory/computation trade-offs
- Mixed parallelism children

**When It Struggles:**
- Very small operations (overhead dominates)
- Very large operations (fragmentation hurts)
- Deep nesting with similar-sized children

### TraversableDepthTargetOptimization

Prevents expression trees from becoming too deep (which causes stack overflow).

**Location:** `code/src/main/java/io/almostrealism/compute/TraversableDepthTargetOptimization.java`

**Configuration:**
```java
// Constructor takes depth limit (default: 8)
new TraversableDepthTargetOptimization(8);
```

**Decision Logic:**
```
1. Calculate max depth of TraversableExpression subtree for each child
2. If any child's depth > limit:
   → ISOLATE CHILDREN
3. Otherwise:
   → Return null (let other strategies decide)
```

**When to Use:**
- Deep expression trees from nested operations
- Operations that compose many TraversableExpressions
- When seeing stack overflow during compilation

**Typical Configuration:**
```java
new CascadingOptimizationStrategy(
    new TraversableDepthTargetOptimization(8),  // Check depth first
    new ParallelismTargetOptimization()         // Then parallelism
)
```

### AggregationDepthTargetOptimization

Flattens deep aggregation trees (reductions, convolutions, etc.).

**Location:** `code/src/main/java/io/almostrealism/compute/AggregationDepthTargetOptimization.java`

**Configuration:**
```java
// Compile-time constants
AggregationDepthTargetOptimization.AGGREGATION_THRESHOLD = 64;
AggregationDepthTargetOptimization.PARALLELISM_THRESHOLD = 128;

// Constructor takes depth limit
new AggregationDepthTargetOptimization(12);
```

**Decision Logic:**
```
1. Get context's aggregation count
2. If aggregationCount <= AGGREGATION_THRESHOLD (64):
   → Return null (not enough aggregation to worry about)

3. If tree depth <= limit:
   → Return null (not deep enough)

4. If any child has parallelism < PARALLELISM_THRESHOLD (128):
   → Return null (avoid creating bottlenecks)

5. Otherwise:
   → ISOLATE CHILDREN
```

**Use Cases:**
- Large convolution operations with many input channels
- Deep reduction trees
- Operations with high fan-in

**When NOT to Use:**
- Small aggregations (overhead dominates)
- Low-parallelism operations (creates bottlenecks)

### ParallelismDiversityOptimization

Handles processes with vastly different parallelism than their children.

**Location:** `code/src/main/java/io/almostrealism/compute/ParallelismDiversityOptimization.java`

**Configuration:**
```java
// Internal thresholds (compile-time)
DIVERSITY_THRESHOLD = 8;   // Min distinct parallelism counts
FACTOR_THRESHOLD = 64;     // Min ratio of avg child to parent parallelism
```

**Decision Logic:**
```
1. Count distinct parallelism values among children
2. If distinctCount <= DIVERSITY_THRESHOLD (8):
   → Return null (not enough diversity)

3. Calculate average child parallelism
4. If avgChildParallelism <= parent * FACTOR_THRESHOLD (64x):
   → Return null (not extreme enough)

5. Otherwise:
   → ISOLATE CHILDREN
```

**Use Cases:**
- Attention mechanisms with many heads
- Operations mixing batch/sequence/feature dimensions
- Processes where children have wildly varying sizes

### CascadingOptimizationStrategy

Chains multiple strategies; first successful result wins.

**Location:** `relation/src/main/java/io/almostrealism/compute/CascadingOptimizationStrategy.java`

**Usage:**
```java
ProcessOptimizationStrategy strategy = new CascadingOptimizationStrategy(
    new TraversableDepthTargetOptimization(8),    // Check depth first
    new AggregationDepthTargetOptimization(12),   // Then aggregation
    new ParallelismDiversityOptimization(),       // Then diversity
    new ParallelismTargetOptimization()           // Finally, default
);
```

**Execution:**
```java
for (ProcessOptimizationStrategy s : strategies) {
    Process result = s.optimize(ctx, parent, children, processor);
    if (result != null) {
        return result;  // First non-null wins
    }
}
return null;  // No strategy applied
```

**Design Principle:**
- More specific strategies first (depth, aggregation)
- More general strategies last (parallelism target)
- Each strategy returns `null` if not applicable

## Strategy Selection Guide

| Scenario | Recommended Strategy |
|----------|---------------------|
| General use | `ParallelismTargetOptimization` (default) |
| Deep expressions | `TraversableDepthTargetOptimization(8)` |
| Large reductions | `AggregationDepthTargetOptimization(12)` |
| Mixed workloads | `CascadingOptimizationStrategy` with all |
| Debugging | Set `enableOptimizationLog = true` |

## Creating Custom Strategies

```java
public class MyCustomStrategy implements ProcessOptimizationStrategy {

    @Override
    public <P extends Process<?, ?>, T> Process<P, T> optimize(
            ProcessContext ctx,
            Process<P, T> parent,
            Collection<P> children,
            Function<Collection<P>, Stream<P>> childProcessor) {

        // Analyze children
        long maxParallelism = children.stream()
            .mapToLong(c -> c instanceof ParallelProcess ?
                ((ParallelProcess) c).getParallelism() : 1)
            .max().orElse(1);

        // Apply custom logic
        if (shouldIsolate(maxParallelism)) {
            return generate(parent, children, true);  // Isolate
        }

        // Return null to let other strategies try
        return null;
    }

    private boolean shouldIsolate(long parallelism) {
        // Custom decision logic
        return parallelism > 10000;
    }
}
```

## Monitoring and Debugging

### Strategy Listeners

```java
ProcessOptimizationStrategy.listeners.add(parent -> {
    System.out.println("[OPTIMIZE] " + parent.getClass().getSimpleName());
});
```

### Isolation Flags

```java
ParallelProcess.isolationFlags.add(process -> {
    if (process instanceof MyProcessType) {
        System.out.println("[ISOLATION] " + process);
        return true;  // Log this isolation
    }
    return false;
});
```

### Verbose Logging

```java
ComputableParallelProcess.enableOptimizationLog = true;
```

## Performance Tuning

### Adjusting Thresholds

```java
// For GPU with high parallelism capacity
ParallelismTargetOptimization.minCount = 1024;
ParallelismTargetOptimization.targetCount = 524288;  // 2^19
ParallelismTargetOptimization.maxCount = 4194304;    // 2^22

// For memory-constrained environments
ParallelismTargetOptimization.enableNarrowMax = false;  // More isolation
ParallelismTargetOptimization.enableContextualCount = true;
```

### Strategy Ordering

```java
// Aggressive isolation (for debugging/memory issues)
new CascadingOptimizationStrategy(
    new TraversableDepthTargetOptimization(4),  // Lower depth limit
    new AggregationDepthTargetOptimization(8),  // Lower aggregation limit
    new ParallelismDiversityOptimization(),
    new ParallelismTargetOptimization()
)

// Minimal isolation (for maximum kernel fusion)
new CascadingOptimizationStrategy(
    new TraversableDepthTargetOptimization(16),  // Higher depth limit
    new ParallelismTargetOptimization()
)
```

## See Also

- [Process Optimization](process-optimization.md) - Overall architecture
- [Isolation Patterns](isolation-patterns.md) - When and how isolation works
- [Expression Evaluation](expression-evaluation.md) - Expression tree compilation
