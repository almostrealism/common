# Assignment Optimization: Short-Circuit vs Scope-Based Paths

## Overview

The `Assignment` class (`hardware/src/main/java/org/almostrealism/hardware/computations/Assignment.java`)
implements element-wise assignment from a source computation to a destination collection. It uses two
different execution paths depending on the complexity of the destination.

## Execution Paths

### Short-Circuit Path (Fast Path)

Used when:
- The destination is a simple provider (checked via `Computable.provider(out)`)
- The source resolves to something that can be written into that memory
  directly — a constant, another `Provider`, or an `AcceleratedOperation`

When the destination is a provider, `Assignment.get()` can write into the
destination's memory directly instead of compiling a scope:
1. Evaluates the destination directly: `MemoryBank destination = (MemoryBank) out.get().evaluate()`
2. Dispatches on the source: a constant is filled directly, a `Provider`
   source becomes a plain memory copy (via the `Runner`), and an
   `AcceleratedOperation` source is wrapped in a `DestinationEvaluable` so
   execution writes straight to the destination memory

**Key code** (the provider branch of `Assignment.get()`):
```java
if (Computable.provider(out)) {
    MemoryBank destination = (MemoryBank) out.get().evaluate();
    // ... constant-fill and provider-to-provider copy handled first ...
    if (ev instanceof AcceleratedOperation<?>) {
        return new DestinationEvaluable(ev, destination);
    }
}
```

### Scope-Based Path (General Path)

Used whenever the direct-write path does not apply, which includes:
- The destination is a computation, not a simple provider (e.g. dynamic indexing)
- The source and destination shapes do not have matching total size or count
- The destination is a provider but the source is neither a constant, a
  `Provider`, nor an `AcceleratedOperation`

The scope-based path:
1. Generates a proper `Scope` with statements
2. Uses `TraversableExpression.getValueAt(index).assign(value)` for assignments
3. Compiles the scope into native code

**Key code** (the scope-based branch of `Assignment.get()`):
```java
return super.get();
```

## The Dynamic Indexing Problem

When the destination is a dynamically indexed collection (e.g., created via `traverseEach()`),
evaluating it with `.get().evaluate()` creates a **temporary collection** rather than referencing
the underlying memory buffer.

### Example: Failing Case

```java
// Create buffer and dynamic view into it
PackedCollection buffer = new PackedCollection(shape(count, size));
PackedCollection indices = new PackedCollection(shape(count)).fill(1, 2, 3);

// This creates a dynamic view - NOT a simple provider
Producer<?> dynamicDest = traverse(0, c(p(buffer), shape(buffer),
                                        integers(0, count),
                                        traverseEach(p(indices))));

// Assignment to dynamic destination
Assignment<?> a = a(dynamicDest, p(value));
```

If the short-circuit path is used:
1. `dynamicDest.get().evaluate()` creates a temporary collection
2. `DestinationEvaluable` writes to this temporary
3. The temporary is discarded
4. Original `buffer` remains unchanged

### The Fix

`Assignment.get()` only takes a direct-write path when the destination is a
simple provider; anything else falls through to the scope-based path:

```java
// If the destination is a computation (e.g., a dynamically indexed
// collection), evaluating it creates a temporary collection rather than
// referencing the underlying memory. Only a provider destination can be
// written directly, so anything else uses the scope-based path.
if (Computable.provider(out)) {
    // ... direct-write optimizations (constant fill, provider-to-provider
    //     copy, DestinationEvaluable for an accelerated source) ...
}

return super.get();
```

## Related Classes

- `Computable.provider()` - Checks if a producer is a simple provider vs computation
- `DestinationEvaluable` - Wrapper that writes computation results to a destination
- `TraversableExpression` - Interface for expressions supporting dynamic indexing
- `Provider` - Simple wrapper around existing memory (no computation)

## Debugging Tips

If assignment appears to do nothing:
1. Check if destination is a computation (dynamic indices, arithmetic)
2. Verify `Computable.provider(destination)` returns true
3. Check if short-circuit path is being used incorrectly
4. Look for temporary collections being created and discarded

## Test Cases

See `CollectionComputationTests.java`:
- `integersIndexAssignment` - Tests assignment with dynamic indices
- `integersIndexAssignmentOptimized` - Tests the same assignment through the optimized path
- `integersIndexAssignmentOperation` / `integersIndexAssignmentOperationIsolated` - Test dynamic-index assignment as an operation and as an isolated operation

## See Also

- [Dynamic Indexing](dynamic-indexing.md) - How traverseEach and integers() work
- [Kernel Count Propagation](kernel-count-propagation.md) - How counts affect operation execution
- [Expression Evaluation](expression-evaluation.md) - Overall compilation pipeline
