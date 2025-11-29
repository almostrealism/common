# AR-Collect Module

**Core data structures and operations for hardware-accelerated multi-dimensional collections.**

## Overview

The `ar-collect` module provides `PackedCollection`, the fundamental data structure for storing and operating on multi-dimensional numerical data in the Almost Realism framework. All data - from vectors and matrices to tensors and model weights - is built on PackedCollection.

## Core Concept: PackedCollection

### What is PackedCollection?

`PackedCollection` is a memory-efficient data structure that:
- Stores multi-dimensional numerical data as a flat array
- Uses `TraversalPolicy` to interpret linear memory as N-dimensional tensors
- Integrates seamlessly with hardware acceleration (GPU/CPU)
- Enables zero-copy views through delegation
- Supports 40+ operations via `CollectionProducer`

### Basic Usage

```java
// Create a 1D collection (vector)
PackedCollection vector = new PackedCollection<>(3);
vector.setMem(0, 1.0);
vector.setMem(1, 2.0);
vector.setMem(2, 3.0);

// Create a 2D collection (matrix)
PackedCollection matrix = new PackedCollection<>(shape(4, 3));  // 4 rows, 3 cols
matrix.setMem(0, value);  // Linear index

// Create from shape
TraversalPolicy shape = shape(10, 20, 30);  // 10x20x30 tensor
PackedCollection tensor = new PackedCollection<>(shape);
```

### Memory Layout

PackedCollection stores data in **row-major order**:
```
3D tensor [2, 3, 4] → Linear memory:
[0,0,0] [0,0,1] [0,0,2] [0,0,3]  [0,1,0] [0,1,1] ...
   0       1       2       3        4       5     ...
```

## CollectionProducer - Lazy Computation Model

### The Pattern

`CollectionProducer` describes computations **before execution**:

```java
// Build computation graph (no execution yet!)
CollectionProducer a = cp(vectorA);
CollectionProducer b = cp(vectorB);
CollectionProducer result = a.add(b).multiply(2.0);

// Execute when ready
PackedCollection computed = result.get().evaluate();
```

### 40+ Operations

**Arithmetic:**
```java
add(Producer<T> other)
subtract(Producer<T> other)
multiply(Producer<T> other)
divide(Producer<T> other)
multiply(double scalar)
```

**Aggregations:**
```java
sum()           // Sum all elements
mean()          // Average value
max()           // Maximum value
min()           // Minimum value
enumerate()     // Index positions
```

**Shape Operations:**
```java
reshape(TraversalPolicy newShape)
transpose()     // Swap dimensions
repeat(int times)
pad(int count)
concat(Producer<T>... others)
subset(TraversalPolicy shape, int... indices)
```

**Advanced:**
```java
map(Function<Double, Double> fn)
reduce(CollectionProducer initial, BinaryOperator<T> op)
traverse(int axis)
consolidate()
```

### Computation Chaining

```java
CollectionProducer pipeline = input
    .reshape(shape(batch, features))
    .subtract(mean)
    .divide(stddev)
    .multiply(weight)
    .add(bias)
    .relu();

PackedCollection output = pipeline.get().evaluate();
```

## TraversalPolicy - Multi-Dimensional Indexing

### What is TraversalPolicy?

Maps 1D physical memory to N-D logical space:

```java
// 2D matrix: 3 rows × 4 columns
TraversalPolicy shape = shape(3, 4);

// Access element at [row=1, col=2]
int linearIndex = shape.index(1, 2);  // Returns 6
```

### Shape Construction

```java
// 1D vector
shape(10)

// 2D matrix
shape(rows, cols)

// 3D tensor
shape(depth, height, width)

// 4D batch
shape(batch, channels, height, width)
```

### Fixed vs Variable Count

**Fixed Count** (size known at compile-time):
```java
TraversalPolicy fixed = shape(100, 50);
fixed.isFixedCount();  // true
fixed.getCountLong();  // 5000 (100 × 50)
```

**Variable Count** (size determined at runtime):
```java
TraversalPolicy variable = shape(false, false, 1);
variable.isFixedCount();  // false
// Size adapts to input at runtime
```

**Impact on GPU compilation:**
- Fixed → Compile specialized kernel with known size
- Variable → Compile adaptive kernel

## Hardware Acceleration Integration

### The 5-Layer Architecture

```
Application Code
       ↓
CollectionProducer (lazy computation description)
       ↓
Computation (operation implementation)
       ↓
Hardware Layer (kernel compilation)
       ↓
Memory (GPU/CPU buffers)
```

### Environment Setup

**Required environment variables:**
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native  # or opencl, metal
```

### Supported Backends

- **native** - JNI with runtime-generated native code
- **opencl** - OpenCL acceleration (CPU/GPU)
- **metal** - Metal GPU acceleration (Apple Silicon)
- **external** - Generated executable approach

### Hardware Workflow

```java
// 1. Describe computation (CPU)
CollectionProducer op = a.add(b).multiply(c);

// 2. Compile to kernel (one-time)
Evaluable<?> kernel = op.get();  // Generates GPU code

// 3. Execute on GPU
PackedCollection result = kernel.evaluate();
```

## Memory Management

### Three-Level Architecture

```
MemoryData (logical region)
     ↓
Memory (physical buffer)
     ↓
MemoryProvider (allocation strategy)
```

### MemoryBank Pattern

`PackedCollection` implements `MemoryBank<T>`:

```java
public interface MemoryBank<T extends MemoryData> {
    int getCount();              // Number of items
    T get(int index);            // Access item
    void set(int index, T value);
    MemoryData flatten();        // Get backing memory
}
```

### Zero-Copy Views

Create views without copying data:

```java
PackedCollection original = new PackedCollection<>(shape(100, 50));

// Create view (no data copy!)
PackedCollection view = original.range(shape(10, 50));
view.setMem(0, 5.0);  // Modifies original!
```

### Delegation Pattern

```java
// Share underlying memory
MemoryData sharedMemory = original.getDelegate();
PackedCollection alias = new PackedCollection<>(
    shape(50, 100),
    sharedMemory,
    offset
);
```

## Common Patterns

### Loading Model Weights

```java
// From StateDictionary (ML module)
StateDictionary weights = new StateDictionary(weightsDir);
PackedCollection embedding = weights.get("model.embed.weight");

// Shape info
TraversalPolicy shape = embedding.getShape();  // e.g., [vocab_size, hidden_dim]
int vocabSize = shape.length(0);
int hiddenDim = shape.length(1);
```

### Batched Operations

```java
// Process batch of vectors
PackedCollection batch = new PackedCollection<>(shape(batchSize, vectorDim));

// Load data
for (int i = 0; i < batchSize; i++) {
    batch.set(i, loadVector(i));
}

// Batch operation (single GPU kernel!)
CollectionProducer normalized = cp(batch).divide(cp(batch).max());
PackedCollection result = normalized.get().evaluate();
```

### Matrix Operations

```java
// Matrix multiplication
PackedCollection A = new PackedCollection<>(shape(m, k));
PackedCollection B = new PackedCollection<>(shape(k, n));

CollectionProducer C = matmul(cp(A), cp(B));
PackedCollection result = C.get().evaluate();  // Shape: [m, n]
```

### Reshaping

```java
// Flatten 2D to 1D
PackedCollection matrix = new PackedCollection<>(shape(10, 20));
CollectionProducer flattened = cp(matrix).reshape(shape(200));

// Reshape to 3D
CollectionProducer tensor = flattened.reshape(shape(4, 5, 10));
```

### Traversal and Subsetting

```java
// Select specific indices
CollectionProducer subset = cp(data).subset(
    shape(selectedCount, features),
    source,
    indices...
);

// Traverse along axis
CollectionProducer traversed = cp(data).traverse(axis);
```

## Integration with Framework

### Used By

- **algebra** - Vector, Pair all extend PackedCollection
- **ml** - Model weights, activations, gradients
- **graph** - Neural network computations
- **time** - AcceleratedTimeSeries stores TemporalScalar collections
- **space** - Vertex buffers, geometric data

### Enables

- **Type Safety** - Vector, Matrix as type-safe wrappers
- **Hardware Acceleration** - Automatic GPU compilation
- **Memory Efficiency** - Zero-copy views, shared buffers
- **Functional Programming** - CollectionProducer chains

## Performance Tips

1. **Reuse Evaluables** - Compile once, execute many times
   ```java
   Evaluable<?> compiled = operation.get();  // Expensive
   for (int i = 0; i < 1000; i++) {
       compiled.evaluate(inputs[i]);  // Fast
   }
   ```

2. **Batch Operations** - Process multiple items in one kernel
   ```java
   // Bad: 100 separate kernels
   for (int i = 0; i < 100; i++) {
       result[i] = process(item[i]).get().evaluate();
   }

   // Good: 1 kernel for all 100
   PackedCollection batch = PackedCollection.bank(100);
   result = process(batch).get().evaluate();
   ```

3. **Avoid Unnecessary Copies** - Use delegation
   ```java
   // Bad: Copies data
   PackedCollection copy = new PackedCollection<>(original.getShape());
   copy.setMem(original);

   // Good: Zero-copy view
   PackedCollection view = original.range(shape);
   ```

4. **Choose Fixed Count** - When possible for performance
   ```java
   // Fixed count: faster compilation
   shape(100, 50)

   // Variable count: more flexible but slower
   shape(false, false, 1)
   ```

## Troubleshooting

### Common Issues

**NoClassDefFoundError: PackedCollection**
- Missing environment variables: Set `AR_HARDWARE_LIBS` and `AR_HARDWARE_DRIVER`

**Shape mismatch errors**
- Check TraversalPolicy dimensions match data
- Verify batch dimensions align

**Out of memory**
- Reduce batch size
- Clear unused PackedCollections
- Check for memory leaks in delegation

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-collect</artifactId>
    <version>0.72</version>
</dependency>
```

## License

Licensed under the Apache License, Version 2.0.
