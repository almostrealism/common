# End-to-End Computation: From Producer to Hardware

This document traces a single computation through every stage of the AR pipeline,
from `CollectionProducer` construction to hardware execution. It ties together the
four pipeline docs into one narrative.

**Prerequisites:**
- [producer-evaluable-pattern.md](producer-evaluable-pattern.md) -- what Producers and Evaluables are
- [computation-graph-to-process-tree.md](computation-graph-to-process-tree.md) -- how graphs become process trees
- [process-optimization-pipeline.md](process-optimization-pipeline.md) -- how trees are optimized
- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md) -- how scopes compile to native code

## The Example

```java
PackedCollection a = new PackedCollection(3).fill(1.0, 2.0, 3.0);
PackedCollection b = new PackedCollection(3).fill(4.0, 5.0, 6.0);
PackedCollection result = cp(a).multiply(cp(b)).evaluate();
// result = [4.0, 10.0, 18.0]
```

Six stages transform this into a hardware kernel execution:

---

## Stage 1: Producer Construction

`cp(a)` calls `CollectionCreationFeatures.cp(PackedCollection)`, which wraps the
`PackedCollection` in a `CollectionProducer`. This producer holds a reference to
`a`'s memory but performs no computation.

`multiply(cp(b))` invokes `ArithmeticFeatures.multiply(Producer, Producer)`. After
short-circuit checks (identity, zero, constant folding), this creates a
`CollectionProductComputation` wrapped in a `CollectionProducer`:

```
CollectionProducer [multiply]
 ├── input 0: CollectionProducer [cp(a)]  -->  PackedCollection [1.0, 2.0, 3.0]
 └── input 1: CollectionProducer [cp(b)]  -->  PackedCollection [4.0, 5.0, 6.0]
```

Nothing has been compiled or executed. The graph is a lightweight description.

**Details:** [producer-evaluable-pattern.md](producer-evaluable-pattern.md)

---

## Stage 2: Evaluable Creation

Calling `evaluate()` on `Producer` triggers `get().evaluate()` (see `Producer.java`).

`CollectionProducerComputationBase.get()` creates a `HardwareEvaluable` -- a lazy
wrapper that defers compilation until first use. When `evaluate()` is called, the
inner `CollectionProducerComputation.get()` runs, which:

1. Obtains the `ComputeContext` from `Hardware.getLocalHardware().getComputer()`
2. Creates a `DefaultCollectionEvaluable` (extends `AcceleratedComputationEvaluable`)
3. Calls `load()` to trigger compilation

```
Producer.evaluate()  -->  CollectionProducerComputationBase.get()
                            |
                            v
                          HardwareEvaluable (lazy)  -->  on first use:
                            |
                            v
                          CollectionProducerComputation.get()
                            |
                            v
                          DefaultCollectionEvaluable  -->  AcceleratedOperation.load()
```

---

## Stage 3: Scope Generation

Inside `AcceleratedComputationOperation`, the `ComputationScopeCompiler` calls
`Computation.getScope(KernelStructureContext)` on the `CollectionProductComputation`.

`CollectionProductComputation` extends `TraversableExpressionComputation`, which
builds its scope by calling `getExpression(TraversableExpression...)`. For our
multiply, this returns a `CollectionExpression` representing element-wise product:

```
Scope
 ├── Variable: arg0  (output destination, size 3)
 ├── Variable: arg1  (input a, size 3)
 ├── Variable: arg2  (input b, size 3)
 └── Assignment: arg0[i] = arg1[i] * arg2[i]
         where i is the kernel index (0..2)
```

The `Scope` is a tree of `Variable` declarations, `Expression` nodes, and
`ExpressionAssignment` statements. It is target-agnostic -- it does not contain
C or OpenCL syntax yet.

**Details:** [computation-graph-to-process-tree.md](computation-graph-to-process-tree.md)

---

## Stage 4: Process Optimization

For this simple two-input multiply, the optimization stage has little to do. The
computation is a single `ParallelProcess` with parallelism 3 (one work item per
element). Both inputs are direct memory references, so no child processes need
isolation.

For larger graphs, `Process.optimize()` walks the tree and decides which children
to **inline** (embed expressions in the parent kernel) vs. **isolate** (compile as
separate kernels that write to intermediate buffers). The decision is based on
parallelism compatibility, expression complexity, and memory trade-offs.

```
Before optimization:              After optimization (no change here):

ParallelProcess [multiply, p=3]   ParallelProcess [multiply, p=3]
 ├── input: a (memory ref)         ├── input: a (memory ref)
 └── input: b (memory ref)         └── input: b (memory ref)
```

**Details:** [process-optimization-pipeline.md](process-optimization-pipeline.md)

---

## Stage 5: Compilation

`AcceleratedComputationOperation.getInstructionSetManager()` drives compilation.
It checks for a cached `InstructionSet` by signature. If
`ScopeSettings.enableInstructionSetReuse` is enabled and a match exists, cached
instructions are reused. Otherwise:

```
AcceleratedComputationOperation.getInstructionSetManager()
  |
  v
ComputationScopeCompiler
  |  calls Computation.getScope() to get the Scope
  |  resolves argument variables, simplifies expressions
  v
ComputeContext.deliver(scope)
  |
  +-- NativeComputeContext:  Scope --> C source --> clang --> .so --> JNI
  +-- CLComputeContext:      Scope --> OpenCL source --> cl_program
  +-- MetalComputeContext:   Scope --> Metal source --> MTLLibrary
  |
  v
InstructionSet  (cached by ScopeInstructionsManager)
  |
  v
Execution  (retrieved by ExecutionKey)
```

`ScopeInstructionsManager` caches compiled instruction sets. `ExecutionKey` uniquely
identifies a kernel within an instruction set, so structurally identical computations
share compiled code. For our multiply with the JNI/C backend, the generated code
looks roughly like:

```c
void multiply(double* arg0, double* arg1, double* arg2, int idx) {
    arg0[idx] = arg1[idx] * arg2[idx];
}
```

This is compiled to a shared library (`.so`) and loaded via JNI.

**Details:** [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md)

---

## Stage 6: Execution

`AcceleratedComputationEvaluable.evaluate()` dispatches the kernel. `confirmLoad()`
ensures compilation is complete, `load()` retrieves the `Execution` from the
`InstructionSet`, and the kernel runs with input memory pointers as arguments:

```
confirmLoad()  -->  load()  -->  Execution (compiled kernel)
  -->  apply(outputBank, [a.mem, b.mem])

Hardware dispatch:
  Thread 0: output[0] = a[0] * b[0]  =  1.0 * 4.0  =  4.0
  Thread 1: output[1] = a[1] * b[1]  =  2.0 * 5.0  = 10.0
  Thread 2: output[2] = a[2] * b[2]  =  3.0 * 6.0  = 18.0
  |
  v
PackedCollection result = [4.0, 10.0, 18.0]
```

---

## Full Pipeline Summary

```
Java code                   What happens                     Key class
─────────────────────────   ──────────────────────────────   ──────────────────────────────────
cp(a)                       Wraps memory as a Producer       CollectionCreationFeatures.cp()
  .multiply(cp(b))          Builds product Producer graph    ArithmeticFeatures.multiply()
                                                             CollectionProductComputation
  .evaluate()               Triggers get() + evaluate()      Producer.evaluate()

  get():
    Create evaluable         Lazy compilation wrapper         HardwareEvaluable
    Build scope              Expression tree from Computation CollectionProductComputation.getScope()
    Optimize process         Inline/isolate decisions         Process.optimize()
    Compile scope            Scope -> native code             ComputeContext.deliver()
    Cache instructions       Reuse across identical graphs    ScopeInstructionsManager

  evaluate():
    Load kernel              Retrieve compiled Execution      AcceleratedOperation.load()
    Dispatch                 Run on hardware                  Execution.apply()
    Return result            Output PackedCollection          PackedCollection [4.0, 10.0, 18.0]
```

## Key Invariants

- **No computation during construction.** `cp()` and `multiply()` only build a graph.
- **Compilation is deferred.** The scope is not compiled until `get()` is called.
- **Caching avoids recompilation.** Structurally identical computations share compiled
  kernels via `ScopeInstructionsManager` and `ExecutionKey`.
- **Memory stays on-device.** `PackedCollection` pointers are passed directly to the
  kernel. No Java-side copying occurs during execution.
- **Parallelism is automatic.** The kernel dispatches one work item per element, derived
  from the output shape.
