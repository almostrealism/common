# Relation Module (ar-relation)

The `ar-relation` module provides the foundational abstractions for the Almost Realism computational framework. It defines the core interfaces for producers, evaluables, and process-based computation that form the basis for all hardware-accelerated operations.

## Overview

This module provides:
- **Producer/Evaluable Pattern** - Two-phase execution model separating computation description from execution
- **Countable Interface** - Element counting for parallel kernel execution
- **Process Framework** - Composable, optimizable computational work units
- **Relational Frames** - Cognitive modeling abstractions based on Relational Frame Theory
- **Streaming Evaluables** - Asynchronous computation support

## Package Structure

| Package | Purpose |
|---------|---------|
| `io.almostrealism.relation` | Core producer, evaluable, and computation abstractions |
| `io.almostrealism.compute` | Process execution, parallelism, and optimization strategies |
| `io.almostrealism.streams` | Asynchronous streaming computation adapters |
| `io.almostrealism.frames` | Relational Frame Theory cognitive modeling |

## Key Concepts

### Producer/Evaluable Two-Phase Model

The framework separates computation into two distinct phases:

1. **Description Phase**: Build computation graphs using `Producer`s
2. **Execution Phase**: Compile to `Evaluable`s and execute

```java
// Phase 1: Build computation graph (description)
Producer<Tensor> inputA = ...;
Producer<Tensor> inputB = ...;
Producer<Tensor> sum = operations.add(inputA, inputB);

// Phase 2: Compile and execute
Evaluable<Tensor> evaluable = sum.get();
Tensor result = evaluable.evaluate();

// The Evaluable can be reused for multiple evaluations
Tensor result2 = evaluable.evaluate(differentArgs);
```

This separation enables:
- Static analysis of computation graphs
- Optimization and fusion of operations
- Compilation to GPU kernels or native code
- Deferred evaluation until results are needed

### Core Interfaces

**Producer<T>** - Describes a computation that produces a result
```java
public interface Producer<T> extends Supplier<Evaluable<? extends T>> {
    Evaluable<T> get();           // Compile to executable form
    T evaluate(Object... args);   // Convenience: compile + execute
    Evaluable<T> into(Object destination); // In-place computation
}
```

**Evaluable<T>** - Executable form of a computation
```java
public interface Evaluable<T> {
    T evaluate(Object... args);   // Execute computation
    Evaluable<T> into(Object d);  // In-place execution
    StreamingEvaluable<T> async(); // Async execution
}
```

**Countable** - Element counting for parallel execution
```java
public interface Countable {
    int getCount();               // Number of elements
    boolean isFixedCount();       // Fixed vs variable count
}
```

### Fixed vs Variable Count

Understanding this distinction is critical for GPU kernel execution:

**Fixed Count** (`isFixedCount() == true`):
- Number of elements known at construction time
- Cannot change at runtime
- Kernel compiles with predetermined size
```java
// Always processes exactly 100 elements
TraversalPolicy fixed = new TraversalPolicy(100);
```

**Variable Count** (`isFixedCount() == false`):
- Number of elements depends on runtime inputs
- Kernel size determined at runtime from output/argument sizes
```java
// Size matches runtime input
TraversalPolicy variable = new TraversalPolicy(false, false, 1);
```

**Kernel Execution Impact**:
```java
if (isFixedCount()) {
    kernelSize = getCount();
    // Output must be size 1 or exactly match operation count
} else {
    // Variable count: kernel size adapts to output size
    kernelSize = output.getCountLong();
}
```

### Process Framework

The `Process` interface represents composable, optimizable computational work:

```java
// Process defines computational work units
public interface Process<P, T, A> {
    T generate(A args);           // Execute the process
    P optimize(ProcessContext ctx); // Apply optimizations
    P isolate();                  // Create isolated copy
}
```

**ParallelProcess** extends this for parallel computation:
```java
// ParallelProcess manages collections of child processes
public interface ParallelProcess<P, T, A> extends Process<P, T, A> {
    int getParallelism();         // Number of parallel children
    boolean isUniform();          // All children identical?
}
```

**Optimization Strategies**:
```java
// Cascading strategy tries multiple strategies in order
ProcessOptimizationStrategy strategy = new CascadingOptimizationStrategy(
    new ParallelismTargetOptimization(),
    customStrategy
);
```

### Composition Patterns

**Factor** - Transforms one producer into another:
```java
public interface Factor<I, O> {
    Producer<O> apply(Producer<I> input);
}
```

**Composition** - Combines two producers:
```java
public interface Composition<A, B, O> {
    Producer<O> apply(Producer<A> a, Producer<B> b);
}
```

**Delegation Pattern**:
```java
public interface Delegated<T> {
    T getDelegate();              // Get delegate
    int getCircularDelegateDepth(); // Detect circular references
}
```

### Streaming Evaluables

For asynchronous computation:

```java
// Convert to async execution
StreamingEvaluable<T> streaming = evaluable.async();

// Set up result consumer
streaming.setDownstream(result -> processResult(result));

// Request computation (results delivered asynchronously)
streaming.request();
```

With custom executor:
```java
StreamingEvaluable<T> streaming = evaluable.async(myExecutor);
```

### Relational Frames

The `frames` package provides abstractions for cognitive modeling based on Relational Frame Theory (RFT):

| Frame Type | Relationship |
|------------|--------------|
| `CoordinationFrame` | "A is the same as B" (equivalence) |
| `ComparativeFrame` | "B is larger than A" (magnitude) |
| `SpatialFrame` | "A is closer than B" (proximity) |
| `TemporalFrame` | "A is before B" (sequence) |
| `CausalFrame` | "B is because of A" (cause-effect) |
| `DiecticFrame` | Perspective-dependent (I-YOU, HERE-THERE) |

```java
// Create relational frames
Predicate subject = new Predicate("sun");
Predicate referent = new Predicate("hot");
CausalFrame frame = new CausalFrame(subject, referent);
// Represents: "hot is because of sun"
```

## Usage Guidelines

### When to Use Fixed Count
- Working with known-size data structures (vectors, fixed-size matrices)
- Output size is predetermined
- Performance is critical (avoids runtime size checks)

### When to Use Variable Count
- Processing collections of varying sizes
- Building generic operations that adapt to input sizes
- Output size depends on runtime arguments

### Optimizing Computations
```java
// Apply optimization before evaluation
Producer<T> producer = ...;
T result = producer.evaluateOptimized(args);

// Or explicitly optimize the process
Process<?, T, ?> optimized = Process.optimized(producer);
```

## Common Patterns

### Creating Simple Providers
```java
// Wrap a constant value
Provider<Double> constant = new Provider<>(3.14);
Evaluable<Double> eval = constant.get();
Double value = eval.evaluate(); // Returns 3.14
```

### Building Computation Graphs
```java
// Combine producers using Factor/Composition
Factor<Input, Output> transform = input -> {
    // Transform input producer to output producer
    return createOutputProducer(input);
};

Producer<Output> result = transform.apply(inputProducer);
```

### Process Optimization
```java
// Create context with optimization strategy
ProcessContext ctx = ProcessContext.base();

// Optimize a process tree
Process<?, T, ?> process = ...;
Process<?, T, ?> optimized = process.optimize(ctx);

// Generate results
T result = optimized.generate(args);
```

## Dependencies

This is a foundational module with minimal dependencies.

## See Also

- `ar-code` module - Code generation using Producer/Evaluable abstractions
- `ar-hardware` module - Hardware acceleration implementations
- `ar-collect` module - Collection operations built on these abstractions
