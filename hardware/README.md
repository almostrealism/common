# Hardware Module

This module provides hardware acceleration abstractions for executing computations on CPU and GPU.

## Key Concepts

### PassThroughProducer

`PassThroughProducer` represents an input argument being passed through a computation without transformation. It's essential for building computation graphs that reference input arguments.

#### Fixed vs Variable Count

The behavior of `PassThroughProducer` is determined by its `TraversalPolicy`:

**Fixed-count** (default):
```java
// Creates a pass-through for a 3-element vector
Producer<Vector> input = Input.value(3, 0);
```
- Dimensions are predetermined at creation time
- Kernel size is fixed
- Output must be size 1 or exactly match the operation count
- Use for: Known-size inputs like vectors, matrices with fixed dimensions

**Variable-count**:
```java
// Creates a pass-through that adapts to input argument size
Producer<T> input = Input.value(new TraversalPolicy(false, false, 1), 0);
```
- Dimensions adapt to runtime input sizes
- Kernel size determined from output at runtime
- Flexible for variable-sized collections
- Use for: Operations that process collections of varying sizes

#### Common Pitfall

Using a fixed-count `PassThroughProducer` with mismatched output size will cause:
```
IllegalArgumentException at ProcessDetailsFactory.init()
```

**Solution:** Use variable-count `TraversalPolicy` when processing variable-sized inputs.

## See Also

- `TraversalPolicy` (code module) - Defines traversal dimensions
- `Countable` (relation module) - Interface for countable objects
- `ProcessDetailsFactory` - Kernel initialization logic
