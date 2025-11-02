# Relation Module

This module provides core abstractions for producers, evaluables, and countable objects in the Almost Realism computational framework.

## Key Concepts

### Countable

The `Countable` interface represents data structures or computations with a countable number of elements.

#### Fixed vs Variable Count

Understanding the distinction between fixed and variable counts is critical for GPU kernel execution:

**Fixed Count (`isFixedCount() == true`)**:
- Number of elements is known at construction time
- Cannot change at runtime
- Kernel compiles with predetermined size
- Examples:
  - `PackedCollection` with specific size
  - Computation that always produces exactly N outputs
  - `new TraversalPolicy(100)` - always 100 elements

**Variable Count (`isFixedCount() == false`)**:
- Number of elements depends on runtime inputs
- Adapts to the size of input arguments
- Kernel size determined at runtime from output or argument sizes
- Examples:
  - Computation processing each element of an input collection
  - `new TraversalPolicy(false, false, 1)` - size matches runtime input

#### Kernel Execution Impact

When `ProcessDetailsFactory` initializes a kernel:

```java
if (isFixedCount()) {
    kernelSize = getCount();
    // Output must be size 1 or exactly match operation count
    if (output != null && !List.of(1L, getCountLong()).contains(output.getCountLong())) {
        throw new IllegalArgumentException();  // Size mismatch!
    }
} else {
    // Variable count: kernel size adapts to output size
    kernelSize = output.getCountLong();
}
```

## Usage Guidelines

1. **Use fixed count** when:
   - Working with known-size data structures (vectors, fixed-size matrices)
   - Output size is predetermined
   - Performance is critical (avoids runtime size checks)

2. **Use variable count** when:
   - Processing collections of varying sizes
   - Building generic operations that adapt to input sizes
   - Output size depends on runtime arguments

## See Also

- `TraversalPolicy` (code module) - Implements `Countable` for multidimensional collections
- `PassThroughProducer` (hardware module) - Uses `Countable` for input argument handling
- `ProcessDetailsFactory` (hardware module) - Kernel initialization logic
