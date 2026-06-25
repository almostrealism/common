# Compile-Determinism Investigation Journal

**Branch:** `feature/ml-dit-memory-tokens`
**Started:** 2026-06-19
**Goal:** Fix cross-compilation nondeterminism — two separately-compiled, structurally-identical
compute graphs (same weights, same input) diverge at an **odd** internal sequence length, but are
byte-identical at an even one. Same compiled graph re-run = `0.0`, so it is a **compile-time**, not
runtime, problem.

Companion repro test: `engine/ml/src/test/java/org/almostrealism/ml/CompileDeterminismReproductionTest.java`
Prior diagnosis doc: `docs/plans/compile-determinism/REPRODUCTION_AND_DIAGNOSIS.md`
Prior memories: `1bd55507` (root-cause matrix), `475e154b` (review note), `1528de11` (precedent
`KernelTraversalOperationGenerator` IdentityHashMap fix), `8d6744b3` (progress).

---

## Established facts (do not re-derive)

Two separate compiles, same input, `maxAbsDiff`:

| config | internal seqLen | diff |
|---|---|---|
| PREPEND audio 8 → 9 | 9 (odd) | ~1e-3 (probabilistic; sometimes 0.0) |
| ADALN 8 → 8 | 8 (even) | 0.0 always |
| +memtokens → 10 | 10 (even) | 0.0 always |
| ADALN 8 + 1 memtoken → 9 | 9 (odd) | huge (1728 — random adaLN gates amplify) |
| same compiled model, re-run | any | 0.0 always |
| isolated softmax / SDPA over odd axis | — | 0.0 (NOT the source) |

- Minimal trigger = **one self-attention sub-block at odd seqLen** (`hand attn-only @9`). FFN sub-block
  is deterministic.
- Profile artifacts (diagnosis §5): the two compiles reach **different operation groupings** — one
  fuses the attention reductions, the other emits a swarm of separate `collectionSumComputation`
  kernels (~3× slower). **This gross difference appears even in the byte-identical even-seqLen control
  (678 vs 2588 nodes, diff 0.0).**

---

## CRITICAL REFRAME (the lever for this whole investigation)

Because the structural divergence (fused vs swarm) is present **even when the numeric diff is 0.0**,
the nondeterminism is **observable deterministically at the structural level** — we do NOT have to
rely on the flaky, probabilistic FP divergence. 

**Plan:** build a narrow test that compiles the same graph twice and asserts the two compiles produce
the *same operation-graph structure* (node count / emitted source). That should fail every time,
regardless of seqLen parity, and is a far better bisection signal than FP divergence. The numeric
divergence is then a *downstream* effect (odd seqLen makes the two structures numerically
non-equivalent; even seqLen makes them numerically equivalent — a separate, secondary question).

---

## Hypotheses

- **H1 — identity-hash-ordered grouping/fusion in the optimizer.** A `HashSet`/`HashMap` keyed on
  `Process`/`Computation` objects (default identity hashCode) is iterated during `optimize()`; order
  decides fusion → different grouping per compile.
- **H2 — identity-hash-ordered variable/argument ordering in `Scope`/codegen.** Scope collects
  variables/arguments into hash collections; emission order of accumulation terms varies → different FP
  order. (Consultant flagged `MemoryDataArgumentMap` aggregation + Scope variable ordering.)
- **H3 — expression CSE/dedup term order identity-dependent → reduction order.** Sum of many terms
  collected in an identity-ordered set → `a+b+c…` order varies → FP non-associativity.
- **H4 — `HashMap<String,…>` where the String keys embed identity hashes** (e.g. `v_<identityHashCode>`
  variable names). Sorted-by-name is then still nondeterministic. (This is the *class* of the precedent
  fix `1528de11`.)
- **H5 — `parallelStream` nondeterministic reduction** during compile-time simplification (memory
  `a153d393` mentions parallel streams over ~175k indices). Would likely also hit even seqLen → lower
  priority.
- **H6 — `Sum.simplify` / `generateReordering` term ordering** non-stable across compiles.
- **H7 — first-vs-second compile (warmup / static-cache state).** *Leading alternative.* The first
  compile in a JVM is cold; by the second, a static cache / intern table / `Expression.value()`
  memo / instruction cache is populated, so the second compiles differently (A=fused cold, B=swarm
  warm). This is a **deterministic order effect, NOT identity ordering** — and the magnitude looks
  probabilistic only because the *weights* are random. **Different fix entirely.** Must disambiguate
  H7 vs H1/H3 before anything else.

The prior memories *assert* "identity-hash-dependent ordering," but the evidence (A always fused / B
always swarm, ~3× slower) is equally consistent with H7. Treat the identity-hash conclusion as **not
yet proven**.

---

## Disambiguation plan (do this FIRST)

1. Compile the SAME minimal graph N=4 times in one JVM; fingerprint each compile (node count /
   generated-source hash). `[X,Y,Y,Y]` ⇒ warmup state (H7). Random / no stable pattern ⇒ identity
   ordering (H1/H3). Re-run the JVM: if the *first* compile's fingerprint is stable across JVM runs ⇒
   deterministic (H7); if it varies ⇒ identity (H1).
2. Compile two *different* trivial graphs in both orders; check whether "first compiled" is always the
   fused one (⇒ H7) or whether it tracks the graph (⇒ structural).

---

## Investigation strategy

- Narrow, fast, deterministic tests over probabilistic FP-divergence. Build a *library* of them.
- Evidence from generated code (profile analyzer `get_source`, `list_children`) before any conclusion.
- May extend the profile-analyzer MCP with a structural-diff (node-count / op-histogram / per-node
  source diff) capability if manual diffing is too slow.
- No fix until the mechanism is proven by a test that fails before and passes after.

---

## Map of hash-ordered sites (base/, non-test)

- `IdentityHashMap` (all *visited-set* usage so far — check if any are *iterated* for emission):
  - `base/code/.../scope/ScopeExpressionCollector.java:83` `IdentityHashMap<Scope<?>,Boolean> visited`
  - `base/code/.../scope/ExpressionDuplicationScanner.java:88` `IdentityHashMap<Expression<?>,Boolean> visited`
  - `base/code/.../sequence/IndexValues.java:135` `valueCache = new IdentityHashMap<>()`
- No direct `System.identityHashCode` in base/ (could still leak via default `Object.hashCode()`).

---

## Log

- **2026-06-19** — Kickoff. Ran consultant + recall; pulled precedent fix `1528de11`. Established the
  reframe above. Created journal + task list. Next: read the three pipeline internals docs, confirm
  whether the `1528de11` fix is present on this branch, and build the H7-vs-identity disambiguation probe.

- **2026-06-19 — DECISIVE: it is H7 (cold/warm), not identity ordering.** Added
  `compileOrderStructuralFingerprint` to the diagnostic harness; compiled the same graph 5× per shape
  in one JVM, fingerprinting `treeNodes/kernelSources` (test run `1cec1033`):
  - direct block **odd 9**: `[967/49, 1210/40, 1210/40, 1210/40, 1210/40]`
  - direct block **even 8**: `[993/49, 1236/40, 1236/40, 1236/40, 1236/40]`
  - attn-only **odd 9**: `[881/30 ×5]` (already warm — reuses `block` weights made device-resident by
    the earlier direct-block runs).

  Pattern = `[X, Y, Y, Y, Y]`: the **first** compile differs, all later compiles are identical. Identity
  ordering would scatter all five → ruled out. The first compile generates different *code* than
  subsequent ones, **deterministically**. Even-seqLen cold/warm structures differ but compute
  bit-identically (`0.0`); odd-seqLen differ numerically — this *is* the parity finding.

  The diagnosis's `compileTwiceMaxAbsDiff` **shares weights** between compile A and B, so A=cold,
  B=warm → the observed divergence. (Sharing weights to "isolate object identity" is exactly what
  creates the cold/warm split.)

  **Leading mechanism H7a — memory-residence-dependent argument aggregation.**
  `MemoryDataArgumentMap.get` decides `generateArg = md.getMem().getProvider() != kernelMemoryProvider`
  (line 215). Shared weights become device/kernel-resident after the first compile/forward, flipping
  the aggregation decision on later compiles → different kernel structure. Next: test H7a directly —
  (a) toggle `enableArgumentAggregation` off, (b) use fresh (non-shared) weights per compile; either
  should collapse the `[X,Y,Y,Y]` to a single fingerprint if H7a is right.

  NOTE on fingerprint confound: `kernelSources` may partly reflect instruction-cache hits vs misses,
  not just structure. But the numeric divergence (ground truth) proves the cold/warm code genuinely
  differs, and the `[X,Y,Y,Y]` step is robust.

- **2026-06-19 — H7a (aggregation) FALSIFIED. The cause is JVM-GLOBAL first-compile state, not
  per-weight residence.** `aggregationResidenceProbe` (run `c773c59e`), directBlock even8:
  - shared weights, agg **ON**: `[993/49, 1235/40, 1236/40, 1236/40]` (cold→warm)
  - **fresh** weights/compile, agg ON: `[1236/40 ×4]` — **all warm**. Fresh host-resident weights do
    NOT bring back the cold structure ⇒ the split is **not** per-weight memory residence; it is global
    state already warm by the time this 2nd condition runs.
  - shared weights, agg **OFF**: `[1148/30, 1848/5, 1848/5, 1848/5]` — the split **persists** without
    aggregation ⇒ aggregation is not the cause.

  Refined finding: a **global, set-once-on-first-compile** state flips a **fusion/simplification**
  decision — the warm compile fuses far more (30→5 kernel sources with agg off; 49→40 with agg on).
  Only the JVM's *very first* compile is truly cold; toggling the agg flag appeared to re-cool it
  (cond 3 went cold again), consistent with a signature-keyed instruction/kernel cache OR a lazily
  initialised compute-context/memory state whose first population changes later compiles.

  Consequence for methodology: each flag-bisection must start from a **fresh JVM** (one truly-cold
  compile per JVM). Next steps: (1) save cold (compile[0]) vs warm (compile[1]) profile XML for
  directBlock even8 and DIFF the generated reduction kernels — ground-truth of what changes; (2)
  bisect global-state flags from a cold start: `KernelSeriesCache.enableCache`,
  `Sum.enableGenerateReordering`, `KernelTraversalOperationGenerator.enableGeneration`, and the
  instruction-set cache.

  OPEN PARADOX to resolve: if "warm" merely reuses cached kernels, the math would be identical — yet
  the diagnosis shows a real numeric divergence. So the global state must change the *compilation* of
  the reduction op (not just cache its result). The cold/warm code diff (step 1) should expose how.

- **2026-06-19 — ROOT CAUSE CONFIRMED (H8): signature-keyed instruction-set reuse.** Profile diff of
  `coldwarm_directBlock_even8` A(cold) vs B(warm): cold = 678 nodes, FUSED (`f_assignment` semantic
  kernels, reductions inlined at 0.0 self-duration); warm = 2400 nodes, DECOMPOSED (swarm of
  `packedCollectionSubset` + `collectionAddComputation`, and the **same** `collectionSumComputation`
  reductions now ISOLATED — `IsolatedProcess(sum)` executing at 250–415 ms each). Warm runs ~2× slower.

  `coldWarmReuseOff` (run `519acb17`) set `ScopeSettings.enableInstructionSetReuse = false` from a cold
  JVM: even8 `[998/51, 997/51, 998/51, 998/51]` (kernelSources constant 51; the 998↔997 is a 1-node
  profiling wobble), odd9 `[972/51 ×4]` perfectly stable. **The split disappears with reuse off.**

  Root: `DefaultComputer.instructionsCache` — a `FrequencyCache` (cap 500) keyed by **scope signature
  alone**, on the `Hardware` singleton (persists across compiles). `AcceleratedComputationOperation`
  `getInstructionSetManager()` / `getExecutionKey()`: when `enableInstructionSetReuse && signature !=
  null`, the op uses `computer.getScopeInstructionsManager(signature, …)` (shared, cached) and a
  `ScopeSignatureExecutionKey`; otherwise a fresh per-op `ComputationInstructionsManager`. Signature is
  nulled for aggregation targets (`DelegatedProducer.signature`, `Assignment.signature`) — explaining
  why turning aggregation off changed the absolute structure but not the split.

  Mechanism (working model, to confirm at the substitution site): the **first** occurrence of a
  signature compiles fused/inlined; **later** occurrences — both within one compile and on subsequent
  compiles whose cache is pre-populated — reuse the cached manager as a separate ISOLATED kernel
  (`ProcessArgumentMap` / "process tree substitution", `DefaultComputer` doc). So compile B isolates
  the reductions compile A inlined → different accumulation order/precision → numeric divergence at odd
  seqLen, identical at even.

  FIX DIRECTION: the isolate-vs-inline (process-tree) decision must be made in `optimize()` and be
  **cache-independent**; the instruction cache should only decide whether an *already-isolated* op's
  kernel is recompiled vs reused — never whether an op IS isolated. Next: (1) numerically confirm
  reuse-off → odd9 cross-compile diff 0 (`numericDeterminismReuseOff`); (2) pin the exact substitution
  site and design a structure-preserving fix.

- **2026-06-19 — NUMERIC PROOF (run `0e2ed0ab`).** `directBlock` odd9 compiled twice:
  - reuse **ON** (default): `maxAbsDiff = 3.05e-04` (21/288 byte-identical) — bug reproduced (A=cold/
    fused vs B=warm/decomposed).
  - reuse **OFF**: `maxAbsDiff = 0.0` (288/288 identical) — fully deterministic.

  `enableInstructionSetReuse = false` eliminates the divergence. H8 proven at the numeric level.

  Per-compile reuse counts (from `coldWarmReuseOff` even8): reuse-off = 51 kernels constant; reuse-on
  cold = 49 (some intra-compile reuse), warm = 40 (more, cache pre-populated). A *single* compile is
  self-consistent (diagnosis: same compiled model re-run = 0.0); the bug is purely the cold↔warm
  mismatch. The cold/fused compile is also ~2× faster, so making every compile behave like the first
  fixes a perf regression too.

  **FIX OPTIONS** (this is a deliberate global perf optimization — checkpoint with owner on intent):
  - **(A) Scope/reset `DefaultComputer.instructionsCache` per top-level compile** → every compile
    starts cold → cross-compile deterministic AND fixes the 2× warm perf regression; preserves
    intra-compile reuse; **cost:** loses cross-compile / cross-model kernel reuse. *Recommended* unless
    cross-compile reuse is load-bearing (e.g. autoregressive recompile-per-step).
  - **(B) Make reuse structure-preserving** — cache presence must never change which ops are isolated;
    reuse only swaps an already-isolated op's compiled kernel. Keeps cross-compile reuse; more
    invasive, needs the exact isolation-coupling site (the deeper, more correct fix).
  - **(C) Disable reuse globally** — sledgehammer / perf regression. Rejected.

- **2026-06-19 — CONSTRAINT: cross-compile reuse is load-bearing.** Consultant (per
  `backend-compilation-and-dispatch.md:423`): the signature-keyed cache is "explicitly load-bearing for
  patterns like autoregressive loops that recompile per step but reuse identical kernels." So **fix (A)
  per-compile scoping and (C) global disable are both off the table** — the cache must stay global and
  cross-compile. The fix must keep cross-compile reuse and make it **numerically transparent (B)**:
  reusing a kernel must not change which ops are isolated nor the reduction accumulation order.

  The 3.05e-4 magnitude (vs ~1e-15 FP64) indicates the backend runs FP32 and the divergence is a
  reduction **accumulation-order** difference (inlined loop order ≠ isolated/decomposed order), not mere
  precision. Sub-options for (B): (B1) make the isolate-vs-inline decision deterministic and
  cache-independent in `optimize()`; (B2) make an isolated reduction accumulate in the same canonical
  order as the inlined one; (B3) gate signature-reuse so ops whose isolated form isn't bit-identical to
  their inlined form don't participate in cross-op reuse. Pinning the exact coupling (why a
  pre-populated cache adds isolation) is the remaining investigation before choosing.

- **2026-06-19 — EXACT STRUCTURAL MECHANISM (cache hit → hoist).** `list_children` of the cold(A) vs
  warm(B) roots: cold root = **19 children** (one fused `operations_390` OperationList + zero-duration
  runner/receptor wrappers) — everything fused into one unit. warm root = **149 children**
  (`operations_697` + ~148 **hoisted** top-level ops: `collectionAdd`, `packedCollectionSubset`,
  `collectionSum`, `exp`, `permute`, `concat`). So a cache **hit hoists an op out of the fused
  OperationList into its own top-level kernel** (required to invoke a standalone reused kernel); a cache
  **miss inlines/fuses** it. `AcceleratedComputationOperation.load()` (line 502): when
  `getArguments()==null` and the computation is a `Process`, it adopts the shared (cached)
  `ScopeInstructionsManager` and rebinds this op's args into the shared scope via
  `ProcessArgumentMap.putSubstitutions`. Compile 1 (cold cache) inlines; compile 2 (cache populated by
  compile 1) hoists. The isolate/inline (hoist) decision is therefore **cache-state-dependent** — the
  determinism bug. The exact compile-time site that turns a cache hit into a hoist (vs inlining the
  child's expression) is the last thing to pin; candidates are the required-scope conversion in
  `Scope.convertArgumentsToRequiredScopes` and `ComputationScopeCompiler` signature handling.

  **STATUS: root cause confirmed + numerically proven + structural mechanism pinned + load-bearing
  constraint established. Checkpointing with the owner on fix direction before implementing.**

- **2026-06-20 — OWNER PUSHBACK + POLARITY REVERSAL. My "hoist/isComputation flips" story was WRONG.**
  Owner pointed out (correctly): `OperationList.isComputation()` and `optimize()` are **purely
  structural** (read `enableCompilation`, depth, whether children are `Computation`s; segment by count
  + strategy) — **neither consults the instruction cache**, so a fuse/isolate decision cannot depend on
  cache state, and `isComputation()` cannot "flip" between two freshly-built identical graphs. Verified
  by reading both methods. So "hoist" was a mischaracterization of a *profile-tree* shape difference.

  Owner's framing: (1) the same thing compiled twice must give the same **results** — if not, narrow
  the mechanism; (2) cold-vs-warm MAY legitimately differ in *structure* (reuse already-compiled
  pieces) — that alone isn't a bug; (3) all fuse/isolate decisions happen once in `optimize()` and
  should be deterministic. So the real bug is the **numerical** difference, not the structure.

  `reuseThreeWayComparison` (run `3290ac15`, odd9, same weights+input):
  `R(reuse off)-vs-A(cold)=4.27e-4`, `R-vs-B(warm)=0.0`, `A-vs-B=4.27e-4`. **The warm compile equals
  the no-reuse reference byte-for-byte; the COLD (first) compile is the lone outlier.** This INVERTS my
  earlier read: warm = canonical (matches no-reuse), cold = anomaly. Mapping back to structure: cold =
  fused (678 nodes), warm = decomposed (2400 nodes) = no-reuse → **the aggressive fusion on the first
  compile is what changes the reduction result; the decomposed form is canonical.**

  CAVEAT being eliminated: `reuseThreeWayComparison` destroys A before compiling B, and model destroy
  can run manager destroy-listeners (cache eviction) → confound. Re-running with all compiled models
  kept alive (`valueSequenceVsReference`, expect `[diff, 0, 0, 0]`) to confirm the cold compile is the
  sole outlier before drawing conclusions.

  REVISED bug statement: with reuse on, the **first** compile of a signature-set fuses reductions in a
  way that yields a non-canonical numeric result; once the cache is warm, compiles reuse standalone
  kernels and match the no-reuse canonical result. Next: confirm the sequence, then pin the **specific
  reduction kernel** whose fused vs standalone form differs (attention sum/softmax/matmul — FFN is
  clean) and read its generated source in both forms to see the exact accumulation/precision change.

- **2026-06-20 — "first compile is the outlier" ALSO falsified; reuse is cache-state-variable.**
  `valueSequenceVsReference` (run `71b1011b`, all compiled models kept alive — no destroy/eviction
  confound), per-compile maxAbsDiff vs the no-reuse reference: **`[7.9e-4, 0.0, 4.9e-4, 7.9e-4]`**. Not
  `[diff,0,0,0]`: compile[1] matched the reference, but compiles 0/2/3 differ from the reference AND
  from each other (compile 2 ≠ 0/3). So it is **not** "first compile only." With reuse on, different
  compiles produce **several different FP results depending on cache contents**; reuse OFF is always
  bit-identical. Two clean narratives now falsified (identity-ordering; first-compile-outlier) — I will
  not assert a third without a test that isolates it.

  Solid invariants: reuse OFF ⇒ bit-reproducible; a single compiled model re-run ⇒ `0.0`; the variance
  lives entirely in the reuse path and tracks attention **reduction** kernels (FFN clean).

- **2026-06-20 — CONCRETE LEAD: under-specified reuse cache key (`signature()` TODO).** The cache key
  is a **signature string**: `Scope.signature()` = `metadata.getSignature()` (else scope name), and
  `ComputationScopeCompiler.signature()` appends `"&distinct=" + distinctChildCount` for a `Process`,
  with an in-code TODO: *"This may not be enough information to distinguish between operations, as a
  Process that had arguments (A, A, B) and (A, B, B) would retain the same signature."* Reuse then binds
  the requesting computation's args onto the cached kernel via `ProcessArgumentMap.putSubstitutions`.

  Hypothesis **H9 — reuse propagates a non-canonical / non-equivalent kernel.** Two flavors, different
  fixes:
  - **H9a (collision):** two genuinely different computations share a signature string → one is handed
    the other's kernel → wrong result. Fix: make the signature complete.
  - **H9b (non-canonical cached kernel):** signature is fine (same sig = same computation), but the
    *cached* kernel embeds a context-dependent reduction reordering (`Sum.enableGenerateReordering`,
    `KernelSeriesCache`, or fusion) from whichever op compiled it first; reuse then propagates that
    reordering, and which reordering gets cached depends on compile/cache order. Fix: make the cached
    kernel's reduction order canonical / signature-invariant.

  Both are gated by reuse (off ⇒ deterministic) and both fit the data. Distinguishing them is the next
  step (and is where the owner's design knowledge — is the reuse contract "same signature ⇒ bit-
  identical result", and is the signature meant to capture reduction-order-affecting decisions? — saves
  a deep dive). Verification ideas: detect cache collisions (compare cached vs requesting computation
  on hit); or strengthen the signature and check determinism returns while genuine reuse persists.

- **2026-06-20 — owner confirmed reuse contract = "same signature ⇒ same output given same inputs",
  upheld by either a distinguishing signature OR correct `ProcessArgumentMap` substitution; leaned
  H9a. THEN deeper evidence walked H9a-as-collision back.** Added two temporary flag-gated diagnostics
  to `DefaultComputer` (REVERT before final): `enableReuseCollisionLog` (logs a cache hit whose cached
  vs requesting computation descriptions differ) and `clearInstructionsCache()` (force a cold compile
  in-JVM). Findings:
  - `reuseCollisionDiagnostic` (attn-only odd9, 2 compiles): **zero** description-level collisions.
  - Signature chain: base = `ProducerComputationBase.signature()` = `MD5(name/reqs|join(":",
    inputSignatures.skip(1)))`, + `CollectionProducerComputationBase` appends output shape, + the cache
    key (`ComputationScopeCompiler.signature()`) appends `&distinct=childCount`. So the base signature
    DOES encode input arrangement + output shape; two reductions over different inputs get different
    signatures (verified: same-shape `sum (9,1,1)` ops correctly do NOT all collapse — some reuse, some
    compile fresh, by input).
  - Captured a CONFIRMED divergent cold/warm pair (`saveDivergentColdWarmProfiles`, run `f75ae1b2`,
    attempt 2, diff 1.5e-5). Diffed the reduction kernels: the cold (A, 718 nodes) and warm (B, 881
    nodes) `collectionSumComputation` kernels are **byte-identical generated C** (same sequential
    `acc = in[i] + acc` over the loop). The `[JNI]`/`[MTL]` markers on reused kernels are just
    deterministic by-shape backend routing (`getContext`: small/`sequential` → CPU, `count>128` →
    GPU), NOT cross-backend reuse.

  REVISED mechanism (most consistent with all evidence): it is **NOT** a wrong-kernel signature
  collision. The per-op kernels are identical; the divergence comes from **composition** — reuse
  changes which ops are emitted as standalone *materialized* kernels (write intermediate to an FP32
  buffer, read back) vs *inlined/fused* into a consumer (intermediate kept in-register, possibly FMA /
  wider precision). Inlined vs materialized is not bit-identical at an odd reduction length, and which
  ops are materialized depends on cache state → cross-compile nondeterminism. (warm/no-reuse materialize
  more; cold fuses more.) This is "reuse's structural change is not numerically transparent," i.e. a
  violation of the owner's point (1) even though the structural change itself (point 2) is intended.

  OPEN (design knowledge needed): is the inline-vs-materialize (fusion) decision supposed to be
  numerically transparent (bit-identical), and is it meant to be decided once in `optimize()` and be
  cache-independent? If yes, the bug is that the reuse path (post-optimize, in
  `AcceleratedComputationOperation.load()` / scope generation) changes it based on cache state.

- **2026-06-20 — OWNER GUIDANCE (decisive). Disentangle argument aggregation from instruction-set
  caching.** Owner clarifications:
  1. **Argument order:** an op `a+b` reusing a cached `x+y` with the operands MIS-mapped (so it computes
     `y+x` / wrong operand bound) would be a **defect** — find and fix if it exists. But *temporal*
     completion order of sibling kernels varying is fine and must not change meaning (barring degenerate
     self-referential cycles). So: a reuse that changes an op's **meaning** = bug; reuse that only
     changes when kernels finish = not a bug.
  2. **optimize() vs caching:** the compiled layout (where Scope boundaries / InstructionSets are) is
     fixed at `optimize()` time. `optimize()` *is allowed* to consider whether an InstructionSet is
     already cached when laying things out — so cold vs warm CAN legitimately produce different layouts
     (owner point 2). What must NOT happen: the structure of `x(y(z()))` changing *after* optimize() has
     decided the Scope conversion. "Cache-independent" was the wrong frame.
  3. **CRITICAL — run with `AR_HARDWARE_OFF_HEAP_SIZE=0`.** Right now the experiments test TWO things at
     once: argument aggregation (triggered by JVMMemory reservations for SMALL PackedCollections) and
     instruction-set caching. `AR_HARDWARE_OFF_HEAP_SIZE` (default 1024) is the size threshold below
     which allocations use the JVM heap (`JVMMemoryProvider`); `=0` forces every reservation to be
     provider-owned (device memory) and **avoids aggregation entirely** (confirmed at
     `AcceleratedOperation.java:629`, and `MemoryDataArgumentMap.isAggregationTarget` only targets
     `JVMMemoryProvider` memory). **Fix the determinism with JVMMemory/aggregation disabled FIRST**, then
     revisit whether JVMMemory has its own consequences.

  This recontextualizes the earlier aggregation work: aggregation NULLS the signature for aggregation
  targets (`DelegatedProducer.signature`/`Assignment.signature`), so with off-heap enabled some ops are
  non-cacheable (null sig) and some are cacheable → a mixed, inconsistent reuse pattern that confounds
  every caching experiment so far. Re-running the numeric determinism experiments with
  `-DAR_HARDWARE_OFF_HEAP_SIZE=0` (run `34c3d847`) to get a clean reading of pure instruction-set-cache
  determinism before designing any fix.

- **2026-06-20 — CONCLUSION: the cross-compile divergence is caused by ARGUMENT AGGREGATION, not
  instruction-set caching.** With `-DAR_HARDWARE_OFF_HEAP_SIZE=0` (aggregation fully disabled):
  - `valueSequenceVsReference = [0, 0, 0, 0]`; `numericDeterminismReuseOff` reuse-ON `= 0.0`; reuse
    3-way `A==B = 0.0` (run `34c3d847`).
  - `offHeapValueSanity` (default reuse, off-heap=0, run `b10bb43f`): `crossCompileDiff = 0.0`,
    `maxAbsValue = 2.0e4` (sane O(1e4), NOT the `2^30` sentinel) — so off-heap=0 is correct &
    deterministic, not "consistent garbage".
  - Same probe at default off-heap=1024 (run `9000c9d4`): `crossCompileDiff = 1.5e-5` (bug reproduces),
    `maxAbsValue = 76.9` (sane; magnitude differs only because of different unseeded random weights).

  So **with aggregation disabled, instruction-set reuse is reliably deterministic across compiles**
  (many samples). The divergence requires aggregation. The `2^30` (`1073741824`) values appeared ONLY in
  the runtime-`enableInstructionSetReuse=false`-toggle path under off-heap=0 — treated as a test-toggle
  artifact (a separate possible reuse-off+off-heap=0 issue, flagged to owner, not the determinism bug).

  Mechanism (with the earlier `aggregationResidenceProbe`, run `c773c59e`): aggregation is applied
  per-op based on whether the input memory is **JVM-heap-resident** (`isAggregationTarget` only targets
  `JVMMemoryProvider` memory; allocations below the off-heap threshold start on the JVM heap). With
  **shared** weights/input across the two compiles (as `compileTwiceMaxAbsDiff` and the real
  `LearnedTokensTest` do), the data is host-resident on the **cold** compile (→ aggregated) but has
  become device-resident by the **warm** compile (→ not aggregated). Aggregated vs non-aggregated emit
  numerically different kernels (the aggregate bundles inputs with pre/post copies + nulls the
  signature), so cold and warm diverge. `aggregationResidenceProbe` corroborates: shared weights split
  cold/warm, **fresh** weights stay uniformly "cold" (no split).

  NEXT: this is now a focused argument-aggregation determinism bug, isolated from caching. Design the
  fix so an op's aggregation decision does not depend on transient memory residence (or is otherwise
  numerically transparent). Confirm the owner wants the fix at the aggregation layer. Then revert the
  temporary `DefaultComputer` diagnostics.

- **2026-06-20 — IMPLEMENTED owner's size-based aggregation fix in `MemoryDataArgumentMap`.** Aggregate
  iff `md.getRootDelegate().getMemLength() < aggregationThreshold` (new static field defaulting to
  `AR_HARDWARE_OFF_HEAP_SIZE`, 1024 elements; `0` disables). Removed all three residence checks (old
  `get()` line 215, `generateArgument()` line 332, `isAggregationTarget` JVMMemoryProvider check),
  the dead `enableOffHeapAggregation` flag + `JVMMemoryProvider` import, and the now-dead `context`
  field/constructor params (only `create()` constructs the map; it keeps `context` for `getLanguage()`).
  Builds clean.

  RESULT (PARTIAL, all at default off-heap): the fix did not break anything (even-control, same-compile,
  ffn-only all `0.0`) and meaningfully reduced divergence — the real-world full `DiffusionTransformer`
  anchor was `0.0` this run (was reliably ~1.5e-3), and `reuseThreeWay` FLIPPED to `A==B` (the two
  reuse-ON compiles now agree; reuse-OFF became the outlier). BUT it is NOT a complete fix: it is flaky
  and several narrowing cases still diverge (`hand DiT-no-conv` 1.2e-3, `concat+block` 3.7e-4,
  `attn-only` 2.4e-5; `numericDeterminismReuseOff` still 6.1e-4 on a different draw).

  Residual hypothesis: the **reuse × aggregation mixed state**. Aggregation NULLS the signature of
  aggregation targets (`DelegatedProducer.signature`/`Assignment.signature`), so small reservations
  (< 1024: norms/biases/invFreq) are aggregated + null-sig + NOT reused, while large ones (qkv 3072,
  w1 8192, …) are cached/reused. `off-heap=0` is deterministic because it makes this UNIFORM (nothing
  aggregated). Testing the opposite extreme (`uniformAggregationDeterminism`: raise the threshold so
  EVERYTHING aggregates) — if both extremes are deterministic and only the mixed middle diverges, the
  null-signature/reuse interaction is confirmed, and the real fix is to make caching uniform (give
  aggregated ops a valid signature — the `DelegatedProducer.signature` TODO says it should be possible)
  OR the owner's fallback: drop arg-map aggregation and aggregate up front on the Process tree.
