# AR-Algebra Module

**Core algebraic types, collection operations, and matrix computations built on PackedCollection for hardware-accelerated numerical computing.**

## Overview

The `ar-algebra` module provides fundamental mathematical types and operations for the Almost Realism framework. All types are built on `PackedCollection` for hardware acceleration and memory efficiency.

This module contains the core collection infrastructure that powers all numerical computations in the framework:

- **PackedCollection**: Hardware-accelerated multi-dimensional arrays with contiguous memory layout
- **CollectionProducer**: Lazy evaluation pattern for building computational graphs
- **CollectionFeatures**: Factory methods for creating computations
- **Parallel Processing**: GPU/CPU kernel compilation and execution

---

## Core Collection Classes

### PackedCollection - The Foundation

`PackedCollection<T>` is the fundamental data container providing efficient storage and access for multi-dimensional numerical data.

#### Key Features

- **Memory Efficiency**: Contiguous packed memory layout for cache-friendly access
- **Hardware Acceleration**: Direct GPU/CPU memory backing via `MemoryData`
- **Multi-dimensional Support**: Arbitrary rank tensors with `TraversalPolicy`
- **Zero-copy Views**: Memory delegation without data copying
- **Flexible Traversal**: Custom access patterns via `TraversalOrdering`

#### Memory Layout

Data is stored in row-major order (C-style) in a contiguous memory block:

```
Logical (3x4 matrix):    Memory layout:
[0  1  2  3]             [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
[4  5  6  7]
[8  9 10 11]
```

#### Construction Patterns

```java
// 1. Simple shape construction
PackedCollection<?> tensor = new PackedCollection<>(3, 4, 5);  // 3x4x5 tensor

// 2. With traversal policy
TraversalPolicy shape = new TraversalPolicy(10, 20);
PackedCollection<?> matrix = new PackedCollection<>(shape);

// 3. Copy constructor (deep copy)
PackedCollection<?> copy = new PackedCollection<>(original);

// 4. Zero-copy view into existing memory
MemoryData largeBuffer = ...;
PackedCollection<?> view = new PackedCollection<>(shape, 0, largeBuffer, 100);
```

#### Access Patterns

```java
PackedCollection<?> data = new PackedCollection<>(10, 5);

// Indexed access
data.setMem(0, 1.5);
double value = data.toDouble(0);

// Array access
data.set(0, new double[]{1.0, 2.0, 3.0, 4.0, 5.0});
double[] row = data.get(0).toArray();

// Streaming
double sum = data.doubleStream().sum();
data.forEach(row -> process(row));

// Position-based access
double val = data.valueAt(2, 3);  // Get element at position [2][3]
data.setValueAt(5.0, 2, 3);       // Set element at position [2][3]
```

#### Initialization Methods

```java
PackedCollection<?> data = new PackedCollection<>(100);

data.fill(0.0);                      // Fill with constant
data.randFill();                     // Fill with uniform random [0,1)
data.randnFill();                    // Fill with normal distribution
data.identityFill();                 // Fill as identity matrix (2D only)
data.fill(pos -> pos[0] * pos[1]);   // Fill with function
data.replace(x -> x * 2.0);          // Transform in-place
```

#### Zero-Copy Views and Delegation

PackedCollection can wrap existing memory without copying, enabling efficient memory reuse:

```java
// Create a large buffer
PackedCollection<?> largeBuffer = new PackedCollection<>(1000000);

// Create views into different regions (zero-copy)
PackedCollection<?> view1 = largeBuffer.range(shape(100, 100), 0);      // First 10000 elements
PackedCollection<?> view2 = largeBuffer.range(shape(100, 100), 10000);  // Next 10000 elements

// Changes to views modify the underlying buffer
view1.setMem(0, 5.0);  // largeBuffer now has 5.0 at position 0

// Repeat creates a virtual view (memory-efficient)
PackedCollection<?> repeated = data.repeat(4);  // Appears 4x larger, same memory

// Delegation chain
PackedCollection<?> subset = largeBuffer
    .range(shape(500, 500), 0)     // View into buffer
    .range(shape(100, 100), 0);    // View into view
```

#### Shape and Traversal

```java
PackedCollection<?> data = new PackedCollection<>(3, 4, 5);
TraversalPolicy shape = data.getShape();

shape.getDimensions();     // 3 (rank)
shape.length(0);           // 3 (first dimension size)
shape.length(1);           // 4 (second dimension size)
shape.length(2);           // 5 (third dimension size)
shape.getTotalSize();      // 60 (total elements)
shape.getTraversalAxis();  // 0 (default traversal axis)

// Reshape (same data, different interpretation)
PackedCollection<?> reshaped = data.reshape(shape(12, 5));

// Change traversal axis
PackedCollection<?> transposed = data.traverse(1);
```

#### I/O Operations

```java
// Save to file
data.save(new File("tensor.dat"));

// Load collections from file (returns Iterable for multiple collections)
for (PackedCollection<?> loaded : PackedCollection.loadCollections(new File("tensor.dat"))) {
    // Process each loaded collection
}

// Print for debugging
data.print();  // Pretty-printed to console
```

---

### CollectionProducer - Lazy Evaluation

`CollectionProducer<T>` is the core interface for building computational graphs through method chaining.

#### Design Principles

- **Immutable Operations**: All operations return new producers, never modifying the original
- **Deferred Execution**: Operations build a computational graph; computation happens on evaluation
- **Hardware Acceleration**: Computations compile to optimized kernels for CPU/GPU
- **Type Safety**: Shape information is tracked through the type system

#### Method Chaining Pattern

```java
// Build a computation graph through method chaining
CollectionProducer<?> input = v(PackedCollection.class);
CollectionProducer<?> result = input
    .reshape(shape(10, 3))      // Reshape to 10x3
    .subtract(input.mean(0))    // Subtract mean along axis 0
    .divide(input.variance(0))  // Divide by variance
    .pow(2.0)                   // Square all elements
    .sum();                     // Sum all elements

// Execute the graph
PackedCollection<?> output = result.get().evaluate();
```

#### Shape Operations

```java
CollectionProducer<?> x = ...; // shape (2, 3, 4)

x.reshape(shape(6, 4))           // Reshape to 6x4
x.traverse(1)                    // Traverse along axis 1
x.transpose()                    // Transpose matrix (2D only)
x.subset(shape(2, 2), 0, 1)      // Extract 2x2 subset starting at (0,1)
x.repeat(5)                      // Repeat data 5 times -> shape (10, 3, 4)
x.enumerate(10)                  // Extract 10 indexed elements
x.permute(2, 0, 1)               // Permute dimensions -> shape (4, 2, 3)
x.pad(1, 2, 3)                   // Add padding to each dimension
```

#### Arithmetic Operations

```java
CollectionProducer<?> a = ...;
CollectionProducer<?> b = ...;

a.add(b)           // Element-wise addition
a.add(5.0)         // Add scalar to all elements
a.subtract(b)      // Element-wise subtraction
a.multiply(b)      // Element-wise multiplication
a.divide(2.0)      // Divide all elements by 2
a.pow(2.0)         // Square all elements
a.sqrt()           // Square root
a.exp()            // e^x
a.log()            // ln(x)
a.abs()            // Absolute value
a.minus()          // Negation
```

#### Statistical Operations

```java
CollectionProducer<?> data = ...; // shape (10, 5)

data.sum()         // Sum all elements -> shape (1)
data.sum(0)        // Sum along axis 0 -> shape (5)
data.mean()        // Mean of all elements
data.mean(1)       // Mean along axis 1 -> shape (10)
data.variance()    // Variance
data.max()         // Maximum value
data.magnitude()   // L2 norm
data.indexOfMax()  // Index of maximum element
```

#### Comparison Operations

```java
CollectionProducer<?> x = ...;
CollectionProducer<?> y = ...;

x.greaterThan(y)                        // 1.0 where x > y, 0.0 elsewhere
x.lessThan(y)                           // 1.0 where x < y, 0.0 elsewhere
x.greaterThanOrEqual(y)                 // 1.0 where x >= y, 0.0 elsewhere
x.lessThanOrEqual(y)                    // 1.0 where x <= y, 0.0 elsewhere
x.and(y)                                // 1.0 where both non-zero, 0.0 elsewhere

// Conditional selection
x.greaterThan(y, trueVal, falseVal)     // Select based on comparison
```

#### Automatic Differentiation

```java
CollectionProducer<?> x = v(PackedCollection.class);
CollectionProducer<?> y = x.pow(2).sum();

// Compute dy/dx
CollectionProducer<?> gradient = y.delta(x);
// Result: 2x (derivative of x^2)

// Combine gradients for backpropagation
CollectionProducer<?> combined = y.grad(x, upstreamGradient);
```

---

### CollectionFeatures - Factory Methods

`CollectionFeatures` is a mixin interface providing 200+ factory methods for creating computations.

#### Usage Pattern

```java
public class MyComputation implements CollectionFeatures {
    public void example() {
        // All factory methods available directly
        CollectionProducer<?> data = c(10.0);
        CollectionProducer<?> result = add(data, c(5.0));
    }
}
```

#### Producer Creation

```java
// Variable producers (placeholders)
CollectionProducer<?> input = v(PackedCollection.class);

// Constant scalars
CollectionProducer<?> scalar = c(3.14);

// Constant collections
CollectionProducer<?> vector = c(1.0, 2.0, 3.0);

// Wrap existing collection
CollectionProducer<?> wrapped = p(myPackedCollection);
```

#### Shape Factory Methods

```java
// Create shapes
TraversalPolicy shape = shape(10, 20, 30);

// Extract shape from producer
TraversalPolicy prodShape = shape(myProducer);

// Traverse along axis
CollectionProducer<?> traversed = traverse(1, myProducer);

// Reshape
CollectionProducer<?> reshaped = reshape(shape(200, 30), myProducer);
```

#### Arithmetic Factory Methods

```java
add(a, b)              // a + b
subtract(a, b)         // a - b
multiply(a, b)         // a * b
divide(a, b)           // a / b
pow(a, b)              // a^b
sqrt(a)                // sqrt(a)
minus(a)               // -a
exp(a)                 // e^a
log(a)                 // ln(a)
abs(a)                 // |a|
sq(a)                  // a^2
sigmoid(a)             // 1 / (1 + e^-a)
mod(a, b)              // a mod b
```

#### Statistical Factory Methods

```java
sum(a)                 // Sum all elements
mean(a)                // Mean of all elements
variance(a)            // Variance
max(a)                 // Maximum value
indexOfMax(a)          // Index of maximum
magnitude(a)           // L2 norm
subtractMean(a)        // a - mean(a)
```

#### Constant Generators

```java
zeros(shape(10, 10))   // 10x10 zero-filled collection
epsilon()              // Machine epsilon constant
rand(shape(100))       // 100 uniform random values [0,1)
randn(shape(100))      // 100 normal random values
integers(0, 100)       // Sequence 0, 1, 2, ..., 99
```

---

### CollectionProducerComputation - Hardware Acceleration

`CollectionProducerComputation<T>` is the interface for computations that compile to hardware-accelerated kernels.

#### Lifecycle

```java
// 1. Create computation (builds graph)
CollectionProducerComputation<?> comp = myProducer.pow(2).sum();

// 2. Get evaluable (compiles to native code / GPU kernel)
Evaluable<?> ev = comp.get();

// 3. Execute (runs on hardware)
PackedCollection<?> result = ev.evaluate();

// Alternative: provide input
PackedCollection<?> result = ev.evaluate(inputData);

// Reuse evaluable for multiple executions
for (PackedCollection<?> input : inputs) {
    PackedCollection<?> output = ev.evaluate(input);
}
```

#### Key Features

- **Automatic Compilation**: Computations are compiled to optimized kernels on first `get()` call
- **Kernel Caching**: Compiled kernels are cached for reuse
- **Memory Management**: Automatic allocation and deallocation of GPU memory
- **Shape Propagation**: Output shapes are computed from input shapes

---

### CollectionProducerParallelProcess - Parallel Execution

`CollectionProducerParallelProcess<T>` enables parallel execution on CPU/GPU.

#### Capabilities

- Multi-threaded CPU execution
- GPU kernel compilation and execution
- Operation fusion for optimization
- Memory-efficient batch processing

---

### DelegatedCollectionProducer - Zero-Copy Delegation

`DelegatedCollectionProducer<T>` provides a lightweight wrapper pattern for producers.

#### Use Cases

- Adding metadata or tracking to existing producers
- Creating proxy patterns for collection operations
- Modifying specific behaviors while preserving functionality
- Process isolation for independent execution contexts

```java
// Wrap an existing producer
DelegatedCollectionProducer<?> delegated = new DelegatedCollectionProducer<>(originalProducer);

// Shape is forwarded from wrapped producer
TraversalPolicy shape = delegated.getShape();

// Evaluation is delegated
PackedCollection<?> result = delegated.get().evaluate();
```

---

## Algebraic Types

### Vector (3D Vector)

```java
Vector v = new Vector(1.0, 2.0, 3.0);
double x = v.getX();
Vector sum = v1.add(v2);
double dot = v1.dotProduct(v2);
Vector cross = v1.crossProduct(v2);
```

### Scalar (Value + Certainty)

```java
Scalar s = new Scalar(5.0, 0.95);  // Value with 95% certainty
double value = s.getValue();
```

### Pair (Two-Element Tuple)

```java
Pair p = new Pair(3.0, 4.0);
double x = p.getX();
double y = p.getY();
```

---

## Operations

All operations return `CollectionProducer` for GPU compilation:

```java
CollectionProducer<Vector> normalized = normalize(v);
CollectionProducer<?> dot = dotProduct(a, b);
CollectionProducer<?> matrix = matmul(A, B);
```

---

## Hardware Acceleration Setup

Before running any code that uses hardware acceleration:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
```

See [CLAUDE.md](../CLAUDE.md) for detailed setup instructions.

---

## Thread Safety

`PackedCollection` is **not thread-safe**. External synchronization is required for concurrent access. For parallel operations, use the computation framework which handles synchronization internally.

---

## Best Practices

### Memory Efficiency

```java
// GOOD: Use zero-copy views
PackedCollection<?> view = buffer.range(shape(100), offset);

// GOOD: Reuse evaluables
Evaluable<?> ev = computation.get();
for (int i = 0; i < 1000; i++) {
    ev.evaluate(inputs[i]);
}

// AVOID: Creating unnecessary copies
PackedCollection<?> copy = new PackedCollection<>(original); // Copies all data
```

### Building Computations

```java
// GOOD: Chain operations (builds efficient graph)
CollectionProducer<?> result = input
    .subtract(input.mean())
    .divide(input.variance().sqrt());

// AVOID: Intermediate evaluations (breaks optimization)
PackedCollection<?> centered = input.subtract(input.mean()).get().evaluate();
PackedCollection<?> result = p(centered).divide(variance).get().evaluate();
```

### Shape Management

```java
// GOOD: Use shape inference
TraversalPolicy inputShape = shape(input);
TraversalPolicy outputShape = inputShape.prependDimension(batchSize);

// GOOD: Validate shapes early
if (a.getShape().getTotalSize() != b.getShape().getTotalSize()) {
    throw new IllegalArgumentException("Shape mismatch");
}
```

---

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-algebra</artifactId>
    <version>0.72</version>
</dependency>
```

---

## Related Documentation

- [CLAUDE.md](../CLAUDE.md) - Development guidelines and hardware setup
- [ML Module](../ml/claude.md) - Machine learning models and layers
- [Graph Module](../graph/README.md) - Computation graph and layers
