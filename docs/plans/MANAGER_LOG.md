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

### 2026-09-29 — Re-baseline LoRA fine-tuning backward-pass compile scaling

**Category:** Performance
**Branch:** `project/plan-20260929-043714`
**Plan:** [`PLAN-20260929-finetune-compile-scaling-rebaseline.md`](PLAN-20260929-finetune-compile-scaling-rebaseline.md)

#### Category assessment (priority order)

- **Documentation — excellent.** Every layer/domain/engine module has a README; `docs/internals/`
  now holds 36 pages spanning the whole story (Producer → process tree → process optimization →
  backend compilation/dispatch → `native-runtime-lifecycle`, which landed last cycle). The only
  catalogued gap left is the low-priority ONNX-induced-pressure research note in
  `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md`, explicitly "a research task in its own right." Not a
  flagship. Moved on.
- **Code quality — strong and actively owned.** The continuous QA pipeline (`qa/defect-*`,
  `qa/consolidate-*`, `qa/coverage-*`) merges fixes daily, and enforcement (checkstyle, code_policy,
  duplicate_code, test-integrity) is mechanical. The one flagged structural debt (split
  `io.almostrealism.collect`) is real but minor and high-churn. No neglected, high-value flagship
  here. Moved on.
- **Performance — the category.** The compile-time front saw a decisive win: the kernel-series /
  `Expression.sequence` analysis that dominated expression simplification was rebuilt on
  `feature/cl-profile-perf` (2026-09-08), taking `convDeltaSmall` 172 s → 1.15 s and `upsample`
  233 s → 4 s (see `CONVOLUTION_COMPILE_TIME.md` §Resolution). That is genuine open leverage — see
  below.

#### The finding that decided it

`FINE_TUNE_FAIL.md` is the platform's authoritative verdict on training feasibility, and it says, in
bold, that production-scale LoRA fine-tuning is **infeasible** — backward-pass compile time scaling
super-linearly, projected to "days, not minutes" at embed=1024/depth=16. That measurement is from
**February 2026**. Its stage-detail profile names `expressionCacheMatch` (~1375.6 s) as the *dominant*
backward-compile cost, with the kernel-series / `Sum.simplify → getSeries → Expression.sequence`
analysis a *secondary* consumer (~150 s). Both fronts have since moved, but by *different* work, and
it is worth keeping them straight: the September `ExplicitExpressionMatrix` cache-bypass and
on-demand-entry changes attacked the dominant `expressionCacheMatch` cost directly (isolated
`testSingleAttentionBackward` ~186 s → ~3.6 s on Metal), while the `feature/cl-profile-perf`
kernel-series rebuild (`convDeltaSmall` 172 s → 1.15 s, ~150×) attacked the secondary one. The
~150× convolution figure therefore is *not* a prediction for the fine-tuning backward pass; it is
the smaller of the two levers. Nobody has re-measured the transformer backward pass against either.
The verdict that gates the entire proof-of-value trajectory is very likely stale — and stale in the
pessimistic direction.

Separately confirmed while investigating: frozen-weight gradient pruning already exists at the
layer level (`DefaultGradientPropagation` only calls `delta()` on the trainable weight list; LoRA
excludes base weights), so the remaining backward-compile cost is the *expression-tree size* of
differentiating the trainable weights through the deep forward graph — precisely the territory
both the `ExplicitExpressionMatrix` cache work and the kernel-series work attack. That reinforces
the premise that the old numbers should move.

#### Why this task

It recovers the truth about the platform's most strategically important performance number, using
the measurement harness that already exists (`AggressiveFineTuningTest`), against optimizations
already on `master`. It is deliberately scoped to measure → profile (via `ar-profile-analyzer`) →
re-baseline `FINE_TUNE_FAIL.md` → name the next lever, with an *only-if-clean* conditional step to
land one small optimization. Crucially it stays clear of the in-flight `feature/lora-gradients`
core-autodiff work (sparse Jacobians, `Sum` reordering, memoization gating) — avoiding the
add/revert collision that has bitten this area before.

#### Balance across categories

Foundations are in good shape and under continuous ownership. The right move now is not to open
another deep front but to *cash in* the compile-time win the platform already has — establishing
what training is now possible so the next cycle can plan against reality.

#### What comes next

1. This plan executes: current scaling numbers, a fresh embed=64 backward-compile profile, a
   rewritten `FINE_TUNE_FAIL.md`, and a named next lever.
2. If the verdict flips toward feasible: scope the **minimal end-to-end self-hosted training run** —
   a tiny model trained on the platform's own docs/source via `ModelOptimizer` — the first concrete
   step toward software that studies itself. (This was item 4 of the prior cycle's "what next".)
3. If a clean compile-time lever was identified but deferred, promote it to its own performance plan.
4. Still open from prior cycles: the ONNX-pressure documentation note; the `io.almostrealism.collect`
   package split as a code-quality candidate.

### 2026-09-26 — Native Runtime Lifecycle Documentation

**Category:** Documentation
**Branch:** `project/plan-20260926-172935`
**Plan:** [`PLAN-20260926-native-runtime-lifecycle-docs.md`](PLAN-20260926-native-runtime-lifecycle-docs.md)

#### Context: the log was restarted

The previous log was deleted on 2026-04-06 together with a batch of completed or obsolete
plans. Between April and now, planning happened through many focused plan documents in
`docs/plans/` and continuous QA agents (`qa/docs-*`, `qa/defect-*`, `qa/coverage-*`,
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

- `docs/internals/` covers the compile side of the pipeline well.
- The hardware README (~1150 lines) covers memory *usage patterns* thoroughly.
- `docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` (May 2026) lists five gaps exposed when a
  native-crash investigation in a client application produced three mechanically impossible
  hypotheses. I checked each against source today: none is closed. `NativeInstructionSet` still
  defers the thread-safety question; `NativeCompiler` has no library-lifetime Javadoc (and the
  default library directory, `SystemUtils.getExtensionsPath()`, has no visible delete-on-start —
  the plan's claim that libraries are "destroyed every JVM start" is itself unverified);
  the `MEMORY_SCALE`-derived limit is enforced in `MetalMemoryProvider`, `CLMemoryProvider` and
  `NativeMemoryProvider` (the two GPU providers throw a "Memory Max Reached" `HardwareException`,
  `NativeMemoryProvider` a "Memory max reached" one), but no doc says
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

1. This plan is executed: the native runtime lifecycle internals page and its Javadoc have landed,
   and `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` is trimmed to its one remaining, out-of-scope entry.
   Follow up on that entry — the ONNX-induced-pressure gap — as a separate documentation task in
   `extern/ml-onnx/`, then delete the gap document.
2. Revisit the deferred structural debt from `docs/MODULE_REVIEW.md` (split
   `io.almostrealism.collect` package) as a code-quality candidate.
3. Performance: compile-time reduction building on `CONVOLUTION_COMPILE_TIME.md`.
4. Proof of value: scope a minimal end-to-end training run of a tiny transformer on platform
   documentation using `ModelOptimizer`.
