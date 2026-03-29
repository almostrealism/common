# Computation Model — Developer Guide

## Overview

This project uses a **computation graph model**. Java code does not execute mathematical
operations directly. Instead, Java builds a DAG (directed acyclic graph) of
`CollectionProducer` nodes that the framework compiles to native code (Metal, OpenCL)
for execution on GPU or CPU hardware.

## Why This Matters

| Approach | Element Access Cost | Parallelism | Autodiff | Kernel Fusion |
|----------|-------------------|-------------|----------|---------------|
| Producer pattern | 0 (compiled) | Full GPU | Yes | Yes |
| Java loops + toDouble | ~1μs per JNI call | None | No | No |

A matrix multiply of 1024×1024 via Java loops makes ~1 million JNI calls.
The same operation as a Producer compiles to a single GPU kernel invocation.

## Core Concepts

### CollectionProducer

A `CollectionProducer<PackedCollection>` represents a computation that WILL produce a
`PackedCollection` when evaluated. It is a node in the computation graph, not a value.

```java
// This does NOT compute anything yet — it builds a graph node
CollectionProducer<PackedCollection> result = cp(a).multiply(cp(b));

// This compiles the graph and executes it on hardware
PackedCollection value = result.evaluate();
```

### PackedCollection

`PackedCollection` is a handle to potentially GPU-resident memory. It is NOT a Java array.
You cannot safely iterate over its elements with Java loops. Use Producers to operate on it.

### Producer Composition

Producers compose like functions. Each operation returns a new Producer:

```java
// Build a computation graph: sigmoid(W @ x + b)
CollectionProducer<PackedCollection> output =
    sigmoid(matmul(p(weights), input).add(p(bias)));
```

This creates a graph with nodes for: matmul, add, sigmoid. The framework compiles
the entire graph into optimized native code.

## Common Operations as Producers

### Matrix Multiplication

```java
// Using LayerFeatures.dense() — creates a full dense layer with backprop support
CellularLayer layer = dense(inputShape, weights, biases, false);

// Using matmul directly
CollectionProducer<PackedCollection> result = matmul(p(weights), input);
```

### Activation Functions

```java
// Sigmoid: 1 / (1 + exp(-x))
CollectionProducer<PackedCollection> activated = sigmoid(input);

// Tanh
CollectionProducer<PackedCollection> activated = tanh(input);

// ReLU (via conditional)
CollectionProducer<PackedCollection> activated = max(input, c(0.0));
```

### Element-wise Operations

```java
// Addition
CollectionProducer<PackedCollection> sum = cp(a).add(cp(b));

// Multiplication (element-wise, Hadamard product)
CollectionProducer<PackedCollection> product = cp(a).multiply(cp(b));

// Subtraction
CollectionProducer<PackedCollection> diff = cp(a).subtract(cp(b));

// Scalar operations
CollectionProducer<PackedCollection> scaled = cp(a).multiply(c(2.0));
CollectionProducer<PackedCollection> shifted = cp(a).add(c(1.0));
```

### Combining Operations (GRU Gates Example)

A GRU cell's equations expressed as Producers:

```java
// r = sigmoid(W_ir @ x + b_ir + W_hr @ h + b_hr)
CollectionProducer<PackedCollection> r = sigmoid(
    matmul(p(wIr), x).add(p(bIr))
        .add(matmul(p(wHr), h)).add(p(bHr))
);

// z = sigmoid(W_iz @ x + b_iz + W_hz @ h + b_hz)
CollectionProducer<PackedCollection> z = sigmoid(
    matmul(p(wIz), x).add(p(bIz))
        .add(matmul(p(wHz), h)).add(p(bHz))
);

// n = tanh(W_in @ x + b_in + r * (W_hn @ h + b_hn))
CollectionProducer<PackedCollection> n = tanh(
    matmul(p(wIn), x).add(p(bIn))
        .add(r.multiply(matmul(p(wHn), h).add(p(bHn))))
);

// h' = (1 - z) * n + z * h
CollectionProducer<PackedCollection> hPrime =
    c(1.0).subtract(z).multiply(n).add(z.multiply(h));
```

Every operation here is a Producer. No Java math. The entire forward pass compiles
to native GPU kernels with full gradient support.

## Where .evaluate() Is Acceptable

`.evaluate()` triggers compilation and execution of the computation graph. It should
only appear at **pipeline boundaries**:

- **Test methods** — verifying computation results
- **Main methods** — final output of a pipeline
- **Autoregressive loop boundaries** — between decode steps, not within them
- **Data loading/preprocessing** — one-time setup operations

`.evaluate()` must NEVER appear inside:
- Model layer implementations
- Cell or Block implementations
- Any method that participates in the computation graph

## Where .toDouble() Is Acceptable

`.toDouble()` extracts a single scalar from a PackedCollection via JNI. Acceptable uses:
- Extracting a final result for display or logging
- One-time configuration reads
- Genetic algorithm fitness evaluation (inherently CPU-bound)

Never acceptable:
- Inside `for` loops operating on collection elements
- As part of matrix/vector computation
- Inside model forward/backward passes

## Common Mistakes

### Mistake 1: Java Loop for Matrix Multiply
```java
// WRONG
for (int i = 0; i < rows; i++) {
    for (int j = 0; j < cols; j++) {
        double sum = 0;
        for (int k = 0; k < inner; k++) {
            sum += a.toDouble(i * inner + k) * b.toDouble(k * cols + j);
        }
        result.setMem(i * cols + j, sum);
    }
}

// CORRECT
CollectionProducer<PackedCollection> result = matmul(p(a), p(b));
```

### Mistake 2: Calling .evaluate() Inside a Layer
```java
// WRONG — breaks the computation graph
public PackedCollection forward(PackedCollection input) {
    PackedCollection hidden = matmul(p(weights), cp(input)).evaluate();  // NO!
    return sigmoid(cp(hidden)).evaluate();  // Separate evaluations = no fusion
}

// CORRECT — returns a Producer, lets the framework optimize
public CollectionProducer<PackedCollection> forward(Producer<PackedCollection> input) {
    return sigmoid(matmul(p(weights), input));
}
```

### Mistake 3: Element-wise Activation in Java
```java
// WRONG
for (int i = 0; i < size; i++) {
    double x = collection.toDouble(i);
    collection.setMem(i, 1.0 / (1.0 + Math.exp(-x)));  // sigmoid in Java
}

// CORRECT
CollectionProducer<PackedCollection> result = sigmoid(cp(collection));
```

## The Cell and Block Interfaces

### Cell

`Cell<T>` represents a unit of computation in a dataflow graph. Its key method is
`push(Producer<T>)` which accepts a Producer input and produces a `Supplier<Runnable>`.

```java
// Create a cell from a transformation function
Cell<PackedCollection> myCell = Cell.of(input -> sigmoid(matmul(p(weights), input)));
```

### Block

`Block` represents a neural network layer with forward and backward passes, input/output
shapes, and composability.

```java
// Chain blocks fluently
Block model = new SequentialBlock(inputShape)
    .andThen(dense(256))
    .andThen(dense(10));
```

### Naming Conventions

- Classes ending in `Cell` MUST implement `org.almostrealism.graph.Cell`
- Classes ending in `Block` MUST implement `org.almostrealism.model.Block`

These conventions are enforced by the build-time `CodePolicyViolationDetector`.

## Enforcement

The `CodePolicyViolationDetector` scans all source files and fails the build when
violations are detected. It checks for:

- CPU loops with `setMem()` / `toDouble()` patterns
- `System.arraycopy` / `Arrays.copyOf` on PackedCollection data
- `.evaluate()` calls in computation code
- `.toDouble()` calls in computation code
- Cell/Block naming violations
- Features interfaces with abstract methods

See `engine/utils/src/main/java/org/almostrealism/util/CodePolicyViolationDetector.java`.
