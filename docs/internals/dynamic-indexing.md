# Dynamic Indexing: traverseEach and integers()

## Overview

Dynamic indexing allows collections to be accessed with computed indices rather than
static offsets. This is essential for operations like gather, scatter, and permutation.

## Key Functions

### `integers(start, count)`

Creates a producer that generates a sequence of integers from `start` to `start + count - 1`.

**Location**: `CollectionFeatures.java`

```java
// Creates [0, 1, 2, 3, 4, 5]
Producer<PackedCollection> indices = integers(0, 6);

// Creates [5, 6, 7, 8, 9]
Producer<PackedCollection> indices = integers(5, 5);
```

**Implementation**: Uses `IndexProjectionProducerComputation` to generate index values at runtime.

### `traverseEach(producer)`

Creates a traversal that uses values from another producer as indices.

**Location**: `CollectionFeatures.java`

```java
// Use values from 'indices' collection as the index for each element
TraversalPolicy policy = traverseEach(p(indices));
```

**Implementation**: Returns a `TraversalPolicy` where the index at position `i` comes from
`indices.valueAt(i)` rather than being `i` itself.

### `traverse(axis, producer)`

Traverses a collection along a specific axis with dynamic indexing.

**Location**: `CollectionFeatures.java`

```java
// Traverse axis 0 of buffer, using dynamic indices
Producer<?> view = traverse(0, c(p(buffer), shape, indexProducer, traverseEach(p(indices))));
```

## Common Patterns

### Gather (Reading with Dynamic Indices)

Read from positions specified by an index array:

```java
PackedCollection data = new PackedCollection(shape(10)).fill(/* values */);
PackedCollection indices = new PackedCollection(shape(5)).fill(3, 1, 4, 1, 5);

// gathered[i] = data[indices[i]]
Producer<?> gathered = traverse(0, c(p(data), shape(10),
                                      integers(0, 5),
                                      traverseEach(p(indices))));
```

### Integer-Exact Indices Above 2^24 (Split-Index Gather)

Index data is ordinarily held in a floating-point `PackedCollection`. On a single-precision
backend (Metal, or any FP32 context) a `float` represents integers exactly only up to
`2^24` (16,777,216), so an index above that bound is **rounded when it is stored** — before
any kernel runs. A gather over a table with more than `2^24` elements then reads the wrong
element: `2^24 + 1` is stored as `2^24`, and `2^24 + 5` as `2^24 + 4`.

For such index data, store the indices in the **split layout** owned by
`SplitIndexTraversalOrdering` (`base/code`, `io.almostrealism.collect`) and gather with
`CollectionFeatures.gatherSplitIndex`:

```java
// Shape (3, 2): each index stored as a high lane and a low lane
PackedCollection index = PackedCollection.ofSplitIndices(16777216, 16777217, 16777221);

// gathered[i] = data[index[i]], exact for every int index
Producer<?> gathered = gatherSplitIndex(shape(3), p(data), p(index));
```

**Representation.** Each integer index `v` is stored as two values, interleaved so index `i`
occupies elements `2i` and `2i + 1` of an `(n, 2)` collection:

```
hi = floorDiv(v, BASE)
lo = floorMod(v, BASE)
v  = hi * BASE + lo
```

`BASE` is `SplitIndexTraversalOrdering.BASE = 2^12`. For any `int` index the high lane's
magnitude is at most `2^19` and the low lane lies in `[0, 2^12)`, so both lanes are exactly
representable in FP32 (any power of two in `[2^12, 2^24]` would also work; `2^12` leaves the
widest margin on the low lane). Negative indices are supported: the high lane carries the sign. The ordering
owns both directions of the layout: `SplitIndexTraversalOrdering.encode(int...)` produces the
lanes and `indexOf` decodes them; `PackedCollection.ofSplitIndices` stores `encode`'s output.

**Exactness guarantee.** `SplitIndexTraversalOrdering.indexOf` reads both lanes, converts each to
an integer, and computes `hi * BASE + lo` on integer expressions inside the kernel — the same
technique `CollectionFeatures.rows` uses for row addresses — so the index never passes back
through a floating-point value. The reconstruction is exact for the full `int` range
(below `2^31`); indices beyond that would need a `long` reconstruction, which is not provided.

**When to use it.** Prefer the split layout for index data whose values can exceed `2^24`:
sparse row offsets, edge lists, and gathers over large flat tables. For small index sets the
plain gather `c(shape, collection, index)` is simpler and equivalent. The representation is
opt-in — ordinary collections keep their precision. `ofSplitIndices` is a host ingest
boundary (index data entering the system) and uploads all lanes in one transfer.
`gatherSplitIndex` rejects an index producer whose declared shape does not end in a dimension
of `LANES = 2`, so an ordinary single-lane index cannot be silently misread as split lanes.

**Testing on FP32.** An index above `2^24` cannot be read back from an FP32 output buffer, and a
table holding `data[i] = i` rounds its payload exactly as a float index rounds the address, so
the bug is invisible with such data. Exactness is observable only through *which element* a
gather selects, using a payload that is exact and distinct between adjacent addresses (for
example the address modulo 16, computed from the integer kernel index). See `SplitIndexTests`.

### Scatter (Writing with Dynamic Indices)

Write to positions specified by an index array:

```java
PackedCollection buffer = new PackedCollection(shape(rows, cols));
PackedCollection rowIndices = new PackedCollection(shape(rows)).fill(/* row indices */);
PackedCollection values = new PackedCollection(shape(rows)).fill(/* values to write */);

// buffer[i, rowIndices[i]] = values[i]
Producer<?> dest = traverse(0, c(p(buffer), shape(buffer),
                                  integers(0, rows),
                                  traverseEach(p(rowIndices))));
a(dest, p(values)).get().run();
```

### Multi-Dimensional Dynamic Indexing

Access with multiple dynamic index dimensions:

```java
// buffer[i, j, indices[i,j]]
Producer<?> view = traverse(2, c(p(buffer), shape(buffer),
                                  integers(0, lastDimSize),
                                  traverseEach(p(indices))));
```

## TraversableExpression Interface

Dynamic indexing is implemented through `TraversableExpression`:

```java
public interface TraversableExpression<T> {
    Expression<T> getValueAt(Expression<?> index);
    Expression<T> getValueRelative(Expression<?> offset);
}
```

When an expression has dynamic indices, `getValueAt(index)` returns an expression that
computes the actual memory offset at runtime rather than compile time.

## Assignment with Dynamic Destinations

**Important**: When assigning to a dynamically indexed destination, the scope-based
assignment path must be used (see `assignment-optimization.md`).

The scope-based path generates code like:
```c
destination[computeIndex(i)] = source[i];
```

Rather than the short-circuit path which would evaluate the destination to a temporary.

## Expression Simplification

Dynamic index expressions often need simplification to generate efficient code:

```java
// Before simplification: complex nested index computation
// After simplification: simpler form that generates better native code
Expression simplified = expression.simplify(context);
```

See `ScopeSettings.isSeriesSimplificationTarget()` for simplification configuration.

## Debugging Tips

1. **Check index bounds**: Ensure index producer values are within valid range
2. **Verify traversal axis**: Wrong axis leads to incorrect memory offsets
3. **Check destination type**: Dynamic destinations require scope-based assignment
4. **Examine generated code**: Use logging to see what native code is generated

## Test Cases

See `CollectionComputationTests.java`:
- `integersIndex` - Basic reading with integers()
- `integersIndexAssignment` - Writing with dynamic indices
- `integersRangeLarge` - Large-scale integer sequences

See `SplitIndexTests.java`:
- `storageIsExactAboveSinglePrecision` - Split lanes recover indices above 2^24 exactly
- `gatherIsExactAboveSinglePrecision` - Split-index gather selects the exact element above 2^24
- `gatherReadsEveryStoredPosition` - Split-index gather across the lane boundary (`BASE - 1`, `BASE`, `BASE + 1`)
- `storageRoundTripsNegativeIndex` - Negative indices keep their sign in the high lane
- `gatherRejectsSingleLaneIndex` - Split-index gather refuses an index whose last dimension is not 2

## See Also

- [Kernel Count Propagation](kernel-count-propagation.md) - How traversal affects kernel counts
- [Assignment Optimization](assignment-optimization.md) - How dynamic destinations affect assignment paths
- [Expression Evaluation](expression-evaluation.md) - Overall compilation pipeline
