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

## Planning History

### 2026-03-09 — Document the ML Inference Pipeline Internals

**Plan:** [`PLAN-20260309-ml-inference-pipeline-docs.md`](PLAN-20260309-ml-inference-pipeline-docs.md)
**Category:** Documentation

#### What I Investigated

With the compilation pipeline documentation completed in the prior session (three internals
docs: `computation-graph-to-process-tree.md`, `process-optimization-pipeline.md`,
`backend-compilation-and-dispatch.md`), I evaluated the two documentation gaps identified
in the previous planning cycle:

1. **Memory management architecture** — The prior session flagged this as the #2 priority.
   However, investigation revealed that `hardware/README.md` already contains 350+ lines
   of memory management documentation covering zero-copy delegation, thread-local arena
   allocation (Heap), GC-integrated native memory, memory versioning, argument aggregation,
   and memory replacement for kernels. While there's no standalone internals doc, the
   coverage in the README is substantial and actionable. This gap is real but smaller than
   initially assessed.

2. **ML inference pipeline** — The ML module has good API-level documentation (README.md,
   CLAUDE.md) covering `AttentionFeatures`, `AutoregressiveModel`, `StateDictionary`, and
   model implementation patterns. However, there is **no internals documentation** explaining
   how the inference pipeline actually works — specifically:
   - How KV caches are allocated, written at each position, and read during attention
   - The GQA expansion strategy (expand at cache write time, not read time)
   - How `AutoregressiveModel` coordinates the two-phase generation loop (prompt vs. generation)
   - How position tracking feeds into RoPE computation and cache indexing
   - How causal masking prevents attention to future cache positions

   These are the mechanics that anyone extending, optimizing, or debugging the inference
   pipeline needs to understand. The ML README says "KV Caching" is a feature but never
   explains the implementation.

#### Why This Task

The ML inference pipeline documentation is more impactful than memory management for
three reasons:

1. **Directly enables proof-of-value work.** The next major milestone for the platform is
   demonstrating real ML capabilities — model replication, inference optimization, eventually
   self-hosted training. Every one of these tasks requires understanding how attention,
   caching, and autoregressive generation work together. This doc is the prerequisite.

2. **The gap is deeper.** Memory management has substantial README coverage; the inference
   pipeline's internal mechanics are genuinely undocumented. The `attention()` method in
   `AttentionFeatures.java` is ~200 lines of carefully orchestrated cache management,
   GQA expansion, causal masking, and RoPE application — none of which is explained
   outside the source code.

3. **Advances the self-understanding vision.** If we want models to reason about the
   platform's ML capabilities, the inference pipeline must be documented in a way that
   is precise and mechanically understandable. This doc teaches both humans and future
   models how the platform does ML inference.

#### What Comes Next

After ML inference pipeline documentation:

1. **Documentation:** Memory management internals doc — consolidate and extend the
   hardware/README.md coverage into a dedicated internals doc with architecture diagrams.
   This would complete the "big three" subsystem docs (compilation, inference, memory).

2. **Code Quality:** `@TestDepth` standardization on expensive tests. Small consistency
   win that's been deferred appropriately.

3. **Performance/Proof of Value:** With compilation pipeline, inference pipeline, and
   memory management all documented, we'll be in a strong position to propose a real
   proof-of-value task — likely inference optimization or a new model replication that
   exercises the documented subsystems.

#### Balance Reflection

This is the second planning cycle, and we're still in Documentation. That's correct —
we identified three major subsystem documentation gaps (compilation pipeline, inference
pipeline, memory management), and we've completed one. The inference pipeline is the
natural second priority because it most directly enables the ML proof-of-value work that
represents the platform's ultimate trajectory.

After this cycle, I expect one more documentation pass (memory management) before we can
confidently move to code quality or performance work. The documentation investment is
paying off: each new doc builds on the previous ones and makes future development faster.
The compilation pipeline docs are already referenced by the inference pipeline plan, and
the inference pipeline docs will be essential context for any future ML optimization work.

We're building toward the moment when we can say: "the platform's core subsystems are
thoroughly documented, the code quality is strong, and we're ready to push the boundaries
of what it can do." That moment is approaching.

---

### 2026-03-08 — Document the Compilation Pipeline

**Plan:** [`PLAN-20260308-compilation-pipeline-docs.md`](PLAN-20260308-compilation-pipeline-docs.md)
**Category:** Documentation

#### What I Investigated

I conducted a comprehensive survey of the platform's documentation and code quality to
determine where the most impactful work lies.

**Documentation survey findings:**
- Module READMEs: 93% coverage (27/29 modules). Missing: `ml-djl`, `ml-onnx`, `flowtreeapi`.
- Class-level javadoc on 12 key public classes: 100% coverage, quality is excellent.
  `Producer`, `Expression`, `Scope`, `Cell`, `Hardware` all have comprehensive,
  well-structured javadoc with usage examples.
- Top-level docs: strong. 7 tutorials, 16 internals docs, quick reference, 23 module
  HTML pages.
- The CellList subsystem is exceptionally well documented (4 dedicated internals docs).
- Expression evaluation is documented, but stops at Scope generation.

**The critical gap:** The compilation pipeline — the path from `Producer<T>` graphs to
native GPU/CPU kernels — has no dedicated documentation. This is the platform's most
complex and differentiating subsystem. The 16 existing internals docs cover adjacent
topics well, but none explain:
- How producer graphs become process trees
- How process trees are optimized (the `optimize()` pipeline)
- How optimized processes compile to backend-specific native code (C, OpenCL, Metal)
- How backend selection and dispatch work

This gap affects every category downstream. You cannot optimize a pipeline you don't
understand. You cannot add new backends without understanding dispatch. And you cannot
train models to understand the platform if its most complex subsystem is undocumented.

**Code quality survey findings:**
- Overall grade: A. Test convention compliance: A+. Code duplication: A. Policy violations: none.
- Minor issues: some expensive tests missing `@TestDepth` annotations, 506 TODO/FIXME
  comments (mostly speculative). These are real but not urgent — the code quality is
  genuinely strong.

#### Why This Task

Following the priority order (Documentation → Code Quality → Performance → Proof of Value),
I stopped at Documentation because the compilation pipeline gap is significant. The
documentation is good in many areas — class javadoc, module READMEs, CellList architecture,
expression evaluation — but the most architecturally critical subsystem is undocumented.

This is exactly the kind of documentation that has compounding returns. Every future agent
session that touches hardware acceleration, every performance optimization task, every
backend extension task will benefit from this documentation existing.

#### What Comes Next

After this documentation is complete, the natural next steps are:

1. **Documentation:** Memory management architecture (multi-tier memory model, MemoryProvider
   implementations, GPU↔CPU transfers). This is the second-most-complex undocumented subsystem.
2. **Documentation:** ML inference pipeline internals (attention computation, KV cache,
   autoregressive generation). Needed before any proof-of-value ML work.
3. **Code Quality:** Standardize `@TestDepth` annotations on expensive tests (currently ~50%
   adoption). Small but valuable consistency improvement.
4. **Performance/Proof of Value:** With the compilation pipeline documented, we'll be in a
   much stronger position to propose performance work or proof-of-value projects that push
   the pipeline's limits.

#### Balance Reflection

This is the first planning cycle, so we're starting from the foundation. The documentation
priority is correct — the platform has strong class-level javadoc and module READMEs, but
the architectural documentation that connects everything together has critical gaps. The
compilation pipeline is the single most important topic to document because it is both the
most complex subsystem and the platform's core value proposition. Once the compilation
pipeline and memory management are documented, I expect we'll be ready to move to code
quality or performance work.
