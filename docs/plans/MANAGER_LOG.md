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

### 2026-03-12 — TODO/FIXME Triage and Dead Code Cleanup

**Plan:** [`PLAN-20260312-todo-triage-dead-code.md`](PLAN-20260312-todo-triage-dead-code.md)
**Category:** Code Quality

#### What I Investigated

The prior cycle completed @TestDepth standardization and TestSuiteBase compliance
improvements (merged from `project/plan-20260309-213715`). The Manager Log explicitly
identified this task as the next step: "Review the 506 TODO/FIXME comments" and
"Evaluate the 90 auto-generated method stubs (dead code)."

I conducted a comprehensive survey of the codebase to validate and refine the scope.

**Documentation re-assessment (confirming it's still strong):**

| Metric | Value |
|--------|-------|
| Internals docs | 20 documents |
| Module READMEs | 24/25 (96%) — only `utils-http` missing |
| Layer-level CLAUDE.md files | All 7 present |
| Module-level CLAUDE.md | `engine/ml` |
| Javadoc coverage (base/relation) | 100% |
| Javadoc coverage (base/code) | 9% (201 undocumented classes) |

The base/code javadoc gap is notable (201 public classes without javadoc in the expression
tree / code generation module), but this is a massive mechanical task better suited for a
dedicated documentation cycle. The existing internals docs and module READMEs provide
strong conceptual coverage. **Documentation remains strong; Code Quality is still the
right category.**

**Code quality survey — TODO/FIXME and dead code:**

| Finding | Details |
|---------|---------|
| Total TODO/FIXME comments | 506 across 192+ files |
| Auto-generated method stubs | 8 files (HybridJobFactory, GraphFileSystem, physics classes) |
| Confirmed broken code | `ImageResource.clip()` — `cx` param unused, produces wrong output |
| Known generics bug | `CollectionVariable.java` — type hierarchy inconsistency |
| Confirmed dead code | `HybridJobFactory` (0 instantiations), `AlmostCache` (throws RuntimeException) |
| GraphFileSystem TODOs alone | 26 comments — permission checks, unimplemented NFS ops |

**@TestDepth post-standardization status:**

| Metric | Before | After |
|--------|--------|-------|
| @TestDepth adoption | 5.2% (99/1,898) | 8.4% (99/1,175) |
| TestSuiteBase compliance | 87.3% (248/284) | 93% (174/187) |
| Remaining TestSuiteBase violations | 36 | 13 (all in audio modules) |

The standardization work improved compliance but there are still 13 non-compliant test
classes (all in `studio/compose` and `engine/audio`). These are residual from the prior
cycle and could be addressed opportunistically, but are not the focus here.

#### Why This Task

The Manager Log has been pointing at this task for two cycles. It's the natural
continuation of code quality work after @TestDepth standardization:

1. **Confirmed bugs are hiding in TODOs.** The `ImageResource.clip()` method has been
   broken for an unknown period — the `cx` parameter is declared but the loop uses `cw`
   instead. This is exactly the kind of bug that TODO comments are supposed to flag, but
   when there are 506 of them, the signal gets lost.

2. **Dead code creates false complexity.** `HybridJobFactory` is 126 lines of empty
   methods that are never called. `AlmostCache` exists only to throw an exception. Six
   physics/color classes contain auto-generated stubs. These pollute IDE searches, confuse
   AI agents, and inflate the apparent size of the codebase.

3. **This is the last code quality gate before proof-of-value.** The Manager Log has
   consistently said: documentation (done) → @TestDepth (done) → TODO triage and dead
   code → then we're ready for ambitious proof-of-value work. This task clears that gate.

4. **Improves signal-to-noise for self-understanding.** When future models analyze this
   codebase, every stale TODO and dead method is noise that degrades comprehension. A
   clean codebase is a learnable codebase.

#### What Comes Next

After TODO triage and dead code cleanup, the platform will be in genuinely strong shape
across both documentation and code quality. The next planning cycle should seriously
evaluate proof-of-value work:

1. **Proof of Value (likely next):** The compilation pipeline, ML inference pipeline, and
   memory management are all well-documented. @TestDepth is standardized. Dead code is
   cleaned up. The natural next step is a proof-of-value task that exercises the platform's
   capabilities — candidates include:
   - ML inference optimization (e.g., benchmarking attention performance)
   - New model replication (exercising the full pipeline end-to-end)
   - Audio generation demonstration
   - Self-hosted training of a small model

2. **Code Quality (if needed):** The base/code module has 201 undocumented public classes.
   If proof-of-value work reveals friction from missing javadoc in the expression tree
   hierarchy, a targeted documentation cycle could address the most critical classes.

3. **Performance:** With proof-of-value work underway, performance bottlenecks will become
   concrete rather than theoretical. Address them as they surface.

#### Balance Reflection

This is the fourth planning cycle. The trajectory has been:
- Cycle 1-2: Documentation (compilation pipeline, ML inference) — **complete**
- Cycle 3: Code Quality (@TestDepth standardization) — **complete**
- Cycle 4: Code Quality (TODO triage, dead code) — **this task**

We've spent three cycles on foundation work (documentation + code quality). That
investment is paying off — investigations are faster, the docs are excellent, test
infrastructure is improving. But we're at the point of diminishing returns on
foundation work. After this cycle, the foundation should be solid enough that we can
shift confidently to proof-of-value work.

The inflection point the prior cycle predicted is here. This TODO triage is the last
cleanup pass. The next cycle should be the first proof-of-value task — something that
demonstrates what the Almost Realism platform can actually do with its newly documented,
newly cleaned codebase. That's where the excitement is, and the foundation is nearly
ready to support it.

---

### 2026-03-10 — @TestDepth Standardization and TestSuiteBase Compliance

**Plan:** [`PLAN-20260310-testdepth-standardization.md`](PLAN-20260310-testdepth-standardization.md)
**Category:** Code Quality

Evaluated documentation (confirmed excellent: 20 internals docs, 84% module READMEs, all
CLAUDE.md files present). Moved to Code Quality. Found @TestDepth adoption at only 5.2%
(99/1,898 methods) — far worse than the "~50%" estimate from Cycle 1. Created a three-phase
plan: (1) annotate timeout-bearing expensive tests, (2) fix non-TestSuiteBase classes,
(3) broader review. Work was executed and merged. See plan document for details.

---

### 2026-03-09 — Document the ML Inference Pipeline Internals

**Plan:** [`PLAN-20260309-ml-inference-pipeline-docs.md`](PLAN-20260309-ml-inference-pipeline-docs.md)
**Category:** Documentation

Documented the ML inference pipeline internals: KV cache allocation and write-time GQA
expansion, `AutoregressiveModel` two-phase generation loop, RoPE position tracking, causal
masking, and cross-attention for encoder-decoder models. Created
`docs/internals/ml-inference-pipeline.md`. This was prioritized over memory management
(which had substantial coverage in `hardware/README.md` already) because it directly
enables proof-of-value ML work.

---

### 2026-03-08 — Document the Compilation Pipeline

**Plan:** [`PLAN-20260308-compilation-pipeline-docs.md`](PLAN-20260308-compilation-pipeline-docs.md)
**Category:** Documentation

First planning cycle. Comprehensive survey found the compilation pipeline (Producer graphs
→ Process trees → native kernels) as the #1 documentation gap. Created three internals
docs: `computation-graph-to-process-tree.md`, `process-optimization-pipeline.md`,
`backend-compilation-and-dispatch.md`. Code quality was graded A overall with the TODO/FIXME
and @TestDepth issues noted for future cycles.
