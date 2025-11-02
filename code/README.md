# Code Module

This module provides core code generation, expression building, and traversal abstractions for the Almost Realism computational framework.

## Key Concepts

### TraversalPolicy

`TraversalPolicy` defines how a sequence of elements should be traversed to form a multidimensional collection. It specifies dimensions and traversal rates for transforming between output space (collection indices) and input space (natural element order).

#### Fixed vs Variable Count

`TraversalPolicy` supports two modes of operation:

**Fixed-Count (default)**:
```java
// Always processes exactly 100 elements
TraversalPolicy fixed = new TraversalPolicy(100);

// Multi-dimensional: 10x20 matrix, always 200 elements
TraversalPolicy matrix = new TraversalPolicy(10, 20);
```
- Dimensions are predetermined
- Size cannot change at runtime
- Most efficient for known-size data structures
- Created with: `new TraversalPolicy(dims...)`

**Variable-Count**:
```java
// Processes N elements where N is determined at runtime
TraversalPolicy variable = new TraversalPolicy(false, false, 1);

// Multi-dimensional with variable size
TraversalPolicy variableMatrix = new TraversalPolicy(false, false, 10, 20);
```
- Dimensions can adapt to runtime input sizes
- Enables flexible operations on varying-sized collections
- Created with: `new TraversalPolicy(false, false, dims...)`
  - First `false`: `tolerateZero` - allows zero-sized dimensions
  - Second `false`: `fixed` - makes count variable

#### Constructor Patterns

```java
// Fixed-count constructors (fixed = true by default)
new TraversalPolicy(int... dims)
new TraversalPolicy(long... dims)
new TraversalPolicy(TraversalOrdering order, int... dims)

// Variable-count constructor
new TraversalPolicy(boolean tolerateZero, boolean fixed, long... dims)

// Example: Variable-count 3D collection
new TraversalPolicy(false, false, 10, 20, 30)
```

#### Usage in PassThroughProducer

`TraversalPolicy` is commonly used with `PassThroughProducer` to define input argument shapes:

```java
// Fixed: Always expects a 3-element vector
Input.value(new TraversalPolicy(3), 0)

// Variable: Adapts to actual input size
Input.value(new TraversalPolicy(false, false, 1), 0)
```

## Common Patterns

### Creating Collection Shapes

```java
// 1D vector (100 elements, fixed)
shape(100)

// 2D matrix (10 rows × 20 columns, fixed)
shape(10, 20)

// Variable-size 1D collection
new TraversalPolicy(false, false, 1)

// Variable-size 2D collection
new TraversalPolicy(false, false, new long[]{10, 20})
```

### Reshape Operations

```java
TraversalPolicy original = shape(100);
TraversalPolicy reshaped = original.reshape(10, 10);  // 100 elements as 10×10 matrix
```

### Traversal

```java
TraversalPolicy matrix = shape(10, 20);  // 10 rows × 20 columns
TraversalPolicy row = matrix.traverse(0);  // Traverse along first axis (rows)
```

## See Also

- `Countable` (relation module) - Interface implemented by `TraversalPolicy`
- `PassThroughProducer` (hardware module) - Uses `TraversalPolicy` for argument shapes
- `CollectionFeatures` - Provides `shape()` helper methods
