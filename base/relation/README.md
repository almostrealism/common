# Relation Module (ar-relation)

## The Idea

The central idea of this module is that computation can be modeled as *relations* — declarative descriptions of how things relate to each other — rather than as imperative procedures.

Consider a sphere. Imperatively, you write an intersection function that takes a ray and solves a quadratic equation. Relationally, you describe what is *true* about the sphere: every point on its surface satisfies `len(x) = r`. The intersection with a ray is then the solution to the system formed by composing the sphere's relation with the ray's relation. The computation emerges from the relation, not the other way around.

This module provides the vocabulary for describing, composing, transforming, and executing such relations. The `Producer` interface is the primary vehicle: it describes a relation between inputs and an output without executing it. A `Factor` transforms one relation into another. A `Composition` combines two relations. A `Process` tree organizes composed relations for optimization before execution. An `Evaluable` is the compiled, executable form of a fully described relation.

## Package Structure

| Package | Purpose |
|---------|---------|
| `io.almostrealism.relation` | Core types for describing, composing, and transforming relations |
| `io.almostrealism.compute` | Optimization of composed relation trees for parallel execution |
| `io.almostrealism.streams` | Asynchronous delivery of relation results |
| `io.almostrealism.frames` | Semantic relation types — extending relational descriptions to real-world domains |

### What lives where

**`io.almostrealism.relation`** — The core vocabulary:
- **Describing relations**: `Producer`, `Evaluable`, `Computable`, `FixedEvaluable`, `Provider`, `DynamicProducer`
- **Transforming relations**: `Factor`, `Composition`, `Realization`, `ProducerFeatures`, `ProducerWithRank`
- **Composing relations into structures**: `Node`, `Graph`, `Tree`, `Group`, `NodeGroup`, `NodeList`, `WeightedGraph`, `IndexedGraph`, `Parent`, `Path`, `TreeRef`
- **Execution and lifecycle**: `Process`, `Operation`, `Countable`, `Series`, `Validity`, `Delegated`, `Generated`, `Factory`

**`io.almostrealism.compute`** — Optimization infrastructure for deciding how to execute composed relation trees efficiently on hardware. `ProcessContext`, optimization strategies (`ParallelismTargetOptimization`, `ReplicationMismatchOptimization`, `CascadingOptimizationStrategy`), and parallelism scoring.

**`io.almostrealism.streams`** — Adapters for delivering relation results asynchronously via push-based consumers (`StreamingEvaluable`, `EvaluableStreamingAdapter`).

**`io.almostrealism.frames`** — A forward-looking extension. See [Semantic Relations and Relational Frames](#semantic-relations-and-relational-frames) below.

## Overview

Today, the module's relation types are used to describe computational relations — mathematical operations, data transformations, GPU kernel compositions. The framework provides:
- **Producer/Evaluable Pattern** — Two-phase execution model separating relation description from execution
- **Process Framework** — Composable, optimizable trees of computational work
- **Countable Interface** — Element counting for parallel kernel execution
- **Streaming Evaluables** — Asynchronous computation support

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

### Semantic Relations and Relational Frames

The `frames` package represents a forward-looking extension of the relation concept. Today, the module's relation types describe *computational* relations — a `Producer` relates inputs to outputs through mathematical operations. But the relational model has the potential to generalize in two directions:

**Generalization 1: Complexity of the relation.** Instead of only representing relations that map to hardware-accelerable arithmetic, the system could represent relations between arbitrary computational artifacts. For example: a relation asserting that one `Scope` should produce the same result as another `Scope`, enabling a solver to search for conditions under which two programs are equivalent.

**Generalization 2: Semantic domain of the relation.** Instead of only representing mathematical relations, the system could represent relations that carry real-world meaning — and with that meaning, additional axioms and modes of reasoning that a solver could exploit.

This is where Relational Frame Theory (RFT) enters. RFT identifies a small set of relational frame types that humans use to organize knowledge:

| Frame Type | Relationship | Entailment Properties |
|------------|--------------|----------------------|
| `CoordinationFrame` | "A is the same as B" | Symmetric, transitive (equivalence) |
| `ComparativeFrame` | "B is larger than A" | Asymmetric, transitive (ordering) |
| `SpatialFrame` | "A is closer than B" | Asymmetric, context-dependent |
| `TemporalFrame` | "A is before B" | Asymmetric, transitive (total order) |
| `CausalFrame` | "B is because of A" | Asymmetric, not transitive in general |
| `DiecticFrame` | Perspective relations (I/you, here/there, now/then) | Context-dependent, non-reversible |

Each frame type carries *entailment rules*. If you establish "A is before B" and "B is before C" through `TemporalFrame`s, the system can derive "A is before C" without being told — the frame type itself provides the axiom. This is fundamentally different from a mathematical relation, where the rules of combination come from the algebra rather than the domain.

The current implementation is embryonic — each frame is a pair of `Predicate` references. But the aspiration is to connect these semantic frames to the same compositional and solving infrastructure that the computational relation types use, enabling experiments in reasoning about the real world using mathematical, logical, and algorithmic relations as a unified substrate.

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

Depends only on `ar-meta` (foundational interfaces for naming, identity, lifecycle).

## See Also

- `ar-meta` module — Shared contracts (Named, Signature, Destroyable) used by relation types
- `ar-code` module — Code generation using Producer/Evaluable abstractions
- `ar-hardware` module — Hardware acceleration implementations
- `ar-collect` module — Collection operations built on these abstractions
