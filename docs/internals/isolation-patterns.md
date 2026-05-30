# Isolation Patterns

## Overview

Process isolation is the mechanism by which the optimization system breaks up large
expression trees into independently compiled units. This prevents exponential expression
growth, stack overflows, and excessive compilation times.

## The Expression Embedding Problem

### Without Isolation

When processes are not isolated, their expressions get embedded in parent expressions:

```
add(multiply(a, b), multiply(c, d))
    ├── multiply expression (embedded in add)
    │   ├── a.getValueAt(i) (embedded)
    │   └── b.getValueAt(i) (embedded)
    └── multiply expression (embedded in add)
        ├── c.getValueAt(i) (embedded)
        └── d.getValueAt(i) (embedded)
```

This creates a single large expression tree that:
- Takes exponentially longer to compile with depth
- May overflow the call stack during compilation
- Cannot be partially cached or reused

### With Isolation

When processes are isolated, they compile as separate kernels:

```
add(isolate(multiply(a, b)), isolate(multiply(c, d)))
    ├── [Kernel 1: multiply(a, b)] → temp1
    ├── [Kernel 2: multiply(c, d)] → temp2
    └── [Kernel 3: add(temp1, temp2)]
```

Each kernel compiles independently, enabling:
- Bounded compilation time per kernel
- Kernel caching and reuse
- Parallel compilation (future optimization)

## How Isolation Works

### The IsolatedProcess Pattern

```java
// Process.isolate() creates a wrapper
public default Process<P, T> isolate() {
    return Process.of(() -> this.get());
}

// Process.of() creates an IsolatedProcess
public static <T> Process<?, T> of(Supplier<Evaluable<T>> supplier) {
    return new IsolatedProcess<>(supplier);
}
```

### Breaking TraversableExpression

The key to isolation is that `IsolatedProcess` does **NOT** implement `TraversableExpression`:

```java
// In parent's getExpression():
protected CollectionExpression getExpression(TraversableExpression... args) {
    // For each child argument:
    if (child instanceof TraversableExpression) {
        // EMBEDDED: calls child.getValueAt(index)
        return ((TraversableExpression) child).getValueAt(index);
    } else {
        // ISOLATED: returns null, forces separate kernel
        return null;
    }
}
```

When `getValueAt()` returns `null`:
1. The parent expression cannot embed the child
2. A destination must be allocated for the child's output
3. The child compiles as a separate kernel
4. The parent reads from the child's output memory

## When Isolation Happens

### 1. Explicit Isolation Targets

```java
// Add a predicate to force isolation for specific process types
Process.explicitIsolationTargets.add(process ->
    process instanceof ExpensiveComputation);

// Check in optimization:
if (Process.isExplicitIsolation()) {
    if (Process.isolationPermitted(process)) {
        return process.isolate();
    }
}
```

**Use Case:** Force isolation for known-problematic operations regardless of optimization strategy.

### 2. Hardware Mismatch

```java
// In ComputableParallelProcess:
@Override
public boolean isIsolationTarget(ProcessContext ctx) {
    if (ctx instanceof ComputableProcessContext) {
        List<ComputeRequirement> contextReqs = ...;
        List<ComputeRequirement> processReqs = ...;

        // Isolation if requirements don't match
        if (!contextReqs.containsAll(processReqs)) {
            return true;
        }
    }
    return super.isIsolationTarget(ctx);
}
```

**Use Case:** GPU operations in a CPU context, or vice versa.

### 3. Optimization Strategy Decision

```java
// In ParallelismTargetOptimization:
if (isolatedScore > currentScore && !suspicious) {
    return generate(parent, children, true);  // Isolate
}
```

**Use Case:** Performance analysis determines isolation is beneficial.

### 4. Expression Depth Limit

```java
// In TraversableDepthTargetOptimization:
int depth = calculateExpressionDepth(child);
if (depth > limit) {
    return generate(parent, children, true);  // Isolate
}
```

**Use Case:** Prevent stack overflow from deep expression trees.

## Isolation Patterns by Scenario

### Pattern 1: Chain Breaking

**Problem:** Long chains of operations create deep expression trees.

```java
// Deep chain without isolation
result = op1(op2(op3(op4(op5(input)))));
// Expression depth: 5, grows exponentially with nesting
```

**Solution:** Insert isolation points in the chain.

```java
// With strategic isolation
temp1 = op1(op2(input)).optimize().get().evaluate();
temp2 = op3(op4(op5(p(temp1)))).optimize().get().evaluate();
// Each segment has bounded depth
```

**Automatic:** Use `TraversableDepthTargetOptimization(4)` to auto-isolate.

### Pattern 2: Multi-Input Aggregation

**Problem:** Operations with many inputs embed all input expressions.

```java
// Concatenation of many tensors
result = concat(t1, t2, t3, t4, t5, t6, t7, t8);
// All 8 tensors' expressions get embedded
```

**Solution:** Ensure inputs are isolated or pre-computed.

```java
// Compute heavy inputs first
List<PackedCollection> precomputed = heavyOps.stream()
    .map(op -> op.optimize().get().evaluate())
    .collect(toList());

// Concat from memory references
result = concat(precomputed.stream().map(this::p).toArray(Producer[]::new));
```

**Automatic:** Use `AggregationDepthTargetOptimization(12)` for high-aggregation scenarios.

### Pattern 3: Gradient Computation

**Problem:** Automatic differentiation creates expression trees that grow exponentially.

```java
// Forward: y = f(x)
// Backward: dy/dx = chain rule across all operations
CollectionProducer gradient = output.delta(input);
// Expression tree includes all intermediate derivatives
```

**Solution:** Use `TransitiveDeltaExpressionComputation` for isolated gradients.

```java
public class MyComputation extends TransitiveDeltaExpressionComputation {
    @Override
    public CollectionProducer delta(Producer<?> target) {
        // Create isolated delta computation
        List<Process<?, ?>> deltas = computeInputDeltas(target);
        return generate(deltas).reshape(outputShape.append(targetShape));
    }
}
```

**Key Insight:** Each transitive delta computation creates an isolated boundary.

### Pattern 4: Conditional Isolation

**Problem:** Some operations only need isolation under specific conditions.

```java
// Only isolate large operations
@Override
public boolean isIsolationTarget(ProcessContext ctx) {
    if (getParallelism() < 1000) {
        return false;  // Small, keep embedded
    }
    return super.isIsolationTarget(ctx);
}
```

**Solution:** Override `isIsolationTarget()` with custom logic.

### Pattern 5: Forced Isolation for Debugging

**Problem:** Need to understand where time is spent in computation.

```java
// Force all matching operations to isolate
Process.explicitIsolationTargets.add(p -> {
    if (p instanceof SuspectedSlowOperation) {
        System.out.println("Isolating: " + p);
        return true;
    }
    return false;
});
```

**Benefit:** Each isolated operation appears separately in profiles.

## Anti-Patterns

### Anti-Pattern 1: Over-Isolation

```java
// BAD: Isolating tiny operations
for (int i = 0; i < 1000; i++) {
    PackedCollection result = add(a, b).optimize().get().evaluate();
    // 1000 kernel launches instead of 1!
}
```

**Fix:** Batch operations or raise isolation thresholds.

### Anti-Pattern 2: Under-Isolation

```java
// BAD: No isolation on deep nested computation
OperationList ops = buildDeepComputationGraph();
Runnable r = ops.get();  // Missing optimize()!
// Potential stack overflow or timeout
```

**Fix:** Always call `optimize()` before `get()` for complex computations.

### Anti-Pattern 3: Isolation Without Context

```java
// BAD: Manual isolation without understanding impact
Process isolated = process.isolate();
// May create unnecessary memory allocation and kernel overhead
```

**Fix:** Let strategies decide, or understand the trade-offs first.

## Debugging Isolation Issues

### Check If Isolation Is Happening

```java
ParallelProcess.isolationFlags.add(p -> {
    System.out.println("[CHECK] " + p.getClass().getSimpleName() +
        " isIsolated=" + (p instanceof IsolatedProcess));
    return true;
});
```

### Trace Optimization Decisions

```java
ProcessOptimizationStrategy.listeners.add(parent -> {
    System.out.println("[OPT] Processing: " + parent);
});

ComputableParallelProcess.enableOptimizationLog = true;
```

### Verify Expression Depth

```java
// In your process:
@Override
public boolean isIsolationTarget(ProcessContext ctx) {
    int depth = calculateDepth(this);
    System.out.println("Depth of " + this + ": " + depth);
    return depth > 8 || super.isIsolationTarget(ctx);
}
```

### Profile Kernel Compilation

```java
// Enable profiling to see where time is spent
long start = System.nanoTime();
OperationList optimized = (OperationList) ops.optimize();
long optimizeTime = System.nanoTime() - start;

start = System.nanoTime();
Runnable r = optimized.get();
long compileTime = System.nanoTime() - start;

System.out.println("Optimize: " + (optimizeTime / 1e6) + "ms");
System.out.println("Compile: " + (compileTime / 1e6) + "ms");
```

## Performance Implications

### Memory Trade-offs

| Scenario | Memory Impact |
|----------|--------------|
| No isolation | Single output buffer |
| Full isolation | Buffer per isolated operation |
| Strategic isolation | Minimal intermediate buffers |

### Compilation Trade-offs

| Scenario | Compilation Impact |
|----------|-------------------|
| No isolation | One large, slow compilation |
| Full isolation | Many small, fast compilations |
| Strategic isolation | Balanced compilation time |

### Execution Trade-offs

| Scenario | Execution Impact |
|----------|-----------------|
| No isolation | One kernel launch (if it compiles) |
| Full isolation | Many kernel launches |
| Strategic isolation | Optimal kernel granularity |

## Best Practices

1. **Always call `optimize()` before `get()`** for non-trivial computations
2. **Use cascading strategies** for complex workloads
3. **Monitor compilation times** to detect isolation issues
4. **Profile first, optimize second** - don't guess where isolation is needed
5. **Use `TransitiveDeltaExpressionComputation`** for gradient-aware operations
6. **Test with varying thresholds** to find optimal configuration

## See Also

- [Process Optimization](process-optimization.md) - Overall architecture
- [Optimization Strategies](optimization-strategies.md) - Available strategies
- [Expression Evaluation](expression-evaluation.md) - Expression tree compilation
- [Profiling](profiling.md) - Performance analysis tools
