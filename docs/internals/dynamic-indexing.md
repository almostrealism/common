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

## See Also

- [Kernel Count Propagation](kernel-count-propagation.md) - How traversal affects kernel counts
- [Assignment Optimization](assignment-optimization.md) - How dynamic destinations affect assignment paths
- [Expression Evaluation](expression-evaluation.md) - Overall compilation pipeline
