# The Producer/Evaluable Pattern

This document explains the core computation model used throughout the Almost Realism
framework. If you are new to the codebase, start here.

## Computation as Relations, Not Procedures

The framework models computation as *relations* -- declarative descriptions of how
things relate to each other -- rather than as imperative procedures.

Consider a sphere. Imperatively, you write an intersection function that takes a ray
and solves a quadratic equation. Relationally, you describe what is *true* about the
sphere: every point on its surface satisfies `len(x) = r`. The intersection with a
ray is the solution to the system formed by composing the sphere's relation with the
ray's relation. The computation emerges from the relation, not the other way around.

This idea is realized through two interfaces that separate **describing** a
computation from **executing** it: `Producer` and `Evaluable`.

## Producer -- Describing a Computation

`Producer<T>` (in `io.almostrealism.relation`) describes a computation that will
eventually produce a value of type `T`. It extends `Supplier<Evaluable<? extends T>>`.

A `Producer` does not compute anything by itself. It is a node in a computation graph
that records *what* should happen, not *when* or *how* it happens.

Key methods:

| Method | Purpose |
|--------|---------|
| `Evaluable<T> get()` | Compile this description into an executable `Evaluable` |
| `Evaluable<T> into(Object destination)` | Compile for in-place computation into a pre-allocated destination |
| `T evaluate(Object... args)` | Convenience: calls `get().evaluate(args)` (compiles every time) |
| `T evaluateOptimized(Object... args)` | Optimizes the computation graph, then compiles and evaluates |

The most important of these is `get()`. It is the bridge between description and
execution.

## Evaluable -- Executing a Computation

`Evaluable<T>` (in `io.almostrealism.relation`) is the compiled, executable form of
a computation. It is a `@FunctionalInterface` whose core method is:

```java
T evaluate(Object... args);
```

An `Evaluable` is created by calling `Producer.get()`. Once created, it can be
invoked repeatedly with different arguments. This is where compilation costs are
paid -- GPU kernel generation, native code compilation, and operation fusion all
happen during `get()`, not during `evaluate()`.

Additional capabilities:

| Method | Purpose |
|--------|---------|
| `T evaluate()` | Evaluate with no arguments |
| `Evaluable<T> into(Object destination)` | Return an evaluable that writes results into a pre-allocated object |
| `StreamingEvaluable<T> async()` | Wrap for asynchronous execution |
| `StreamingEvaluable<T> async(Executor executor)` | Wrap for async execution with a specific executor |
| `Multiple<T> createDestination(int size)` | Allocate a container for batch results |

## Why Two Phases?

The separation between `Producer` (description) and `Evaluable` (execution) is the
foundation that makes the rest of the framework possible:

1. **Optimization.** Before compiling, the framework can analyze and transform the
   computation graph -- fusing operations, eliminating redundancies, choosing
   parallelism strategies. This happens via the `Process` tree (see below).

2. **GPU Compilation.** A graph of `Producer`s can be compiled into GPU kernels,
   Metal shaders, or OpenCL programs. This is only possible because the computation
   is described before it runs.

3. **Deferred Evaluation.** Computations are not executed until `evaluate()` is
   called. This lets the framework batch work, reorder operations, and allocate
   memory efficiently.

4. **Reuse.** A single `Evaluable` compiled from a `Producer` can be called many
   times with different inputs, amortizing compilation cost over many evaluations.

## Factor -- Unary Transformation

`Factor<T>` transforms one `Producer` into another. It is a `@FunctionalInterface`
with one method:

```java
Producer<T> getResultant(Producer<T> value);
```

Use cases include normalization, negation, and optimization transformations. Factors
can be chained with `andThen(Factor<T> next)` to create composite transformations.

```java
Factor<Tensor> normalize = input -> divide(input, computeNorm(input));
Producer<Tensor> normalized = normalize.getResultant(rawData);
```

## Composition -- Binary Combination

`Composition<T>` combines two `Producer`s into one. It is a `@FunctionalInterface`
with one method:

```java
Producer<T> compose(Producer<T> a, Producer<T> b);
```

Use cases include arithmetic (add, multiply), logical operations, and data merging.
Compositions can be followed by a `Factor` using `andThen(Factor<T> next)`:

```java
Composition<Tensor> add = (a, b) -> createAddProducer(a, b);
Producer<Tensor> sum = add.compose(tensorA, tensorB);

// Chain with a factor
Composition<Tensor> addThenNormalize = add.andThen(normalizeFactor);
```

## The cp() Pattern

To enter the computation graph, raw data must be wrapped in a `Producer`. The `cp()`
method (short for "collection producer") on `CollectionFeatures` does this for
`PackedCollection` values:

```java
CollectionProducer result = cp(source).multiply(2.0);
```

`cp(PackedCollection value)` returns a `CollectionProducer` -- a `Producer`
specialized for `PackedCollection` that provides fluent methods for building
computation graphs (`.multiply()`, `.add()`, `.traverse()`, `.sum()`, etc.).

This is the standard entry point for numerical computation. Without `cp()`, a
`PackedCollection` is just data sitting in memory. With `cp()`, it becomes a node
in a computation graph that can be optimized and compiled.

See [packed-collection-examples.md](packed-collection-examples.md) for detailed usage.

## Common Mistakes

**Calling `get()` inside a loop.** Each call to `get()` triggers compilation. Cache
the `Evaluable` and reuse it:

```java
// Wrong -- compiles on every iteration
for (int i = 0; i < 1000; i++) {
    T result = producer.get().evaluate(inputs[i]);
}

// Right -- compile once, evaluate many times
Evaluable<T> evaluable = producer.get();
for (int i = 0; i < 1000; i++) {
    T result = evaluable.evaluate(inputs[i]);
}
```

**Using `evaluate()` instead of caching.** The convenience method
`Producer.evaluate(args)` calls `get().evaluate(args)` every time. It is fine for
one-shot usage but wasteful in loops. For repeated evaluation, call `get()` once
and hold the `Evaluable`.

**Skipping optimization.** Calling `get()` directly on a complex `Producer` tree
compiles without optimization. Use `Process.optimized(producer)` or
`evaluateOptimized()` to apply graph-level optimizations first:

```java
// Without optimization
Evaluable<T> evaluable = producer.get();

// With optimization (preferred for complex graphs)
Evaluable<T> evaluable = Process.optimized(producer).get();

// One-shot with optimization
T result = producer.evaluateOptimized(args);
```

**Treating PackedCollection as a Java array.** Never use `System.arraycopy`,
`Arrays.copyOf`, or tight `setMem` loops on `PackedCollection`. It is a handle to
potentially GPU-resident memory. Use the `cp()` pattern instead.

## Connection to Process

`Producer`s that implement the `Process` interface form trees that the framework can
analyze and optimize before compilation. The `Process.optimize()` method transforms
these trees -- fusing operations, choosing parallelism strategies, and preparing for
hardware dispatch.

The typical flow is:

```
Producer graph  -->  Process.optimize()  -->  Optimized Producer  -->  get()  -->  Evaluable  -->  evaluate()
 (description)       (graph transforms)       (optimized description)  (compile)   (executable)    (execute)
```

`Process.optimized(Supplier)` is a convenience that calls `optimize()` if the
supplier is a `Process`, or returns it unchanged otherwise.

For details on how Process trees are constructed and optimized, see:
- [computation-graph-to-process-tree.md](computation-graph-to-process-tree.md)
- [process-optimization-pipeline.md](process-optimization-pipeline.md)
- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md)

## Summary

| Concept | Type | Role |
|---------|------|------|
| **Producer** | `Producer<T>` | Describes a computation (what to compute) |
| **Evaluable** | `Evaluable<T>` | Executes a computation (compiled form) |
| **Factor** | `Factor<T>` | Transforms one Producer into another (unary) |
| **Composition** | `Composition<T>` | Combines two Producers into one (binary) |
| **Process** | `Process<P, T>` | Organizes Producers into optimizable trees |
| **cp()** | `CollectionFeatures.cp()` | Wraps raw data into a Producer to enter the graph |

The two-phase model -- describe with `Producer`, execute with `Evaluable` -- is the
foundation of the entire framework. Every higher-level abstraction (neural network
layers, ray tracing, audio synthesis) builds on this pattern.
