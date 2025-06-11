# EpsilonConstantComputation Usage Examples

This document provides practical usage examples for the `EpsilonConstantComputation` class, demonstrating its capabilities for machine epsilon-based computations within the AlmostRealism computation framework.

## Basic Usage

### Creating Epsilon Constant Collections

```java
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.EpsilonConstantComputation;

// Create a 3x3 matrix filled with machine epsilon values
TraversalPolicy shape = new TraversalPolicy(3, 3);
EpsilonConstantComputation<PackedCollection> epsilon = 
    new EpsilonConstantComputation<>(shape);

// Evaluate to get the actual collection
PackedCollection result = epsilon.get().evaluate();
// result will be a 3x3 collection where every element is machine epsilon
```

### Vector and Higher-Dimensional Examples

```java
// Create an epsilon vector
EpsilonConstantComputation<PackedCollection> epsilonVector = 
    new EpsilonConstantComputation<>(new TraversalPolicy(10));

// Create an epsilon tensor (3D)
EpsilonConstantComputation<PackedCollection> epsilonTensor = 
    new EpsilonConstantComputation<>(new TraversalPolicy(2, 3, 4));
```

## Primary Use Case: Floating-Point Computations

### Epsilon Constants in Conditional Logic

```java
import org.almostrealism.collect.CollectionFeatures;

// Create epsilon constants for use in conditional computations
TraversalPolicy shape = new TraversalPolicy(10);
CollectionProducer<PackedCollection> a = ...; // some computation
CollectionProducer<PackedCollection> b = ...; // another computation

// Use EpsilonConstantComputation as return value in conditional operations
CollectionProducer<PackedCollection> result = 
    equals(a, b, 
           new EpsilonConstantComputation<>(shape),     // return epsilon if equal
           add(a, minus(b)));                           // return difference if not equal

// When a equals b exactly, result contains epsilon values
// When a does not equal b, result contains the actual difference
```

### Numerical Stability in Subtraction

```java
// Use subtractIgnoreZero for epsilon-aware subtraction
CollectionProducer<PackedCollection> a = ...; // some computation
CollectionProducer<PackedCollection> b = ...; // another computation

// This automatically uses EpsilonConstantComputation internally
CollectionProducerComputation<PackedCollection> result = subtractIgnoreZero(a, b);
// When a ≈ b (within epsilon), preserves the original value instead of computing near-zero
```

## Shape Transformations

### Reshaping Epsilon Collections

```java
// Start with a matrix of epsilon values
EpsilonConstantComputation<PackedCollection> matrixEpsilon = 
    new EpsilonConstantComputation<>(new TraversalPolicy(3, 4));

// Reshape to a vector while preserving epsilon semantics
EpsilonConstantComputation<PackedCollection> vectorEpsilon = 
    (EpsilonConstantComputation<PackedCollection>) matrixEpsilon.reshape(new TraversalPolicy(12));

// Both computations produce epsilon values, just with different layouts
```

### Traversal Operations

```java
// Create epsilon values for a 2D collection
EpsilonConstantComputation<PackedCollection> original = 
    new EpsilonConstantComputation<>(new TraversalPolicy(4, 5));

// Traverse along axis 0 for axis-specific epsilon values
EpsilonConstantComputation<PackedCollection> traversed = 
    (EpsilonConstantComputation<PackedCollection>) original.traverse(0);

// The result maintains epsilon semantics with transformed shape
```

## Integration Patterns

### Using with CollectionFeatures

```java
import org.almostrealism.collect.CollectionFeatures;

// Epsilon collections integrate seamlessly with other operations
TraversalPolicy shape = new TraversalPolicy(5, 5);
EpsilonConstantComputation<PackedCollection> epsilon = 
    new EpsilonConstantComputation<>(shape);

// Use in conditional operations
CollectionProducer<PackedCollection> data = ...; // some data
CollectionProducer<PackedCollection> conditionalResult = 
    greaterThan(data, epsilon, data, zeros(shape));
// Returns data where data > epsilon, zeros elsewhere
```

## Machine Epsilon Behavior

### Runtime vs Compiled Behavior

```java
// In test environments
EpsilonConstantComputation<PackedCollection> epsilon = 
    new EpsilonConstantComputation<>(new TraversalPolicy(3));

// epsilon.getConstantValue() returns 0.0 for compatibility
// But epsilon.getExpression() uses actual machine epsilon in compiled code
```

### Precision Awareness

```java
// EpsilonConstantComputation works with the Precision enum to determine epsilon values
// The Precision class defines three floating-point precisions:

// FP16 (half precision): epsilon = 1e-4 (or 0.0009765625 strict)
// FP32 (single precision): epsilon = 1e-5 (or 1.1920928955078125e-7 strict)  
// FP64 (double precision): epsilon = 1e-7 (or 2.220446049250313e-16 strict)

// Precision.epsilon() provides the appropriate epsilon value:
// Precision.FP32.epsilon() -> 1e-5
// Precision.FP64.epsilon() -> 1e-7
// Precision.FP16.epsilon() -> 1e-4

// The actual epsilon used depends on the compilation context and target precision
```

## Performance Considerations

1. **Memory Efficiency**: EpsilonConstantComputation doesn't store arrays of epsilon values, it generates them as needed
2. **Computation Optimization**: Inherits optimizations from SingleConstantComputation
3. **Short-Circuit Evaluation**: Provides direct evaluation paths for better performance
4. **Parallel Processing**: Efficiently handles large-scale epsilon computations

## Best Practices

1. **Use for Floating-Point Comparisons**: Primary use case is tolerance-based equality testing
2. **Leverage in Numerical Algorithms**: Essential for maintaining numerical stability
3. **Shape Consistency**: Ensure epsilon collections match the shape of operands in comparisons
4. **Understand Dual Behavior**: Remember that test and compiled behavior may differ
5. **Combine with CollectionFeatures**: Use with built-in methods like `equals()` and `subtractIgnoreZero()`

## Common Patterns

### Tolerance-Based Operations

```java
// Pattern: Create epsilon tolerance for specific operations
TraversalPolicy operandShape = a.getShape();
CollectionProducer<PackedCollection> tolerance = 
    new EpsilonConstantComputation<>(operandShape);

// Use in various direct comparisons
CollectionProducer<PackedCollection> comparison = 
    lessThan(abs(subtract(a, b)), tolerance, c(1.0), c(0.0));
```