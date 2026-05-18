# Shape and Traversal System

The shape and traversal system provides the abstraction that maps multi-dimensional
structure onto flat, contiguous memory. Every tensor, matrix, and collection in the
framework is ultimately a one-dimensional array of doubles; `TraversalPolicy` is the
object that gives that flat memory its N-dimensional interpretation.

---

## TraversalPolicy — The Central Type

`TraversalPolicy` is defined in `io.almostrealism.collect` and implements
`Traversable<TraversalPolicy>`, `Countable`, and `Describable`.

### Creating a shape

The most common constructor takes dimension lengths as `int` or `long` varargs:

```java
// A 10x20 matrix (200 elements total)
TraversalPolicy matrix = new TraversalPolicy(10, 20);

// A 3D tensor: 4 batches of 8x16 matrices (512 elements)
TraversalPolicy tensor = new TraversalPolicy(4, 8, 16);
```

There is no `shape()` factory method; use the constructor directly.

### Querying dimensions

| Method               | Returns                                              |
|----------------------|------------------------------------------------------|
| `getDimensions()`    | Number of axes (rank)                                |
| `length(axis)`       | Size along a single axis                             |
| `getTotalSize()`     | Product of all dimensions (total element count)      |
| `extent()`           | `int[]` of all dimension lengths                     |
| `getTraversalAxis()` | Current traversal axis (see below)                   |

### Coordinate-to-index mapping

`TraversalPolicy` converts between N-dimensional positions and linear indices:

```java
TraversalPolicy shape = new TraversalPolicy(3, 4);

// Position (1, 2) -> linear index
int idx = shape.index(1, 2);   // returns 6  (1*4 + 2)

// Linear index -> position
int[] pos = shape.position(6); // returns [1, 2]
```

Both `index()` and `position()` also accept `Expression` arguments, producing
symbolic expressions that can be embedded in generated GPU/CPU kernel code.

---

## Traversal — Slicing a Shape into Count and Size

The **traversal axis** splits the dimensions into two groups:

- **Count**: the product of dimensions *before* the axis (how many chunks)
- **Size**: the product of dimensions *at and after* the axis (elements per chunk)

```java
TraversalPolicy shape = new TraversalPolicy(4, 8, 16);
// Default traversalAxis = 0 -> count=1, size=512

TraversalPolicy t1 = shape.traverse(1);
// traversalAxis = 1 -> count=4, size=128

TraversalPolicy t2 = shape.traverse(2);
// traversalAxis = 2 -> count=32, size=16

TraversalPolicy t3 = shape.traverseEach();
// traversalAxis = 3 -> count=512, size=1
```

The relevant methods on `TraversalPolicy`:

| Method           | Description                                              |
|------------------|----------------------------------------------------------|
| `traverse(axis)` | Returns a new policy with the given traversal axis       |
| `traverse()`     | Advances to the next axis (current + 1)                  |
| `consolidate()`  | Moves to the previous axis (current - 1)                 |
| `traverseEach()` | Sets axis = `getDimensions()` (every element separately) |
| `getCountLong()` | `getTotalSize() / getSize()` — number of chunks          |
| `getSizeLong()`  | Product of dimensions from traversalAxis onward          |

The traversal axis determines how GPU kernels partition work. A kernel with
`count=4` and `size=128` dispatches 4 work items, each processing 128 elements.

---

## Fixed vs Variable Count

A `TraversalPolicy` is either **fixed-count** or **variable-count**, controlled
by the `fixed` parameter in the constructor (default is `true`).

```java
// Fixed-count (default) — dimensions are compile-time constants
TraversalPolicy fixed = new TraversalPolicy(10, 20);
fixed.isFixedCount(); // true

// Variable-count — dimensions may change at runtime
TraversalPolicy variable = new TraversalPolicy(false, false, 10, 20);
variable.isFixedCount(); // false
```

**Why this matters for GPU compilation:** fixed-count shapes allow the compiler
to emit constant buffer sizes and loop bounds. Variable-count shapes force the
compiler to generate dynamic size calculations, because the actual count is
determined by the size of arguments passed to `Evaluable.evaluate()` at runtime.

Use fixed-count for model weights and architecture constants. Use variable-count
for batch inputs where the batch dimension is not known until evaluation.

---

## The Traversable Interface

`Traversable<T>` is the minimal interface for axis-based traversal:

```java
public interface Traversable<T> {
    T traverse(int axis);
}
```

The axis parameter controls granularity: axis 0 is the coarsest (entire collection
as one unit), and higher axes are finer (smaller chunks, more iterations). The
interface is parameterized so that `traverse()` returns the implementing type.

---

## The Shape Interface

`Shape<T>` extends `Traversable<T>`, `IndexSet`, and `Describable`. It is the
primary interface for objects that have a multi-dimensional shape:

```java
public interface Shape<T> extends Traversable<T>, IndexSet, Describable {
    TraversalPolicy getShape();
    T reshape(TraversalPolicy shape);
    // ... default methods below
}
```

Key default methods provided by `Shape`:

| Method            | Description                                                    |
|-------------------|----------------------------------------------------------------|
| `reshape(dims)`   | Change dimensions (total elements must match; -1 infers one)   |
| `flatten()`       | Reshape to a single dimension `[totalSize]`                    |
| `each()`          | Alias for `traverseEach()` — traverse individual elements      |
| `traverseAll()`   | `traverse(0)` — treat collection as single unit                |
| `traverseEach()`  | `traverse(getDimensions())` — iterate every element            |
| `consolidate()`   | `traverse(currentAxis - 1)` — coarser traversal                |
| `traverse()`      | `traverse(currentAxis + 1)` — finer traversal                  |

The `reshape` method supports dimension inference with -1:

```java
// Given a shape (4, 8, 16) = 512 elements total
shape.reshape(2, -1);   // produces (2, 256)
shape.reshape(-1, 8);   // produces (64, 8)
```

---

## IndexSet and TraversalOrdering

`IndexSet` defines membership testing for a set of integer indices. Its core method
returns an `Expression` (not a plain boolean), so membership tests can be embedded
in generated kernel code:

```java
public interface IndexSet {
    Expression<Boolean> containsIndex(Expression<Integer> index);
    default Optional<Boolean> containsIndex(int index);
}
```

`TraversalOrdering` extends `IndexSet` and adds index remapping:

```java
public interface TraversalOrdering extends IndexSet {
    Expression<Integer> indexOf(Expression<Integer> idx);
    default TraversalOrdering compose(TraversalOrdering other);
}
```

A `TraversalPolicy` can carry a `TraversalOrdering` (accessed via `getOrder()`)
that remaps indices before they reach the underlying memory. Orderings compose:
`traverse(ordering)` on a policy combines the new ordering with any existing one.

---

## Relationship to PackedCollection

`PackedCollection` (in `org.almostrealism.collect`) holds a flat memory buffer
and a `TraversalPolicy` that gives it shape:

```java
// Create a 10x20 collection (200 elements of flat memory)
PackedCollection<?> data = new PackedCollection<>(new TraversalPolicy(10, 20));

// The shape is always accessible
TraversalPolicy shape = data.getShape();
shape.getDimensions();  // 2
shape.length(0);        // 10
shape.length(1);        // 20
shape.getTotalSize();   // 200
```

`PackedCollection` implements `Shape`, so all traversal and reshape operations
apply directly:

```java
PackedCollection<?> flat = data.flatten();          // shape (200)
PackedCollection<?> batched = data.traverse(1);     // count=10, size=20
PackedCollection<?> each = data.traverseEach();     // count=200, size=1
```

The traversal axis on a `PackedCollection`'s shape determines how operations
like kernel dispatch partition the data. The underlying flat memory is never
rearranged; only the `TraversalPolicy` interpretation changes.

---

## Additional Shape Operations

`TraversalPolicy` provides several methods for constructing derived shapes:

| Method                       | Description                                          |
|------------------------------|------------------------------------------------------|
| `prependDimension(size)`     | Add a new outermost dimension                        |
| `appendDimension(size)`      | Add a new innermost dimension                        |
| `append(TraversalPolicy)`    | Concatenate dimensions from another policy           |
| `permute(order)`             | Reorder dimensions (like NumPy transpose)            |
| `repeat(count)`              | Tile along the traversal axis                        |
| `repeat(axis, count)`        | Tile along a specific axis                           |
| `withRate(axis, num, denom)` | Change the traversal rate for an axis                |
| `subset(shape, index, loc)`  | Compute index mapping for extracting a sub-region    |

These methods return new `TraversalPolicy` instances; the original is immutable
with respect to its dimensions and axis configuration.
