# CollectionZerosComputation Usage Examples

This document provides comprehensive examples of using `CollectionZerosComputation` throughout the AlmostRealism computation framework. The examples demonstrate practical usage patterns, optimization opportunities, and integration with other components.

## Basic Usage

### Creating Zero Collections

```java
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionZerosComputation;

// Create a zero vector
TraversalPolicy vectorShape = new TraversalPolicy(5);
CollectionZerosComputation<PackedCollection> zeroVector = 
    new CollectionZerosComputation<>(vectorShape);

// Evaluate to get the actual collection
PackedCollection result = zeroVector.get().evaluate();
// result: [0.0, 0.0, 0.0, 0.0, 0.0]

// Create a zero matrix
TraversalPolicy matrixShape = new TraversalPolicy(3, 4);
CollectionZerosComputation<PackedCollection> zeroMatrix = 
    new CollectionZerosComputation<>(matrixShape);
PackedCollection matrix = zeroMatrix.get().evaluate();
// result: 3x4 matrix filled with zeros

// Create a 3D tensor of zeros
TraversalPolicy tensorShape = new TraversalPolicy(2, 3, 4);
CollectionZerosComputation<PackedCollection> zeroTensor = 
    new CollectionZerosComputation<>(tensorShape);
// result: 2x3x4 tensor with all 24 elements as 0.0
```

### Using with CollectionFeatures

```java
import static org.almostrealism.collect.CollectionFeatures.*;

// Create zero collections using the convenience method
CollectionProducer<PackedCollection<?>> zeros1D = zeros(shape(10));
CollectionProducer<PackedCollection<?>> zeros2D = zeros(shape(5, 6));
CollectionProducer<PackedCollection<?>> zeros3D = zeros(shape(2, 3, 4));

// These are equivalent to direct construction
CollectionZerosComputation<PackedCollection<?>> direct1D = 
    new CollectionZerosComputation<>(new TraversalPolicy(10));
```

## Shape Operations

### Reshaping Zero Collections

```java
// Start with a zero vector
CollectionZerosComputation<PackedCollection> originalZeros = 
    new CollectionZerosComputation<>(new TraversalPolicy(12));

// Reshape to different dimensions while preserving zero content
CollectionProducerComputation<PackedCollection> matrix = 
    originalZeros.reshape(new TraversalPolicy(3, 4));
// Result: 3x4 matrix of zeros

CollectionProducerComputation<PackedCollection> tensor = 
    originalZeros.reshape(new TraversalPolicy(2, 2, 3));
// Result: 2x2x3 tensor of zeros

// All reshape operations maintain the zero property
assert matrix.isZero();
assert tensor.isZero();
```

### Traversal Operations

```java
// Create a 2D zero matrix
CollectionZerosComputation<PackedCollection> zeroMatrix = 
    new CollectionZerosComputation<>(new TraversalPolicy(4, 5));

// Traverse along different axes
CollectionProducer<PackedCollection> traversed1 = zeroMatrix.traverse(0);
CollectionProducer<PackedCollection> traversed2 = zeroMatrix.traverse(1);

// Traversed results are still zero collections
assert traversed1.isZero();
assert traversed2.isZero();
```

## Optimization Features

### Zero Detection in Arithmetic Operations

```java
// Zero multiplication optimization
CollectionProducer<PackedCollection<?>> someVector = c(1.0, 2.0, 3.0);
CollectionProducer<PackedCollection<?>> zeros = zeros(shape(3));

// This gets optimized to return zeros directly
CollectionProducer<PackedCollection<?>> product = multiply(someVector, zeros);
// Result: Optimized to zeros(shape(3)) without actual multiplication

// Zero addition optimization  
CollectionProducer<PackedCollection<?>> sum = add(someVector, zeros);
// Result: Optimized to return someVector directly (additive identity)
```

### Sum of Zeros

```java
CollectionProducer<PackedCollection<?>> zeros = zeros(shape(100));
CollectionProducer<PackedCollection<?>> sumResult = sum(zeros);
// Result: Optimized to zeros(shape(1)) - sum of zeros is zero
```

### Conditional Operations

```java
CollectionProducer<PackedCollection<?>> condition = someCondition;
CollectionProducer<PackedCollection<?>> zeros = zeros(shape(5));
CollectionProducer<PackedCollection<?>> nonZeros = c(1.0, 2.0, 3.0, 4.0, 5.0);

// Conditional selection involving zeros
CollectionProducer<PackedCollection<?>> result = 
    conditional(condition, nonZeros, zeros);
// When condition is false, efficiently returns zeros
```

## Calculus and Derivatives

### Delta (Derivative) Computations

```java
// Zero function derivatives
CollectionZerosComputation<PackedCollection> zeroFunction = 
    new CollectionZerosComputation<>(new TraversalPolicy(3));

// Target variable for differentiation
Producer<?> variable = someVariable; // shape [2]

// Compute derivative: d/dx(0) = 0
CollectionProducer<PackedCollection> derivative = zeroFunction.delta(variable);
// Result: Zero collection with expanded shape [3, 2]

assert derivative.isZero();
```

### Gradient Computations

```java
// In machine learning contexts, zero gradients are common
CollectionProducer<PackedCollection<?>> lossGradient = zeros(shape(modelParams));
// Represents no change needed in parameters

// Chain rule with zero gradients
CollectionProducer<PackedCollection<?>> chainedGradient = 
    multiply(upstreamGradient, lossGradient);
// Result: Optimized to zeros due to multiplication by zero
```

## Integration Patterns

### Custom Computations

```java
// Using zeros as initialization values
public class MyNeuralLayer extends CollectionProducerComputation<PackedCollection> {
    public MyNeuralLayer(TraversalPolicy inputShape, TraversalPolicy outputShape) {
        super(outputShape);
        
        // Initialize bias terms to zero
        this.biases = new CollectionZerosComputation<>(outputShape);
        
        // Initialize weights with small random values
        this.weights = new Random(inputShape.append(outputShape));
    }
}
```

### Pipeline Integration

```java
// CollectionZerosComputation integrates seamlessly with computation pipelines
CollectionProducer<PackedCollection<?>> pipeline = 
    zeros(shape(3, 3))              // Start with zero matrix
        .add(someTransformation)     // Add actual data
        .multiply(scalingFactor)     // Scale the result
        .traverse(1);               // Transform dimensions

// Optimizations occur automatically:
// - If someTransformation is also zero, the add becomes zero
// - If scalingFactor is zero, the multiply becomes zero
```

### Sparse Data Representation

```java
// Efficient sparse matrix operations
public CollectionProducer<PackedCollection<?>> createSparseMatrix(
        int rows, int cols, Map<Integer, Double> nonZeroEntries) {
    
    if (nonZeroEntries.isEmpty()) {
        // Completely sparse (all zeros) - use optimized zero computation
        return zeros(shape(rows, cols));
    }
    
    // Start with zeros and add non-zero entries
    CollectionProducer<PackedCollection<?>> result = zeros(shape(rows, cols));
    
    for (Map.Entry<Integer, Double> entry : nonZeroEntries.entrySet()) {
        // Add non-zero values at specific positions
        result = add(result, createSingletonAt(entry.getKey(), entry.getValue()));
    }
    
    return result;
}
```

## Performance Considerations

### Memory Efficiency

```java
// CollectionZerosComputation is extremely memory efficient
CollectionZerosComputation<PackedCollection> hugeZeros = 
    new CollectionZerosComputation<>(new TraversalPolicy(1000000));
// Uses minimal memory regardless of size - no actual zero storage

// Compare with creating actual zero-filled collection
PackedCollection actualZeros = new PackedCollection(new TraversalPolicy(1000000));
actualZeros.fill(0.0);
// Uses 8MB of memory (1M doubles × 8 bytes each)
```

### Computation Optimization

```java
// Mathematical operations are optimized at construction time
CollectionProducer<PackedCollection<?>> zeros = zeros(shape(1000, 1000));
CollectionProducer<PackedCollection<?>> expensive = someExpensiveComputation();

// This multiplication is optimized away - no expensive computation needed
CollectionProducer<PackedCollection<?>> result = multiply(expensive, zeros);
// Result: Returns zeros(shape(1000, 1000)) without evaluating expensive
```

### Kernel Compilation Avoidance

```java
// Zero computations can bypass kernel compilation for pure-zero operations
CollectionZerosComputation<PackedCollection> zeros = 
    new CollectionZerosComputation<>(new TraversalPolicy(10000));

// Direct evaluation without GPU kernel compilation
PackedCollection result = zeros.get().evaluate();
// Efficiently fills result with zeros using CPU operations
```

## Advanced Usage Patterns

### Broadcasting and Dimension Alignment

```java
// Zero collections automatically handle broadcasting
CollectionProducer<PackedCollection<?>> vector = c(1.0, 2.0, 3.0);        // shape [3]
CollectionProducer<PackedCollection<?>> zeroMatrix = zeros(shape(4, 3));   // shape [4,3]

// Broadcasting zero addition (optimized to return vector broadcast to [4,3])
CollectionProducer<PackedCollection<?>> broadcast = add(vector, zeroMatrix);
```

### Accumulation Patterns

```java
// Efficient accumulation starting from zeros
CollectionProducer<PackedCollection<?>> accumulator = zeros(shape(100));

for (CollectionProducer<PackedCollection<?>> term : terms) {
    accumulator = add(accumulator, term);
}
// First addition optimized: add(zeros, firstTerm) → firstTerm
```

### Conditional Initialization

```java
// Initialize collections based on conditions
public CollectionProducer<PackedCollection<?>> initializeWeights(boolean usePretrainedWeights) {
    if (usePretrainedWeights) {
        return loadPretrainedWeights();
    } else {
        // Start with zeros for training from scratch
        return zeros(getWeightShape());
    }
}
```

## Testing and Debugging

### Verification Patterns

```java
// Verify zero properties in tests
@Test
public void testOptimization() {
    CollectionProducer<PackedCollection<?>> result = 
        multiply(someComputation(), zeros(shape(10)));
    
    // Verify optimization occurred
    assertTrue("Multiplication by zero should yield zero", result.isZero());
    assertTrue("Should be optimized to CollectionZerosComputation", 
               result instanceof CollectionZerosComputation);
}
```

### Debugging Zero Propagation

```java
// Track where zeros originate in complex computations
public class DebugZeroComputation extends CollectionZerosComputation<PackedCollection<?>> {
    private final String debugInfo;
    
    public DebugZeroComputation(TraversalPolicy shape, String debugInfo) {
        super(shape);
        this.debugInfo = debugInfo;
        System.out.println("Zero computation created: " + debugInfo);
    }
}
```

## Best Practices

1. **Use CollectionFeatures.zeros()** for most cases rather than direct construction
2. **Leverage zero optimizations** in arithmetic operations for performance
3. **Check isZero()** before expensive operations when possible
4. **Use zeros for initialization** of accumulation variables
5. **Combine with conditionals** for efficient sparse operations
6. **Prefer reshape over reconstruction** when changing zero collection dimensions
7. **Document when functions may return zeros** for optimization opportunities