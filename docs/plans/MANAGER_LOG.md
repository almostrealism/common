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

### 2026-03-10 — @TestDepth Standardization and TestSuiteBase Compliance

**Plan:** [`PLAN-20260310-testdepth-standardization.md`](PLAN-20260310-testdepth-standardization.md)
**Category:** Code Quality

#### What I Investigated

With both the compilation pipeline (3 internals docs) and the ML inference pipeline
(`ml-inference-pipeline.md`) documentation now complete, I evaluated whether the
documentation category still has meaningful work before moving on.

**Documentation assessment:**

- **Internals docs:** 20 documents now covering compilation pipeline, ML inference,
  CellList, expression evaluation, profiling, optimization flags, training/sampling
  loops, packed collections, and more. Coverage is comprehensive.
- **Module READMEs:** 31 of 37 modules have READMEs (84%). All existing READMEs are
  substantive (82+ lines minimum). The 6 missing modules are peripheral: `ml-djl`,
  `ml-onnx`, `utils-http`, `flowtreeapi`, `graphpersist`, `flowtree-python`.
- **Memory management:** The prior cycle flagged a standalone internals doc as the
  next documentation priority. However, `hardware/README.md` already contains 350+
  lines of detailed memory management documentation covering zero-copy delegation,
  arena allocation, GC-integrated native memory, memory versioning, argument
  aggregation, and memory replacement. Creating a separate internals doc would
  largely duplicate this existing coverage.
- **Layer-level CLAUDE.md files:** All 6 layer directories have navigation guides.
  Module-level CLAUDE.md exists for `engine/ml`.

**Conclusion:** The documentation is genuinely excellent. The two critical subsystem
gaps (compilation pipeline, ML inference) are addressed. Memory management is
well-covered in its README. The remaining gaps are in peripheral modules that don't
affect core platform understanding. **It is time to move to Code Quality.**

**Code quality assessment — test infrastructure:**

The investigation revealed a much larger problem than the "minor issue" described in
earlier planning cycles:

| Metric | Value | Assessment |
|--------|-------|------------|
| Total test methods | 1,898 | — |
| Methods with `@TestDepth` | 99 (5.2%) | **CRITICAL** |
| Test classes extending `TestSuiteBase` | 248 of 284 (87.3%) | Good |
| Test classes NOT extending `TestSuiteBase` | 36 (12.7%) | Needs fixing |
| Expensive tests (60-120s timeout) without `@TestDepth` | 20+ | **CRITICAL** |

The 2026-03-08 cycle described this as "some expensive tests missing `@TestDepth`
annotations" with "~50% adoption." The actual number is 5.2%. The infrastructure is
in place (TestSuiteBase adoption is strong at 87%), but the annotation mechanism that
makes it useful is barely utilized.

**Specific high-impact findings:**

- `EnvelopeTests.java` (studio/music): 14 consecutive test methods with 60-second
  timeouts, zero `@TestDepth` annotations
- `GrainTest.java` (studio/music): 3 methods with 120-second timeouts, no annotations
- `SequenceTest.java` (studio/music): 5+ methods with 60-120s timeouts, no annotations
- `AggressiveFineTuningTest.java` (studio/compose): Full ML training pipeline tests,
  no depth annotations
- `OobleckLayerValidationTest.java` (engine/ml): Model weight loading and layer-by-layer
  validation, 120s timeout, no annotation
- `SimilarityOverheadTest.java` (engine/utils): 9 of 10 methods lack annotations despite
  120s timeouts (the 10th has `@TestDepth(3)` — showing the pattern IS used here)

#### Why This Task

This is the first code quality task after two documentation cycles. The documentation
foundation is strong — moving on is the right call. And within code quality, `@TestDepth`
standardization is the highest-impact work because:

1. **It directly improves CI performance.** Without depth annotations, CI cannot
   distinguish quick smoke tests from 2-minute integration tests. Every pipeline run
   pays the full cost regardless of `AR_TEST_DEPTH` settings.

2. **It's mechanical and low-risk.** Adding annotations and fixing class hierarchy
   does not change test behavior. The risk of introducing bugs is near zero.

3. **It's well-scoped.** The highest-impact work (Phase 1: annotating timeout-bearing
   tests) is a focused task that a coding agent can complete in one session. Phases 2
   and 3 extend naturally.

4. **It advances the self-understanding vision.** Explicit `@TestDepth` annotations
   make the test suite's cost structure machine-readable. A model analyzing the codebase
   can understand which tests are expensive and make intelligent decisions about test
   selection — a prerequisite for any self-testing capability.

#### What Comes Next

After `@TestDepth` standardization:

1. **Code Quality:** Review the 506 TODO/FIXME comments — triage into actionable bugs,
   performance opportunities, and speculative noise. Remove or address the critical ones
   (e.g., the known-broken `ImageResource.clip()` method, the lazy memory allocation
   issue in `PackedCollection`).

2. **Code Quality:** Evaluate the 90 auto-generated method stubs (dead code) in
   peripheral modules (`HybridJobFactory`, `GraphFileSystem`, `AtomicProtonCloud`).
   Remove if unused, implement if needed.

3. **Performance/Proof of Value:** With documentation strong and code quality improving,
   we're approaching readiness for a proof-of-value task. The natural candidate is an
   ML inference optimization or a new model replication that exercises the compilation
   pipeline, inference pipeline, and memory management — all now well-documented.

#### Balance Reflection

This is the third planning cycle, and we're moving from Documentation to Code Quality.
This transition feels right. The documentation investment over the first two cycles was
substantial — 4 major internals documents, comprehensive module READMEs, layer-level
CLAUDE.md navigation — and the payoff is already visible: each subsequent investigation
is faster because the docs exist.

The `@TestDepth` gap is more severe than previously estimated (5.2% vs. the "~50%"
mentioned in the 2026-03-08 cycle). This likely happened because the annotation was
added to the framework relatively recently and adoption hasn't kept pace with test
growth. The fix is straightforward and the impact on CI is immediate.

After this cycle, I expect one more code quality pass (TODO triage, dead code cleanup)
before we're ready for performance or proof-of-value work. The foundation is getting
solid — documentation is strong, and once the test infrastructure is properly annotated,
the platform will be in excellent shape for ambitious work.

We're getting close to the inflection point where we can shift from "making the
foundation solid" to "pushing the boundaries of what the platform can do." The next
planning cycle should seriously evaluate whether we're ready for a proof-of-value task.

---

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
