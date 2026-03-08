# Project Manager Log

This is the living journal of the Almost Realism project management process. It captures
the current thinking about where the platform is heading, why specific tasks are chosen,
and how each piece of work connects to the larger vision.

This document is maintained by the automated project planning agent. It is not a historical
archive — it is a snapshot of current thinking. Older entries are pruned to keep the
document focused and under 50,000 characters.

---

## Current State

The Almost Realism platform is a monorepo of four Maven projects (`common`, `flowtree`,
`rings`, `ringsdesktop`) providing high-performance scientific computing, ML, and
multimedia generation with pluggable native acceleration (CPU, OpenCL, Metal, JNI).

### Strategic Direction

The platform is pursuing a layered strategy:

1. **Foundation** — Solid documentation, clean code, comprehensive tests
2. **Performance** — Hardware-accelerated computation that rivals dedicated frameworks
3. **Proof of Value** — Real-world demonstrations (model replication, audio generation,
   distributed training) that show what the platform can do
4. **Self-Understanding** — The long-term aspiration: training modest language models
   locally that understand the platform itself, creating software that grows and evolves
   alongside its own capabilities

### Task Categories (Priority Order)

1. **Documentation** — Can the platform be understood by humans and AI systems?
2. **Code Quality** — Is the codebase clean, consistent, and well-tested?
3. **Performance** — Can the platform handle real workloads efficiently?
4. **Proof of Value** — Can we demonstrate compelling real-world capabilities?

---

## 2026-03-08 — Planning Cycle: Compilation Pipeline Documentation

### What I Investigated

Performed a comprehensive survey of documentation coverage across the entire platform:

**Module READMEs (37 files):** Coverage is excellent at 93%+. Every layer has READMEs,
and most are thorough (150-550 lines). The only stub is `domain/physics/README.md` at
150 lines — adequate overview but missing detailed API docs.

**Internals Documentation (16 files in `docs/internals/`):** Strong coverage of specific
subsystems — expression evaluation, assignment optimization, dynamic indexing, kernel
count propagation, CellList architecture (5 docs), profiling, and training/sampling loop
examples. These are well-written and grounded in actual source code.

**Development Guidelines:** Excellent. `CLAUDE.md` provides mandatory tool-use rules and
code quality standards. Module-specific guidelines exist for ML (`engine/ml/claude.md`)
and Graph (`domain/graph/README.md`). Quick Reference provides a condensed API cheatsheet.

**The Gap:** The compilation pipeline — the path from `Producer` expression through
`Process` optimization to native kernel dispatch — has **no dedicated internals
documentation**. This is the platform's most complex subsystem, spanning three foundational
modules (`ar-relation`, `ar-code`, `ar-hardware`) and involving dozens of classes. The
ar-relation README documents Process optimization well, and expression-evaluation.md
covers Expression→Scope→code generation, but nothing connects these into an end-to-end
narrative.

I traced the actual compilation pipeline through source code to confirm this gap:
- `Process.java` → `ParallelProcess` → optimization chain with isolation decisions
- `AcceleratedOperation.java` → `InstructionSetManager` → `ComputationScopeCompiler`
- `HardwareOperator` → `CLOperator` / `MetalOperator` / `NativeExecution`
- `Hardware.java` → backend selection via `AR_HARDWARE_DRIVER`

Each class is well-implemented but the connections between them are undocumented.

### Why This Task

Documentation is Category 1 (highest priority), and this is the most impactful
documentation gap in the project. The compilation pipeline is what makes Almost Realism
more than a Java library — it's the core capability that enables hardware-accelerated
computation across CPU, GPU, OpenCL, and Metal. Without clear documentation of this
pipeline:

- New contributors cannot debug compilation failures or add optimization passes
- AI assistants (including the ar-consultant) cannot answer questions about how
  computation graphs become native kernels
- The platform's most sophisticated machinery remains opaque

A previous planning session on this branch also identified this same gap, confirming it
is the right priority.

### What I Proposed

**Plan:** `PLAN-20260308-compilation-pipeline-docs.md`

Three new internals documents:
1. **`computation-graph-to-process-tree.md`** — How `Producer` expressions become
   `Process` trees (CollectionProducerComputation, OperationList composition, dependency
   ordering)
2. **`process-optimization-pipeline.md`** — How `Process.optimize()` transforms trees
   (isolation decisions, ParallelismTargetOptimization scoring, segmentation)
3. **`backend-compilation-and-dispatch.md`** — How optimized trees compile to native
   code and dispatch to hardware (AcceleratedOperation lifecycle, InstructionSetManager
   caching, HardwareOperator backends)

### What Comes Next

After the compilation pipeline documentation:
1. **Memory management architecture docs** — The multi-tier memory model (heap, off-heap,
   device memory) and synchronization between providers
2. **ML inference internals** — How `transformer` and `feedForward` build computation
   graphs, KV cache management, StateDictionary weight layout
3. **Code quality pass** — `@TestDepth` standardization, physics README expansion
4. **Proof of value** — With documentation solid, shift focus to demonstrating the
   platform's capabilities on real workloads

### Reflection on Balance

We are correctly focused on documentation right now. The platform has strong code quality
(recent branches show careful review practices, duplication elimination, and consistent
patterns) and solid recent performance work (similarity optimization, audio loop LICM).
But the documentation of the core compilation pipeline — the thing that makes this
platform unique — is a genuine gap. Filling it is the highest-leverage action available.

Once the compilation pipeline, memory architecture, and ML inference internals are
documented, the platform will be well-positioned for a shift toward proof-of-value work.
The documentation foundation will make it possible for AI agents to reason about and
extend the platform's core capabilities, which is exactly what we need for the
self-understanding aspiration.
