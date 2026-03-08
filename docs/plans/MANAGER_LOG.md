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
