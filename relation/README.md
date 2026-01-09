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

The `Process` interface represents composable, optimizable computational work units that form a tree structure. Understanding Process optimization and isolation is **critical** for correct and efficient execution.

```java
// Process defines computational work units
public interface Process<P extends Process<?, ?>, T> extends Supplier<T>, Tree<P> {
    T get();                          // Execute the process
    Process<P, T> optimize();         // Apply optimizations (uses base context)
    Process<P, T> optimize(ProcessContext ctx); // Apply optimizations with context
    Process<P, T> isolate();          // Create isolated copy
    boolean isIsolationTarget(ProcessContext ctx); // Should this process be isolated?
    long getOutputSize();             // Memory footprint for optimization decisions
}
```

**ParallelProcess** extends this for parallel computation:
```java
// ParallelProcess manages collections of child processes
public interface ParallelProcess<P, T> extends Process<P, T>, Countable {
    long getParallelism();            // Number of parallel children
    boolean isUniform();              // All children have same parallelism?
    ParallelProcess<P, T> optimize(ProcessContext ctx); // Optimizes children
}
```

---

## ⚠️ CRITICAL: Process Optimization and Isolation ⚠️

**This is the most important architectural concept in the framework. Incorrect handling will cause severe performance issues or incorrect results.**

### Why Optimization Matters

Many computations (especially those with loops or large iteration counts) MUST be isolated into separate execution units. Without proper isolation:
- Expression trees can grow exponentially large
- Compilation times become unacceptable (minutes to hours)
- Memory usage explodes
- Tests timeout or fail

### The Optimization Chain

When `optimize()` is called on a Process tree:

```
Process.optimize(ctx)
    └── For each child process:
        └── ParallelProcess.optimize(ctx, child)
            ├── child.optimize(ctx)           // Recursively optimize child
            ├── Check child.isIsolationTarget(ctx)
            │   └── If true → child.isolate()  // Wrap in IsolatedProcess
            └── Return optimized (possibly isolated) child
    └── ProcessOptimizationStrategy.optimize()  // Apply strategy
```

### isIsolationTarget() - Requesting Isolation

A process signals it needs isolation by overriding `isIsolationTarget()`:

```java
public class LoopedComputation extends CollectionProducerComputation {
    @Override
    public boolean isIsolationTarget(ProcessContext context) {
        // Return true if this computation should be isolated
        // Common reasons:
        // - Native loop generation (vs expression unrolling)
        // - Large iteration count that would explode expression tree
        // - Memory-intensive operations
        return iterationCount > threshold;
    }
}
```

### IsolatedProcess - Breaking Expression Embedding

When `isolate()` is called on a computation, it returns an `IsolatedProcess` wrapper:

```java
// IsolatedProcess does NOT implement TraversableExpression
class IsolatedProcess extends DelegatedCollectionProducer {
    // The key property: this class lacks getValueAt()
    // When a parent checks: producer instanceof TraversableExpression
    // It returns false, naturally breaking expression embedding
}
```

**This is the ONLY proper way to break expression embedding.** Never return null from `getValueAt()` to force isolation.

### When optimize() MUST Be Called

`optimize()` must be called before `get()` when the process tree may contain computations requiring isolation:

```java
// CORRECT: Call optimize() before get()
OperationList op = model.getForward().push(input);
op = (OperationList) op.optimize();  // This triggers isolation
Runnable compiled = op.get();
compiled.run();

// INCORRECT: Missing optimize() call
OperationList op = model.getForward().push(input);
Runnable compiled = op.get();  // May cause timeouts/failures!
```

**Note**: `OperationList.enableAutomaticOptimization` is `false` by default. Either:
1. Call `optimize()` explicitly, OR
2. Set `OperationList.enableAutomaticOptimization = true`, OR
3. Use `CompiledModel` which calls `optimize()` internally

### Optimization Strategies

```java
// ParallelismTargetOptimization - Default strategy
// Uses parallelism thresholds and scoring to decide isolation
ProcessOptimizationStrategy strategy = new ParallelismTargetOptimization();

// Key thresholds:
ParallelismTargetOptimization.minCount = 256;      // Min parallelism
ParallelismTargetOptimization.targetCount = 131072; // Target parallelism (2^17)
ParallelismTargetOptimization.maxCount = 1048576;  // Max parallelism (2^20)

// Cascading strategy tries multiple strategies in order
ProcessOptimizationStrategy cascading = new CascadingOptimizationStrategy(
    new ParallelismTargetOptimization(),
    customStrategy
);
```

### Debugging Isolation Issues

If you see these symptoms:
- Test timeouts (60+ seconds for simple operations)
- Massive expression trees in stack traces
- OutOfMemoryError during compilation

Check:
1. Is `optimize()` being called before `get()`?
2. Does the problematic computation override `isIsolationTarget()` correctly?
3. Is the parent process calling `optimize(ctx, child)` on its children?

```java
// Debug: Log when isolation happens
CollectionProducerComputation.isolationLogging = true;
```

### Key Classes for Process Optimization

| Class | Purpose |
|-------|---------|
| `Process` | Base interface for optimizable work units |
| `ParallelProcess` | Parallel execution with child optimization |
| `ProcessContext` | Context for optimization decisions |
| `ProcessOptimizationStrategy` | Strategy interface for optimization |
| `ParallelismTargetOptimization` | Default threshold-based strategy |
| `IsolatedProcess` | Wrapper that breaks expression embedding |
| `isIsolationTarget()` | Method to request isolation |

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
