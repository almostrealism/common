# Document the Compilation Pipeline Internals

**Category:** Documentation
**Date:** 2026-03-08
**Estimated Complexity:** Medium

---

## Motivation

The compilation pipeline — the path from a `Producer` expression through `Process`
optimization to native kernel dispatch on CPU, OpenCL, or Metal — is the most complex
subsystem in the Almost Realism platform. It spans three foundational modules
(`ar-relation`, `ar-code`, `ar-hardware`) and involves dozens of interacting classes.

Today, the existing internals documentation covers individual pieces well:
- `expression-evaluation.md` explains Expression trees, Scope generation, and code generation
- `assignment-optimization.md` covers short-circuit vs. scope-based execution paths
- `kernel-count-propagation.md` explains how work sizes flow through operations
- `operationlist-optimization-flags.md` documents OperationList configuration
- `dynamic-indexing.md` covers traversal and dynamic index handling

But there is **no document that traces the end-to-end flow** from a high-level
`Producer` expression to a compiled, dispatched native kernel. A developer (or an AI
assistant) trying to understand how `add(a, b).evaluate()` actually becomes a GPU kernel
launch has to piece together information from module READMEs, scattered Javadoc, and
source code across three modules. This is the single largest documentation gap in the
platform.

Closing this gap matters for two reasons:
1. **Human contributors** need to understand the pipeline to debug compilation failures,
   add optimization passes, or extend backend support.
2. **AI systems** (including the models we aspire to train on this codebase) need clear
   architectural documentation to reason about the platform's core machinery.

---

## Scope

Create **three new internals documents** in `docs/internals/`, each covering a distinct
phase of the compilation pipeline. Together, they form a complete walkthrough from
Producer creation to kernel execution.

### Document 1: `computation-graph-to-process-tree.md`

**What it covers:** How a `Producer` expression becomes a `Process` tree ready for
optimization.

**Specific content:**
- The Producer/Evaluable two-phase model (brief recap, linking to ar-relation README)
- How `CollectionProducerComputation` bridges Producer and Process
- The `Computation` interface and its role as the compilation unit
- How `OperationList` composes multiple computations into a process tree
- The tree structure: parent-child relationships, `getChildren()`, dependency ordering
- `Countable` and how element counts attach to process nodes
- Concrete example: trace `cp(a).add(cp(b))` from API call to process tree structure
- Key classes and file paths:
  - `Process` (`base/relation/.../compute/Process.java`)
  - `ParallelProcess` (`base/relation/.../compute/ParallelProcess.java`)
  - `CollectionProducerComputation` (`base/collect/.../computations/CollectionProducerComputation.java`)
  - `OperationList` (`base/hardware/.../OperationList.java`)

### Document 2: `process-optimization-pipeline.md`

**What it covers:** How `Process.optimize()` transforms the process tree for efficient
execution, including isolation decisions and optimization strategies.

**Specific content:**
- Why optimization is necessary (expression tree explosion, compilation time, memory)
- The optimization chain: `Process.optimize(ctx)` → child recursion → isolation checks
- `isIsolationTarget()`: how computations signal they need isolation
- `IsolatedProcess`: how it breaks expression embedding (not implementing
  `TraversableExpression`)
- `ParallelismTargetOptimization`: threshold-based scoring (`minCount`, `targetCount`,
  `maxCount`), how parallelism counts drive decisions
- `TraversableDepthTargetOptimization`: depth-based isolation
- `CascadingOptimizationStrategy`: composing multiple strategies
- `OperationList.optimize()`: segmentation by parallelism count, grouping consecutive
  operations
- `enableAutomaticOptimization`, `enableSegmenting`, `enableNonUniformCompilation` flags
  (link to `operationlist-optimization-flags.md` for details)
- When `optimize()` must be called (before `get()`) and what happens if it's skipped
- Debugging: `CollectionProducerComputation.isolationLogging`, common symptoms of
  missing optimization
- Concrete example: trace optimization of a model forward pass with isolation decisions
- Key classes and file paths:
  - `ProcessContext` (`base/relation/.../compute/ProcessContext.java`)
  - `ProcessOptimizationStrategy` (`base/relation/.../compute/ProcessOptimizationStrategy.java`)
  - `ParallelismTargetOptimization` (`base/relation/.../compute/ParallelismTargetOptimization.java`)
  - `IsolatedProcess` (in `ar-collect`)

### Document 3: `backend-compilation-and-dispatch.md`

**What it covers:** How an optimized process tree gets compiled to native code and
dispatched to hardware backends.

**Specific content:**
- The compilation trigger: `AcceleratedOperation.load()` and lazy compilation
- Scope preparation: `prepareScope()`, `MemoryDataArgumentMap`, argument aggregation
- `InstructionSetManager` and caching strategies:
  - `DefaultExecutionKey` (name + arg count)
  - `ScopeSignatureExecutionKey` (signature-based dedup)
  - `ProcessTreePositionKey` (tree position)
- `ComputationScopeCompiler`: Scope → native code generation
- Code generation targets:
  - C code for JNI (`NativeCompiler` → shared library → `NativeExecution`)
  - OpenCL kernel source → `CLOperator` → `clEnqueueNDRangeKernel`
  - Metal shader source → `MetalOperator` → `dispatchThreads`
- `HardwareOperator` lifecycle:
  - `prepareArguments()`: validation, memory migration (CPU↔GPU)
  - `setGlobalWorkSize()`: parallel work configuration
  - `accept()`: backend-specific kernel dispatch
  - Timing and profiling hooks
- Backend selection: `AR_HARDWARE_DRIVER` environment variable, auto-detection
  (ARM64 → JNI+MTL+CL, x86 → CL+JNI), `Hardware` singleton initialization
- Memory considerations: device vs. host memory, automatic migration in
  `HardwareOperator.prepareArguments()`, memory provider selection
- The dual execution strategy in `OperationList`:
  - Compiled path (all `Computation`s → single kernel)
  - Sequential path (mixed operations → Java execution)
- Concrete example: trace `AcceleratedOperation` from scope to kernel launch on OpenCL
- Key classes and file paths:
  - `AcceleratedOperation` (`base/hardware/.../AcceleratedOperation.java`)
  - `InstructionSetManager` (`base/hardware/.../instructions/InstructionSetManager.java`)
  - `ComputationScopeCompiler` (`base/hardware/.../instructions/ComputationScopeCompiler.java`)
  - `HardwareOperator` (`base/hardware/.../HardwareOperator.java`)
  - `CLOperator` (`base/hardware/.../cl/CLOperator.java`)
  - `MetalOperator` (`base/hardware/.../metal/MetalOperator.java`)
  - `NativeExecution` (`base/hardware/.../jni/NativeExecution.java`)
  - `Hardware` (`base/hardware/.../Hardware.java`)

---

## Approach

1. **Read the source code** for each key class listed above. The agent should trace
   actual control flow, not guess from names.

2. **Write each document** following the style of existing internals docs:
   - Start with a concise Overview section
   - Use code snippets from actual source (with file path and approximate line references)
   - Include ASCII diagrams for flow and tree structures
   - End with "Related Files" and "See Also" cross-references

3. **Cross-link** the three documents to each other and to existing internals docs
   (expression-evaluation.md, operationlist-optimization-flags.md, kernel-count-propagation.md,
   assignment-optimization.md).

4. **Update `docs/internals/expression-evaluation.md`** to add forward references to the
   new documents in its "See Also" section.

5. **Verify accuracy** by tracing a concrete computation through the pipeline and
   confirming each step matches the documentation.

---

## Success Criteria

- [ ] Three new files exist in `docs/internals/`:
  - `computation-graph-to-process-tree.md`
  - `process-optimization-pipeline.md`
  - `backend-compilation-and-dispatch.md`
- [ ] Each document is 150-300 lines with code snippets, diagrams, and cross-references
- [ ] A developer can read all three sequentially and understand the complete path from
      `Producer` to native kernel execution
- [ ] Existing internals docs are updated with cross-references to the new documents
- [ ] No inaccuracies: every claim is verified against current source code

---

## Dependencies

- None. This is a documentation-only task with no code changes required.
- The source code for all referenced classes already exists and is stable.

---

## Connection to the Larger Vision

This documentation directly serves the platform's long-term goal of self-understanding.
The compilation pipeline is the heart of Almost Realism — it's what makes the platform
more than a Java library. Without clear documentation of this pipeline, neither human
contributors nor AI systems can effectively extend, optimize, or reason about the
platform's core capability. These three documents will make the platform's most
sophisticated machinery legible and approachable.
