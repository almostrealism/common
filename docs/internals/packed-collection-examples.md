# PackedCollection — Comprehensive Guide

This document covers the core data type in the Almost Realism framework.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules, and the
[Quick Reference](../QUICK_REFERENCE.md) for a condensed API cheatsheet.

---

## What PackedCollection Is

`PackedCollection` is a hardware-accelerated multi-dimensional array backed by
contiguous memory. It is the fundamental data container for all numerical
computation in the framework. Think of it as a flat block of doubles that a
`TraversalPolicy` interprets as a tensor of arbitrary rank.

Key properties:

- **Flat memory, logical shape.** Data is stored in a single contiguous buffer
  in row-major (C-style) order. A `TraversalPolicy` defines how that flat
  buffer maps to multi-dimensional indices.
- **Hardware-backed.** The underlying memory may live on the GPU, on a native
  off-heap buffer, or on the Java heap — you do not choose directly. The
  `MemoryProvider` and `Heap` infrastructure manage placement.
- **Not a Java array.** You must never treat it like one. No `System.arraycopy`,
  no `Arrays.copyOf`, no tight `setMem` loops. Use the Producer pattern instead
  (see below).

Memory layout example for a 3x4 matrix:

```
Logical view:         Flat memory:
[0  1  2  3]          [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
[4  5  6  7]
[8  9 10 11]
```

---

## Creating Collections

### From dimensions

```java
// Varargs int constructor — creates a 3x4x5 tensor (60 elements)
PackedCollection tensor = new PackedCollection(3, 4, 5);

// From a TraversalPolicy
TraversalPolicy shape = new TraversalPolicy(10, 20);
PackedCollection matrix = new PackedCollection(shape);
```

### From existing data

```java
// Static factory from a double array
PackedCollection vec = PackedCollection.of(1.0, 2.0, 3.0);

// Static factory from a List<Double>
PackedCollection vec2 = PackedCollection.of(List.of(1.0, 2.0, 3.0));
```

### Copy constructor

```java
// Deep copy — allocates new memory and copies data
PackedCollection copy = new PackedCollection(original);
```

### Zero-copy views via delegation

```java
// View into existing memory at an offset — no data is copied
PackedCollection view = new PackedCollection(
    new TraversalPolicy(10, 10),   // shape of the view
    0,                              // traversal axis
    largeBuffer,                    // delegate (the backing MemoryData)
    100                             // offset into the delegate
);

// Changes to `view` modify `largeBuffer` starting at position 100
view.setMem(0, 5.0);
```

### Initialization

```java
PackedCollection data = new PackedCollection(100);

data.fill(0.0);                        // Fill with a constant
data.fill(1.0, 2.0, 3.0);             // Fill repeating pattern
data.fill(() -> Math.random());        // Fill from a DoubleSupplier
data.fill(pos -> pos[0] * pos[1]);     // Fill from a position function
data.replace(x -> x * 2.0);           // Transform values in-place
data.identityFill();                   // Identity matrix (2D only)
data.randFill();                       // Uniform random [0, 1)
data.randnFill();                      // Normal distribution (mean 0, std 1)
data.clear();                          // Zero out all elements
```

---

## TraversalPolicy

A `TraversalPolicy` defines the dimensions of a collection and how elements are
traversed. It maps between multi-dimensional positions in the "output space" and
flat indices in the "input space" (the underlying memory).

### Shape basics

```java
TraversalPolicy shape = new TraversalPolicy(3, 4, 5);

shape.getDimensions();     // 3 (the rank — number of axes)
shape.length(0);           // 3 (size along axis 0)
shape.length(1);           // 4 (size along axis 1)
shape.length(2);           // 5 (size along axis 2)
shape.getTotalSize();      // 60 (3 * 4 * 5)
shape.extent();            // int[]{3, 4, 5}
```

### Traversal axis

The traversal axis splits a collection into a "count" of items, each of a
certain "size". For a shape `(3, 4, 5)`:

| Traversal axis | Count | Size | Meaning |
|:-:|:-:|:-:|---|
| 0 | 1 | 60 | Entire collection is one item |
| 1 | 3 | 20 | 3 items, each 4x5 = 20 elements |
| 2 | 12 | 5 | 12 items, each 5 elements |
| 3 | 60 | 1 | 60 scalar items |

```java
TraversalPolicy shape = new TraversalPolicy(3, 4, 5);

// Default traversal axis is 0
shape.getTraversalAxis();  // 0
shape.getCountLong();      // 1
shape.getSize();           // 60

// Move traversal axis to 1: treat as 3 items of size 20
TraversalPolicy t1 = shape.traverse(1);
t1.getCountLong();         // 3
t1.getSize();              // 20

// traverseEach: each element is its own item
TraversalPolicy each = shape.traverseEach();
each.getTraversalAxis();   // 3
each.getCountLong();       // 60
each.getSize();            // 1
```

### Index conversion

```java
TraversalPolicy shape = new TraversalPolicy(3, 4);

// Position (row=1, col=2) -> flat index 6
shape.index(1, 2);       // 6

// Flat index 6 -> position [1, 2]
shape.position(6);        // int[]{1, 2}
```

### Fixed vs variable count

A fixed-count policy (the default) has predetermined dimensions. A
variable-count policy can adapt at runtime to match the size of inputs passed
to an `Evaluable.evaluate()` call.

```java
// Fixed count (default) — dimensions are locked
TraversalPolicy fixed = new TraversalPolicy(10, 20);
fixed.isFixedCount();  // true

// Variable count — created with fixed=false
TraversalPolicy variable = new TraversalPolicy(false, false, 10, 20);
variable.isFixedCount();  // false
```

### Shape manipulation

```java
TraversalPolicy shape = new TraversalPolicy(3, 4, 5);

shape.subset(1);              // Drop first axis -> (4, 5)
shape.prependDimension(2);    // Add axis at front -> (2, 3, 4, 5)
shape.appendDimension(7);     // Add axis at end -> (3, 4, 5, 7)
shape.replaceDimension(0, 6); // Replace axis 0 -> (6, 4, 5)
shape.permute(2, 0, 1);       // Reorder axes -> (5, 3, 4)
```

---

## The Producer Pattern

**This is the single most important concept for working with PackedCollection.**

PackedCollection data may live on the GPU. Direct element-by-element access
forces round-trips between GPU and CPU memory, destroying performance. Instead,
build computation graphs using `CollectionProducer` and evaluate them in one
shot.

```java
// WRONG: CPU loop defeats GPU parallelism
for (int i = 0; i < size; i++) {
    result.setMem(i, source.toDouble(i) * 2);  // Round-trip per element!
}

// CORRECT: GPU-accelerated computation
CollectionProducer result = cp(source).multiply(2.0);
PackedCollection evaluated = result.evaluate();  // Runs on GPU
```

The `cp()` method (from `CollectionFeatures`) wraps a `PackedCollection` in a
`CollectionProducer`. From there, chain operations to build an expression graph.
Call `.evaluate()` once at the end to execute the entire graph on the hardware.

```java
// WRONG: toArray() + manipulation + setMem() forces CPU round-trip
double[] data = collection.toArray();  // GPU -> CPU
for (int i = 0; i < data.length; i++) { data[i] *= 2; }
result.setMem(data);  // CPU -> GPU

// CORRECT: Chained operations stay on GPU
CollectionProducer result = cp(x)
        .subtract(cp(modelOutput).multiply(t))
        .multiply(1.0 - tPrev)
        .add(cp(noise).multiply(tPrev));
return result.evaluate();
```

### Common operations reference

| Task | WRONG | CORRECT |
|------|-------|---------|
| Multiply by scalar | `for (i) result.setMem(i, x.toDouble(i) * 2)` | `cp(x).multiply(2.0).evaluate()` |
| Add two collections | `for (i) result.setMem(i, a.toDouble(i) + b.toDouble(i))` | `cp(a).add(cp(b)).evaluate()` |
| Clamp values | `for (i) result.setMem(i, Math.max(min, x.toDouble(i)))` | `max(cp(x), c(min)).evaluate()` |
| Fill with noise | `for (i) result.setMem(i, random.nextGaussian())` | `new PackedCollection(shape).randnFill(random)` |

---

## Common Operations on PackedCollection

### Element access (use sparingly — debugging only)

```java
PackedCollection data = new PackedCollection(3, 4);

// Single element read
double val = data.toDouble(0);         // Read flat index 0

// Multi-dimensional read
double val2 = data.valueAt(1, 2);      // Read position (row=1, col=2)

// Single element write
data.setMem(0, 1.5);

// Multi-dimensional write
data.setValueAt(1.5, 1, 2);           // Write to position (row=1, col=2)

// Write a row at logical index 0
data.set(0, new double[]{1.0, 2.0, 3.0, 4.0});

// Read a row at logical index 0
double[] row = data.get(0).toArray(0, data.get(0).getMemLength());
```

### Reshape and traverse

```java
PackedCollection data = new PackedCollection(3, 4);  // 12 elements

// Reshape to different dimensions (total must stay the same)
PackedCollection reshaped = data.reshape(2, 6);
PackedCollection reshaped2 = data.reshape(12);

// Reshape with inference: -1 means "compute this dimension"
PackedCollection reshaped3 = data.reshape(-1, 3);  // -> (4, 3)

// Flatten to 1D
PackedCollection flat = data.flatten();

// Traverse: change the iteration granularity
PackedCollection t = data.traverse(1);  // 3 items of size 4
```

### Subsetting with range()

`range()` creates a zero-copy view into a sub-region of the collection.

```java
PackedCollection data = new PackedCollection(100);

// View the first 10 elements
PackedCollection first10 = data.range(new TraversalPolicy(10));

// View 10 elements starting at offset 20
PackedCollection slice = data.range(new TraversalPolicy(10), 20);

// View with a multi-dimensional shape
PackedCollection block = data.range(new TraversalPolicy(5, 4), 0);
```

The returned collection shares memory with the original. Writes to the view
modify the original data, and vice versa.

### Repeating

```java
PackedCollection original = new PackedCollection(2, 3);

// Create a view that repeats the data 4 times
// Shape becomes (4, 2, 3), but no data is duplicated
PackedCollection repeated = original.repeat(4);
```

### Delegation

```java
// Create a flat view at a specific offset and length
PackedCollection segment = data.delegate(offset, length);
```

### Streaming

```java
PackedCollection data = new PackedCollection(3, 4);

// Stream of doubles (flat)
double sum = data.doubleStream().sum();

// Stream of sub-collections (one per count)
data.traverse(1).stream().forEach(row -> row.print());

// Iterate sub-collections
data.traverse(1).forEach(row -> {
    // each row is a PackedCollection of size 4
});
```

### Misc

```java
data.print();                     // Pretty-print to stdout
data.transpose();                 // Transpose a 2D collection
data.argmax();                    // Index of the maximum value
data.save(new File("data.dat"));  // Serialize to file

// Load collections from file (returns an Iterable)
Iterable<PackedCollection> loaded = PackedCollection.loadCollections(new File("data.dat"));
```

---

## Memory Layout and Delegation

### How delegation works

A `PackedCollection` can either own its memory directly or act as a **view**
into another `MemoryData` object (its "delegate"). When you call `range()`,
`get()`, `reshape()`, or `traverse()`, the returned collection typically
delegates to the original — no data is copied.

```
Original (owns memory):
  mem = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

View via range(shape(3), 4):
  delegate = Original
  delegateOffset = 4
  Logical contents: [4, 5, 6]
```

Multiple views can point into the same underlying buffer. This is how large
tensors are sliced efficiently without allocation.

### What range() returns

`range(shape, start)` returns a new `PackedCollection` with:
- The given `TraversalPolicy` as its shape
- A delegate pointing to the source collection at the given offset
- No new memory allocation

If the range covers the entire collection with the same shape, `range()`
returns `this` (the original object).

### The delegate chain

If you call `range()` on a collection that is itself a delegate (with offset 0
and no ordering), the method follows the chain to point directly at the root
memory. This avoids deep delegation chains.

---

## GPU Memory Considerations

### Memory providers

The framework uses `MemoryProvider` instances to allocate memory. Which provider
is used depends on the hardware backend and configuration. You do not choose
directly — the system selects the appropriate provider.

### Why destroy() matters

When a `PackedCollection` is no longer needed and holds significant memory, call
`destroy()` to release it promptly. Relying on garbage collection is not
sufficient for GPU or off-heap memory, which the JVM garbage collector cannot
track.

```java
PackedCollection temp = cp(a).add(cp(b)).evaluate();
// ... use temp ...
temp.destroy();  // Release GPU/off-heap memory immediately
```

When destroying a collection that spans its entire delegate, `destroy()` also
destroys the delegate. This prevents memory leaks in delegation chains.

### Heap allocation

The `Heap` class provides a stack-based arena allocator for short-lived
allocations. When a `Heap` is active on the current thread, `PackedCollection`
allocations go through it. The heap pre-allocates a single large memory block
and hands out slices, which is faster than individual allocations.

```java
Heap heap = new Heap(100_000);
heap.use(() -> {
    // All PackedCollection allocations on this thread use the heap
    PackedCollection temp = new PackedCollection(1000);
    // ...
});
// Heap memory released when use() returns
```

### Thread safety

`PackedCollection` is **not thread-safe**. For concurrent access, use the
computation framework (which handles synchronization internally) or provide
your own external synchronization.

---

## Anti-Patterns

These indicate you are bypassing the hardware abstraction. The
`CodePolicyViolationDetector` enforces these rules in CI.

### Never use Java array operations

```java
// WRONG: System.arraycopy anywhere near PackedCollection
System.arraycopy(source.toArray(), 0, dest, 0, length);

// WRONG: Arrays.copyOf with PackedCollection
double[] copy = Arrays.copyOf(collection.toArray(), newLength);
```

### Never use tight setMem loops

```java
// WRONG: Element-by-element mutation in a loop
for (int i = 0; i < size; i++) {
    result.setMem(i, source.toDouble(i) * 2);
}

// CORRECT: Use the Producer pattern
cp(source).multiply(2.0).get().into(result).evaluate();
```

### Never round-trip through toArray()

```java
// WRONG: Pull to CPU, manipulate, push back
double[] data = collection.toArray();
for (int i = 0; i < data.length; i++) { data[i] *= 2; }
result.setMem(data);

// CORRECT: Stay on GPU
cp(collection).multiply(2.0).evaluate();
```

### Never assume data is "just there" in JVM memory

The backing memory may be on a GPU or in a native off-heap buffer. Treating
it like a Java array will produce incorrect results or crash.

---

## Related Documentation

- [CLAUDE.md](../../CLAUDE.md) — Project rules including the PackedCollection policy
- [Quick Reference](../QUICK_REFERENCE.md) — Condensed API cheatsheet
- [Collect module README](../../base/collect/README.md) — Module overview
- [Hardware README](../../base/hardware/README.md) — Memory and GPU configuration
