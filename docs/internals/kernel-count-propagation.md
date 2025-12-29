# Kernel Count Propagation

## Overview

When compiling and executing parallel operations, the framework must determine how many
parallel work items (kernel size) to use. This is controlled by the `count` and `isFixedCount()`
methods on computations and producers.

## Key Concepts

### Count vs MemLength vs TotalSize

For a `PackedCollection` with shape `(5, 4, 10)`:
- **TotalSize/MemLength**: 200 (total number of scalar elements: 5 × 4 × 10)
- **Count**: Depends on traversal axis. With default traversal, count = 20 (5 × 4)
- **AtomicMemLength**: Size of the innermost dimension = 10

### isFixedCount()

Determines whether the kernel count is known at compile time:
- `true`: Count is fixed, kernel is compiled with specific parallelism
- `false`: Count is dynamic, determined at runtime from output size

**Key implementations**:
- `TraversalPolicy.isFixedCount()`: Returns `fixed` field (false if shape has `-1` dimensions)
- `CollectionProducerComputationBase.isFixedCount()`: Combines shape and input fixedness
- `ComputationBase.isFixedCount()`: True if all inputs have fixed counts

### Count Propagation Through Operations

When operations are composed, counts propagate:

```java
// Input: shape (5, 4, 10), count = 20
Producer<?> traversed = traverse(2, input);  // count = 20

// mean() reduces, keeping count = 20
Producer<?> mean = mean(traversed);          // count = 20

// subtract broadcasts mean across input
Producer<?> diff = subtract(traversed, mean); // count = 20

// sq() preserves count from input
Producer<?> squared = sq(diff);               // count = 20
```

The issue: `squared` has count 20, but output has 200 elements.

## ProcessDetailsFactory Validation

`ProcessDetailsFactory.init()` validates that the kernel count matches the output:

```java
if (isFixedCount()) {
    kernelSize = getCount();

    if (output != null) {
        long dc = output.getCountLong();
        boolean matchCount = List.of(1L, getCountLong()).contains(dc);
        boolean matchTotal = getCountLong() == output.getMemLength();

        if (!matchCount && !matchTotal) {
            throw new IllegalArgumentException("The destination count (" + dc +
                    ") must match the count for the process (" + kernelSize + "), unless the count " +
                    "for the process is identical to the total size of the output (" +
                    output.getMemLength() + ")");
        }
    }
}
```

**Valid scenarios**:
1. Output count equals 1 (scalar output)
2. Output count equals kernel count (direct match)
3. Kernel count equals output's total memory length (full coverage)

## Common Count Mismatch Scenarios

### Scenario 1: Traversal with Non-Reducing Operation

```java
// Shape (5, 4, 10) - 200 elements, count 20
PackedCollection input = new PackedCollection(shape(5, 4, 10));

// subtractMean(2) operates along axis 2 but preserves shape
// Kernel count is 20, but output needs 200 elements
sq(cp(input).subtractMean(2))  // May cause count mismatch in optimized path
```

**Why it fails**: The traversal sets count to 20, but the output shape `(5, 4, 10)` has 200 elements.

**Resolution**: The validation now allows when kernel count covers the full output via `matchTotal`.

### Scenario 2: Reduction Followed by Broadcast

```java
// mean() reduces, producing count = 20
Producer<?> mean = mean(traverse(2, input));

// subtract() broadcasts mean back to full shape
Producer<?> diff = subtract(input, mean);
```

The subtraction broadcasts the reduced mean across all 200 elements.

### Scenario 3: OperationList with Mismatched Counts

```java
OperationList op = new OperationList();
op.add(output.getAtomicMemLength(), computation, p(output));
op.optimize();  // May fuse operations with different counts
```

During optimization, operations may be fused, and the resulting kernel count
must still be valid for the output.

## Debugging Count Mismatches

1. **Check the error message**: It now includes actual counts and expected values
2. **Print shapes**: Log `shape.getCount()` and `shape.getMemLength()`
3. **Check traversal axis**: Traversal changes how count is computed
4. **Look at operation composition**: Reductions and broadcasts affect counts

## Related Files

- `ProcessDetailsFactory.java:314-325` - Count validation logic
- `TraversalPolicy.java:861` - `isFixedCount()` for shapes
- `CollectionProducerComputationBase.java:459` - `isFixedCount()` for computations
- `TestFeatures.java:577` - Test harness that can trigger count mismatches

## Test Cases

- `CollectionMathTests.subtractMeanSq` - Tests traversal with sq() operation
- `CollectionMathTests.variance2` - Tests traversal with reduction (passes because reduction matches count)
- `NormTests` - Tests normalization which uses subtractMean and variance
