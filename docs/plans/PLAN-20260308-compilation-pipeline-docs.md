# Document the Compilation Pipeline: From Producer Graphs to Native Kernels

**Category:** Documentation
**Date:** 2026-03-08
**Estimated Complexity:** Medium-Large

---

## Motivation

The compilation pipeline — the path from a graph of `Producer<T>` objects to native
GPU/CPU kernels — is the core differentiating technology of the Almost Realism platform.
It is what makes the platform unique: a single computation graph compiles to native code
across CPU, OpenCL, Metal, and JNI backends.

Despite being the most architecturally complex subsystem, **the compilation pipeline has
no dedicated documentation**. The existing internals docs cover adjacent topics well
(expression evaluation, kernel count propagation, OperationList flags, CellList
architecture), but the actual compilation flow — how a producer graph becomes a process
tree, how that tree is optimized, and how it compiles to backend-specific native code —
is a black box.

This matters for three reasons:

1. **Human contributors** cannot debug compilation issues or extend the pipeline without
   reverse-engineering the code.
2. **AI coding agents** working on the platform regularly need to understand optimization
   and compilation behavior. Every session that touches hardware acceleration burns
   significant context window just reading source files.
3. **The long-term vision** of training models that understand the platform requires that
   the platform's most complex subsystem be documented in a form that models can learn from.

### What Exists Today

The `docs/internals/` directory has 16 documents. Related coverage includes:

| Document | What It Covers | Gap |
|----------|---------------|-----|
| `expression-evaluation.md` | Expression trees, simplification, Provider vs Computation | Stops at Scope generation; doesn't cover compilation |
| `kernel-count-propagation.md` | How kernel sizes flow through operations | Narrow topic within the pipeline |
| `operationlist-optimization-flags.md` | OperationList flags and their effects | Describes *what* flags do, not *how* optimization works |
| `assignment-optimization.md` | Short-circuit vs scope-based assignment | Narrow optimization within assignment |

None of these documents explain the end-to-end flow. A developer reading all 16 documents
would still not understand how `Producer.get()` ultimately results in a compiled kernel
executing on a GPU.

---

## Scope

Create **three new internals documents** that together describe the complete compilation
pipeline from end to end:

### Document 1: `computation-graph-to-process-tree.md`

**Covers:** How a graph of `Producer<T>` objects is transformed into an optimizable
process tree.

Key topics:
- The `Producer<T>` → `Evaluable<T>` two-phase model (already documented in javadoc,
  but not in internals)
- `Computation<T>` and its `getScope()` contract
- `CollectionProducerComputationBase` and the computation hierarchy
- How `Process` trees are constructed from producer graphs
- `ParallelProcess` — grouping independent computations
- `IsolatedProcess` — when and why expression embedding must be broken
- `OperationList` as the top-level process container
- The relationship between `Process.optimize()` and `Process.get()`

Key source files to reference:
- `relation/src/main/java/io/almostrealism/compute/Process.java`
- `relation/src/main/java/io/almostrealism/compute/ParallelProcess.java`
- `code/src/main/java/io/almostrealism/code/Computation.java`
- `hardware/src/main/java/org/almostrealism/hardware/OperationList.java`

### Document 2: `process-optimization-pipeline.md`

**Covers:** How the process tree is optimized before compilation.

Key topics:
- The `optimize()` pipeline stages and traversal order
- `ProcessOptimizationStrategy` and the cascading strategy pattern
  (`CascadingOptimizationStrategy`)
- `ParallelismTargetOptimization` — scoring and inlining decisions
- `ReplicationMismatchOptimization` — handling shape mismatches
- `ParallelProcessContext` — context propagation during optimization
- `ParallelismSettings` — thresholds and tuning knobs
- Cost model for isolation vs. inlining decisions
- How optimization interacts with instruction caching
- When optimization is required vs. optional
- Common optimization patterns and their effects on generated code

Key source files to reference:
- `relation/src/main/java/io/almostrealism/compute/ProcessOptimizationStrategy.java`
- `relation/src/main/java/io/almostrealism/compute/ParallelismTargetOptimization.java`
- `relation/src/main/java/io/almostrealism/compute/CascadingOptimizationStrategy.java`
- `relation/src/main/java/io/almostrealism/compute/ReplicationMismatchOptimization.java`
- `relation/src/main/java/io/almostrealism/compute/ParallelProcessContext.java`

### Document 3: `backend-compilation-and-dispatch.md`

**Covers:** How optimized process trees compile to native code and execute on hardware
backends.

Key topics:
- `ComputationScopeCompiler` — translating scopes to instruction sets
- `ScopeInstructionsManager` — lifecycle, caching, and reuse of compiled instructions
- `ComputableInstructionSetManager` — orchestrating compilation
- `InstructionSetManager` interface and the `ExecutionKey` hierarchy
- Backend-specific compilation targets (C, OpenCL, Metal)
- `Hardware` initialization and backend detection
- `HardwareDataContext` and `AbstractComputeContext` — per-backend contexts
- `ComputeContext` role in language selection and device management
- How code generation produces C / OpenCL / Metal source
- Kernel argument binding and memory layout
- Execution dispatch: enqueueing work on the selected backend
- Fallback behavior when a backend is unavailable

Key source files to reference:
- `hardware/src/main/java/org/almostrealism/hardware/instructions/ComputationScopeCompiler.java`
- `hardware/src/main/java/org/almostrealism/hardware/instructions/ScopeInstructionsManager.java`
- `hardware/src/main/java/org/almostrealism/hardware/instructions/ComputableInstructionSetManager.java`
- `hardware/src/main/java/org/almostrealism/hardware/Hardware.java`
- `hardware/src/main/java/org/almostrealism/hardware/ctx/HardwareDataContext.java`
- `hardware/src/main/java/org/almostrealism/hardware/ctx/AbstractComputeContext.java`

---

## Approach

For each document:

1. **Read the source files** listed above thoroughly, including javadoc, method
   implementations, and call patterns
2. **Trace the execution path** from a simple example (e.g., `cp(a).multiply(b).evaluate()`)
   through the pipeline to understand the actual flow
3. **Write the document** following the style of existing internals docs:
   - Start with a high-level overview and a flow diagram (ASCII art or bullet hierarchy)
   - Explain each stage with references to specific classes and methods
   - Include "red flag" anti-patterns where relevant (following the pattern in
     `packed-collection-examples.md`)
   - Cross-reference existing internals docs where topics connect
4. **Verify accuracy** by checking that the documented flow matches what the code actually does

### Writing Style Guidelines

- Follow the conventions established by `expression-evaluation.md` and
  `celllist-architecture.md` — these are the gold standard for internals docs
- Use concrete class and method names with file path references
- Include ASCII flow diagrams for the pipeline stages
- Keep each document focused (150-400 lines)
- Cross-reference between the three documents to show the full pipeline

---

## Success Criteria

1. A developer reading all three documents in sequence can explain the complete path from
   `Producer.get()` to native kernel execution without reading source code
2. Each document is accurate — the described flow matches the actual code
3. Documents cross-reference each other and existing internals docs to form a coherent whole
4. An AI coding agent given these documents would understand:
   - When `optimize()` is necessary and why
   - Why `IsolatedProcess` exists and when it triggers
   - How backend selection works
   - How instruction caching prevents redundant compilation
5. Documents follow the established style of existing internals docs

---

## Dependencies

- None — this is pure documentation work based on existing, stable code
- The compilation pipeline has been stable across recent branches (audio-loop-performance,
  similarity-performance) with only incremental changes

---

## Connection to the Larger Vision

This documentation is a prerequisite for multiple future efforts:

- **Performance optimization** — Cannot optimize the pipeline without understanding it
- **New backend support** — Adding CUDA or Vulkan requires understanding the backend
  dispatch architecture
- **Self-understanding models** — The compilation pipeline is the most complex subsystem;
  models trained on the codebase need documentation to learn it effectively
- **Contributor onboarding** — The pipeline is the first thing a new contributor needs
  to understand to work on hardware acceleration
