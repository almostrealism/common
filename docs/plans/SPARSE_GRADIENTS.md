# Sparse Gradient Computation - Future Directions

This document captures ideas for handling sparse gradient computation that may be explored after exhausting simpler `uniqueNonZeroOffset` optimizations.

## Context

When computing gradients for operations with sparse Jacobians (diagonal, projection, etc.), the current approach creates full Jacobian matrices that can exceed `MemoryProvider.MAX_RESERVATION` (268M elements for FP64). Two alternative approaches are documented here for future consideration.

## Approach 1: TraversalPolicy Rate Adjustment

TraversalPolicy already supports having logical shapes different from physical memory sizes through rate adjustment (similar to how `repeat` works). This could be extended for sparse patterns:

- A diagonal Jacobian [n, n] could be backed by n physical values
- The TraversalPolicy rate along dimensions would handle the mapping
- No new patterns needed - use existing infrastructure

**Key insight**: The system already has mechanisms for this. Need to explore how `repeat` and rate adjustment work, and whether they can be applied to sparse Jacobian representation.

**Investigation needed**:
- How does TraversalPolicy rate adjustment work internally?
- Can PackedCollection allocation respect physical vs logical size differences?
- How would kernel generation handle sparse access patterns?

## Approach 2: Vector-Jacobian Products (VJP)

Instead of computing full Jacobians then aggregating, pass aggregation intent to `delta()`:

```java
// Current: full Jacobian then multiply
CollectionProducer jacobian = f.delta(x);                // [output, input]
CollectionProducer grad = upstreamGrad.matmul(jacobian); // expensive

// VJP: compute aggregated result directly
CollectionProducer grad = f.delta(x, upstreamGrad);      // no intermediate
```

For subset with upstream gradient `g`:
- Current: Create [outputSize, inputSize] sparse Jacobian, then multiply
- VJP: Directly scatter `g` to input positions

This is what efficient backward-mode automatic differentiation does.

**Investigation needed**:
- API design for delta() with upstream gradient
- Which operations benefit most from VJP?
- Integration with existing gradient infrastructure

## Prerequisites

Before exploring these approaches, first exhaust `uniqueNonZeroOffset` optimizations:
1. SubsetProjectionComputation - DONE
2. DiagonalCollectionExpression - when pattern doesn't match Index.child()
3. CollectionProductComputation
4. PackedCollectionEnumerate
5. Other computations that fall back to expensive TraversableExpression default

Only after these optimizations are complete will it be clear which harder cases require the approaches above.
