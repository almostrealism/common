# Computation Graph to Process Tree

## Overview

This document explains how a graph of `Producer<T>` objects is transformed into an
optimizable process tree. This is the first stage of the compilation pipeline — it
takes the high-level computation description and structures it for optimization and
eventual native compilation.

For the optimization stage, see [process-optimization-pipeline.md](process-optimization-pipeline.md).
For backend compilation, see [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md).

## The Producer-Evaluable Two-Phase Model

The AR framework separates computation **description** from **execution** using two
interfaces:

```
Producer<T>                    Evaluable<T>
  (description)      .get()      (execution)
  ─────────────────────────>
  Build graph,                 Compiled native code,
  compose operations           ready to run repeatedly
```

- **`Producer<T>`** (`base/relation/src/.../relation/Producer.java`) — Describes *what*
  to compute. Producers compose via methods like `multiply()`, `add()`, and `traverse()`.
  Building a producer graph is cheap — no compilation happens.

- **`Evaluable<T>`** (`base/relation/src/.../relation/Evaluable.java`) — The executable
  form. Calling `Producer.get()` triggers compilation and returns an `Evaluable` that
  can be called many times with different inputs.

```java
// Description phase — builds computation graph (cheap)
Producer<PackedCollection> result = cp(a).multiply(cp(b)).add(cp(c));

// Compilation phase — compiles to native code (expensive, once)
Evaluable<PackedCollection> eval = result.get();

// Execution phase — runs compiled kernel (cheap, many times)
for (int i = 0; i < 1000; i++) {
    PackedCollection output = eval.evaluate(data[i]);
}
```

**Why this matters:** The two-phase model means compilation cost is paid once and
amortized over many evaluations. This is what makes GPU acceleration practical —
kernel compilation takes milliseconds, but each execution takes microseconds.

## Computation and Scope Generation

When `Producer.get()` is called, the producer must generate a `Scope` — the AST-like
representation of the computation that can be compiled to native code.

### The Computation Hierarchy

```
Computation<T>                                   (base interface — has getScope())
├── ComputationBase<I, O, R>                     (base implementation)
│   ├── OperationComputationAdapter<T>           (produces Runnable — for side effects)
│   └── ProducerComputationBase<I, O>            (produces Evaluable — for values)
│       └── CollectionProducerComputationBase    (PackedCollection-typed producers)
```

- **`Computation<T>`** (`base/code/src/.../code/Computation.java`) — Defines the
  `getScope(KernelStructureContext)` contract. Every computation must produce a `Scope`
  describing its logic in terms of variables, assignments, and expressions.

- **`ComputationBase`** — Manages the argument list, input preparation, and scope
  lifecycle. Handles `ArgumentMap` setup and `ScopeInputManager` integration.

- **`OperationComputationAdapter<T>`** (`base/hardware/src/.../hardware/OperationComputationAdapter.java`)
  — For computations that produce side effects (write to memory) rather than return
  values. These become `Runnable` operations.

- **`ProducerComputationBase<I, O>`** — For computations that produce values. These
  become `Evaluable` instances.

- **`CollectionProducerComputationBase`** (`compute/algebra/src/.../collect/computations/CollectionProducerComputationBase.java`)
  — The primary base class for `PackedCollection`-typed computations. Most concrete
  producer classes (arithmetic, traversal, reshaping) extend this. It provides shape
  management, delta computation for autodiff, and index expression generation. This is
  the class most users interact with when building computation graphs.

### Scope: The Computation AST

A `Scope` is the intermediate representation between the high-level producer graph and
backend-specific native code. It contains:

- **Variables** — Inputs, intermediates, and outputs with type and shape information
- **Assignments** — Statements that compute values from expressions
- **Expressions** — The actual math (see [expression-evaluation.md](expression-evaluation.md))
- **Metadata** — Shape, signature, traversal policy

```java
// Simplified view of scope generation
Scope<T> scope = computation.getScope(kernelStructureContext);
// scope now contains the full AST for this computation
```

The scope is what gets compiled by the backend — OpenCL, Metal, or JNI each translate
the same `Scope` into their respective native code.

## Process Trees

A `Process` is the fundamental abstraction for composable computational work. Process
trees capture the dependency structure between computations, enabling optimization
before compilation.

### The Process Interface

```java
// base/relation/src/.../compute/Process.java
public interface Process<P extends Process<?, ?>, T>
        extends Node, Supplier<T>, Tree<P> {

    Collection<Process<?, ?>> getChildren();        // Dependencies
    Process<P, T> optimize(ProcessContext context);  // Tree restructuring
    Process<P, T> isolate();                         // Force independent execution
    long getOutputSize();                            // Memory footprint
}
```

**Key semantics:**
- Children represent **dependencies** — they must complete before the parent can execute
- `optimize()` restructures the tree for better hardware utilization
- `get()` (from `Supplier<T>`) produces the final compiled result
- `getOutputSize()` reports memory footprint for optimization decisions

### How Process Trees Are Built

When multiple computations are composed, they form a tree of processes:

```
OperationList (top-level container)
├── matmul(A, B)           Process with parallelism 4096
│   ├── transpose(A)       Child process, parallelism 64
│   └── reshape(B)         Child process, parallelism 64
├── add(result, bias)      Process with parallelism 4096
└── activation(sum)        Process with parallelism 4096
```

Each node in the tree is a `Process` that knows its children (dependencies), its
parallelism (how many independent work items it represents), and its output size
(memory footprint).

### ParallelProcess — Parallelism-Aware Processes

Most processes in the AR framework implement `ParallelProcess`, which extends `Process`
with parallelism information:

```java
// base/relation/src/.../compute/ParallelProcess.java
public interface ParallelProcess<P extends Process<?, ?>, T>
        extends Process<P, T>, Countable {

    long getParallelism();    // Number of independent work items
    boolean isUniform();      // Whether all children have same parallelism
}
```

Parallelism is central to optimization — it determines whether a child process should
be inlined into its parent's kernel or isolated into a separate kernel. See
[kernel-count-propagation.md](kernel-count-propagation.md) for how kernel counts flow
through operations.

### IsolatedProcess — Breaking Expression Embedding

When a process is **isolated**, it executes independently from its parent. This means:

1. The child's computation becomes a separate kernel
2. Results are written to a buffer
3. The parent reads from that buffer instead of inlining the child's expressions

```
Before isolation:                  After isolation:
┌─────────────────┐               ┌─────────────────┐
│ Parent Kernel   │               │ Child Kernel    │
│  child_expr * 2 │               │  write result   │──→ buffer
│  (inlined)      │               └─────────────────┘
└─────────────────┘               ┌─────────────────┐
                                  │ Parent Kernel   │
                                  │  read buffer * 2│←── buffer
                                  └─────────────────┘
```

**When isolation helps:**
- Child has high parallelism — separate kernel exploits GPU parallelism better
- Expression tree is very large — inlining would exceed register pressure limits
- Replication mismatch — child has much lower parallelism than parent, so inlining
  would replicate the child's expression tree many times

**When isolation hurts:**
- Overhead exceeds gains — kernel launch cost and buffer transfer dominate
- Low parallelism — not enough work items to justify a separate kernel
- Excessive fragmentation — too many tiny kernels

The `isolate()` method on `Process` wraps the process via `Process.of(this)`, creating
an isolation boundary. Isolation decisions are made during the optimization phase — see
[process-optimization-pipeline.md](process-optimization-pipeline.md).

**CRITICAL INSIGHT:** Never return `null` from `getValueAt()` in a process implementation.
This breaks expression embedding and causes silent failures downstream.

## OperationList — The Top-Level Container

`OperationList` (`base/hardware/src/.../hardware/OperationList.java`) is the standard
top-level process container. It implements both `OperationComputation<Void>` and
`ComputableParallelProcess`, making it both a computation and a process tree root.

```java
public class OperationList extends ArrayList<Supplier<Runnable>>
    implements OperationComputation<Void>,
               ComputableParallelProcess<Process<?, ?>, Runnable> { ... }
```

**Key responsibilities:**
- Collects operations (each a `Supplier<Runnable>`)
- Implements `ParallelProcess` — knows the parallelism of its operations
- Produces a `Scope` for optimization (via `OperationComputation`)
- Executes operations in sequence via its inner `Runner` class

```java
// Building an OperationList
OperationList ops = new OperationList("forward-pass");
ops.add(matmul(weights, input));
ops.add(addBias(result, bias));
ops.add(activation(sum));

// Optimization and execution
Runnable compiled = ops.get();  // Triggers optimization → compilation
compiled.run();                  // Executes all operations
```

## The optimize() → get() Contract

The relationship between `Process.optimize()` and `Process.get()` is fundamental:

1. **`optimize(ProcessContext)`** — Restructures the process tree. This is where
   isolation decisions are made, children are reordered, and the tree is prepared for
   efficient compilation. Optimization is recursive — each node optimizes its children
   before optimizing itself.

2. **`get()`** — Compiles and returns the executable form. By the time `get()` is
   called, the tree should already be optimized. `get()` triggers scope generation,
   native code compilation, and returns the compiled result.

**Always call `optimize()` before `get()`.** Calling `get()` on an unoptimized tree
works but may produce inefficient code — large inlined expression trees, missed
parallelism opportunities, and excessive memory usage.

```java
// Correct pattern
Process<?, Runnable> process = buildProcessTree();
Process<?, Runnable> optimized = process.optimize(ProcessContext.create());
Runnable compiled = optimized.get();

// The OperationList.get() method handles this internally
```

## Related Files

- `Process.java` (`base/relation/src/.../compute/Process.java`) — Core process interface
- `ParallelProcess.java` (`base/relation/src/.../compute/ParallelProcess.java`) — Parallelism extension
- `Computation.java` (`base/code/src/.../code/Computation.java`) — Scope generation contract
- `CollectionProducerComputationBase.java` (`compute/algebra/src/.../collect/computations/`) — PackedCollection producer base
- `OperationList.java` (`base/hardware/src/.../hardware/OperationList.java`) — Top-level container
- `Producer.java` (`base/relation/src/.../relation/Producer.java`) — Computation description
- `Evaluable.java` (`base/relation/src/.../relation/Evaluable.java`) — Compiled execution

## See Also

- [expression-evaluation.md](expression-evaluation.md) — How expression trees work within scopes
- [process-optimization-pipeline.md](process-optimization-pipeline.md) — How the process tree is optimized
- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md) — How scopes compile to native code
- [kernel-count-propagation.md](kernel-count-propagation.md) — How parallelism flows through operations
- [operationlist-optimization-flags.md](operationlist-optimization-flags.md) — OperationList compilation flags
