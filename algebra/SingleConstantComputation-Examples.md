# SingleConstantComputation Usage Examples

This document provides practical usage examples for the `SingleConstantComputation` class, demonstrating its capabilities and optimizations within the AlmostRealism computation framework.

## Basic Usage

### Creating Constant Collections

```java
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.SingleConstantComputation;

// Create a 3x3 matrix filled with the value 5.0
TraversalPolicy shape = new TraversalPolicy(3, 3);
SingleConstantComputation<PackedCollection> constant = 
    new SingleConstantComputation<>(shape, 5.0);

// Evaluate to get the actual collection
PackedCollection result = constant.get().evaluate();
// result will be a 3x3 collection where every element is 5.0
```

### Creating Different Shapes

```java
// Create a 1D vector filled with zeros
TraversalPolicy vectorShape = new TraversalPolicy(5);
SingleConstantComputation<PackedCollection> zeros = 
    new SingleConstantComputation<>(vectorShape, 0.0);

// Create a 3D tensor filled with pi
TraversalPolicy tensorShape = new TraversalPolicy(2, 2, 2);
SingleConstantComputation<PackedCollection> piTensor = 
    new SingleConstantComputation<>(tensorShape, Math.PI);
```

## Using with CollectionFeatures

### Constant Factory Method

```java
import org.almostrealism.collect.CollectionFeatures;

// Using the factory method (automatically chooses SingleConstantComputation for multi-element collections)
CollectionProducer<PackedCollection<?>> ones = constant(shape(2, 3), 1.0);
// Creates a SingleConstantComputation internally for the 2x3 matrix
```

### Arithmetic Operations with Optimization

```java
// Addition of constants (optimized)
List<Producer<?>> constants = List.of(
    constant(shape(3), 1.0), 
    constant(shape(3), 2.0), 
    constant(shape(3), 3.0)
);
CollectionProducer<PackedCollection<?>> sum = add(constants);
// Result: Optimized to constant(shape(3), 6.0) at construction time

// Multiplication of constants (optimized)
CollectionProducer<PackedCollection<?>> constant1 = constant(shape(2, 2), 2.0);
CollectionProducer<PackedCollection<?>> constant2 = constant(shape(2, 2), 3.0);
CollectionProducer<PackedCollection<?>> product = multiply(constant1, constant2);
// Result: Optimized to constant(shape(2, 2), 6.0) directly
```

### Repeating Constants

```java
// Efficient repeat operation for constants
CollectionProducer<?> constantVector = constant(shape(3), 5.0);
CollectionProducer<?> repeated = repeat(4, constantVector);
// Optimized: Uses reshape instead of full repeat computation
```

## Shape Transformations

### Reshaping

```java
SingleConstantComputation<PackedCollection> original = 
    new SingleConstantComputation<>(new TraversalPolicy(2, 3), 7.5);

// Reshape to a vector
SingleConstantComputation<PackedCollection> reshaped = 
    (SingleConstantComputation<PackedCollection>) original.reshape(new TraversalPolicy(6));

// The constant value (7.5) is preserved, only the shape changes
assert reshaped.getConstantValue() == 7.5;
```

### Traversal

```java
SingleConstantComputation<PackedCollection> matrix = 
    new SingleConstantComputation<>(new TraversalPolicy(3, 4), -2.0);

// Traverse along axis 0
SingleConstantComputation<PackedCollection> traversed = 
    (SingleConstantComputation<PackedCollection>) matrix.traverse(0);

// Constant value is preserved through traversal operations
assert traversed.getConstantValue() == -2.0;
```

## Optimization Features

### Zero Detection

```java
SingleConstantComputation<PackedCollection> zero = 
    new SingleConstantComputation<>(new TraversalPolicy(5), 0.0);

if (zero.isZero()) {
    // Can optimize operations involving this zero constant
    System.out.println("Optimization: detected zero constant");
}
```

### Identity Detection

```java
SingleConstantComputation<PackedCollection> identity = 
    new SingleConstantComputation<>(new TraversalPolicy(1), 1.0);

if (identity.isIdentity(1)) {
    // Can optimize multiplication operations
    System.out.println("Optimization: detected scalar identity");
}
```

### Short-Circuit Evaluation

```java
SingleConstantComputation<PackedCollection> constant = 
    new SingleConstantComputation<>(new TraversalPolicy(1000), 42.0);

// Use short-circuit for efficient evaluation
PackedCollection result = constant.getShortCircuit().evaluate();
// Bypasses the full computation pipeline for better performance
```

## Integration Patterns

### Custom Computations

```java
// Using the protected constructor for custom naming
public class MyConstantComputation extends SingleConstantComputation<PackedCollection> {
    public MyConstantComputation(TraversalPolicy shape, double value) {
        super("myCustomConstant", shape, value);
    }
}
```

### Pipeline Integration

```java
// SingleConstantComputation integrates seamlessly with the computation pipeline
CollectionProducer<PackedCollection<?>> pipeline = 
    constant(shape(3, 3), 1.0)  // Creates SingleConstantComputation
        .multiply(someOtherProducer)
        .add(anotherConstant)
        .traverse(1);
```

## Performance Considerations

- **Memory Efficiency**: SingleConstantComputation stores only a single double value regardless of collection size
- **Computation Optimization**: Arithmetic operations between constants are computed at construction time
- **Short-Circuit Evaluation**: Bypasses kernel compilation for constant-only operations
- **Shape Operations**: Reshape and traverse operations are O(1) as they only modify metadata

## Best Practices

1. **Use the factory method**: `constant(shape, value)` automatically chooses the most efficient implementation
2. **Leverage optimizations**: The framework automatically optimizes operations with constants
3. **Batch constant operations**: When possible, combine multiple constant operations for better optimization
4. **Consider memory usage**: For very large collections with constant values, SingleConstantComputation is memory-optimal