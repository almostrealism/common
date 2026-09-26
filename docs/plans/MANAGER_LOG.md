# Project Manager Log

The living journal of the Almost Realism project management process: current thinking about
where the platform is heading, why specific tasks are chosen, and how each piece of work
connects to the larger vision. This is a snapshot of current thinking, not an archive — older
entries are condensed or pruned to keep it under roughly 50,000 characters.

---

## Current State

### Strategic Direction

1. **Foundation** — documentation, clean code, comprehensive tests
2. **Performance** — hardware-accelerated computation that rivals dedicated frameworks
3. **Proof of Value** — model replication, audio generation, distributed training
4. **Self-Understanding** — training modest language models locally that understand the
   platform itself, so the software grows alongside its own capabilities

### Task Categories (priority order)

1. Documentation — can the platform be understood by humans and AI systems?
2. Code Quality — is the codebase clean, consistent, well-tested?
3. Performance — can it handle real workloads efficiently?
4. Proof of Value — can we demonstrate compelling capabilities?

---

## Planning History

### 2026-09-26 — Native Runtime Lifecycle Documentation

**Category:** Documentation
**Branch:** `project/plan-20260926-172935`
**Plan:** [`PLAN-20260926-native-runtime-lifecycle-docs.md`](PLAN-20260926-native-runtime-lifecycle-docs.md)

#### Context: the log was restarted

The previous log was deleted on 2026-04-06 together with a batch of completed or obsolete
plans. Between April and now, planning happened through many focused plan documents (79 files
in `docs/plans/`) and continuous QA agents (`qa/docs-*`, `qa/defect-*`, `qa/coverage-*`,
`qa/performance-*`, `qa/consolidate-*`) that merge small fixes daily. This entry restarts the
narrative. What carried over from the old log, condensed:

- **March 2026:** the compilation-pipeline internals docs (graph → process tree, process
  optimization, backend compilation and dispatch) and the ML inference pipeline doc were planned
  and have since landed in `docs/internals/`. TODO/FIXME work pivoted from mass triage to fixing
  items individually. The module layer review (`docs/MODULE_REVIEW.md`) reorganized modules and
  deferred structural debt (split `io.almostrealism.collect` package, JobOutput migration).
- **April–September 2026:** heavy investment in agent infrastructure (FlowTree, ar-manager,
  enforcement hooks, test-integrity checks), kernel correctness investigations (see
  `docs/journals/`), audio/PDSL work, and Stable Audio 3 / Qwen performance tracks.

#### What I investigated

- `docs/internals/` now has 35 pages; the compile side of the pipeline is well covered.
- The hardware README (~1150 lines) covers memory *usage patterns* thoroughly.
- `docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` (May 2026) lists five gaps exposed when a
  native-crash investigation in a client application produced three mechanically impossible
  hypotheses. I checked each against source today: none is closed. `NativeInstructionSet` still
  defers the thread-safety question; `NativeCompiler` has no library-lifetime Javadoc (and the
  default library directory, `SystemUtils.getExtensionsPath()`, has no visible delete-on-start —
  the plan's claim that libraries are "destroyed every JVM start" is itself unverified);
  the `MEMORY_SCALE`-derived limit is enforced in `MetalMemoryProvider`, `CLMemoryProvider` and
  `NativeMemoryProvider` (each throws a "Memory max reached" `HardwareException`), but no doc says
  so; there is no internals page for any of it.
- Recent QA documentation passes fix individual stale claims (line-number citations, a
  non-existent `PackedCollection.load(File)`), but they do not create missing conceptual docs.

#### Why this task

Documentation is the first category, and this is its most consequential remaining gap: the
*back half* of the execution story — what happens to a compiled kernel and the memory it reads
after compilation. It is the part people and agents get wrong under pressure (crash triage),
and the part any future kernel-caching or compile-time optimization must reason about. The
plan requires every claim to be verified against source or an empirical run, and to record —
not fix — any defect found, keeping the task a pure documentation task.

#### Balance across categories

Documentation of the conceptual core is now nearly complete; after this task the internals
corpus tells the whole story from Producer to released memory. Code quality is being handled
continuously by QA agents. The next planning cycles should lean toward **performance**
(compile-time and kernel reuse, where an accurate lifecycle doc is a prerequisite) and then
**proof-of-value** — in particular a small, self-hosted language-model training run, which is
the most direct step toward the self-understanding goal.

#### What comes next

1. Execute this plan; delete `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` when covered.
2. Revisit the deferred structural debt from `docs/MODULE_REVIEW.md` (split
   `io.almostrealism.collect` package) as a code-quality candidate.
3. Performance: compile-time reduction building on `CONVOLUTION_COMPILE_TIME.md`.
4. Proof of value: scope a minimal end-to-end training run of a tiny transformer on platform
   documentation using `ModelOptimizer`.
