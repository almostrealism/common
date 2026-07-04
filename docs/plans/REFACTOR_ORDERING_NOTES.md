# Ordering Notes: Output-Arg Removal and Related Goals

Working notes for sequencing five work items on this branch. Companion to
`DROP_OPERATION_OUTPUT_ARG.md` (the detailed plan for one of them). Findings
below are from a study pass (usage sweeps plus a local reproduction); they are
inputs to ordering, not full implementation plans.

The five items:

- **A. Remove the `output` argument** from `AcceleratedOperation.apply`
  (see `DROP_OPERATION_OUTPUT_ARG.md`)
- **B. Remove `PackedCollectionMap`** and replace its uses
- **C. Remove `getValueRelative`** in favor of TraversalPolicy-respecting
  `getValue`/`getValueAt`
- **D. Eliminate the destination-factory machinery** (self-contained
  destination producer instead of delegation back into the owning Computation)
- **E. Fix the `test-cl` / `test-media-cl` CI failures** (correctness and
  timeouts under `AR_HARDWARE_DRIVER=native,cl`)

---

## Findings per goal

### B. PackedCollectionMap

- Already `@Deprecated`; deprecation message recommends
  `TraversableExpressionComputation`-based replacements.
- **All production construction flows through three `SlicingFeatures` factory
  methods** (`map` ×2, `reduce`); no other production code constructs it.
  The removal is therefore a rewrite of those factory methods plus migration
  of the tests that pin current behavior.
- Test surface: `PackedCollectionMapTests` (~22 methods) and
  `EmbeddedCollectionMapTests` (~18 methods) are dedicated to it, plus ~18
  supporting uses across delta/convolution test classes.
- Known internal warts to not carry forward: the "Embedded PackedCollectionMap"
  nesting workaround (`ignoreTraversalAxis`), and `delta()` chain-rule support
  that is disabled by default (`enableChainDelta = false`).
- **Interlock with C:** its slice addressing is relative-position arithmetic
  (index division/modulo), and `PackedCollectionRepeat.getValueRelative` —
  one of the implementations C must remove — is already marked for removal.
- **Interlock with E:** the dedicated map test classes contain several of the
  worst CL offenders (the `mapEnumerate`-family timeouts). Removing B deletes
  or rewrites those tests, changing E's baseline.

### C. getValueRelative

- Declared on `TraversableExpression` (default: delegate to `getValueAt`),
  overridden kernel-aware in `CollectionExpression`
  (`kernelIndex * size + index`) and in `ArrayVariable` (with a delegate-chain
  fallback). The encoded assumption: each kernel instance owns a contiguous
  batch of `memLength` elements and "relative" means an offset within that
  batch — exactly the assumption the removal is meant to eliminate.
- Small surface: ~9 implementations, **8 production call sites** in four
  patterns. Most are mechanical (`getValueAt` with an explicitly computed
  index); two need real thought:
  - `ArrayVariable.getValueRelative`'s producer/delegate fallback chain
    (semantics must be preserved, not formula-substituted);
  - `Interpolate`'s variable-count fallback.
- No tests pin `getValueRelative` directly; behavior is covered via the
  computations that use it (repeat/delta/index-of-max paths).
- Shrinks if B lands first (one implementation and its call sites vanish).

### D. Destination-factory machinery

- The delegation cycle today: `ProcessDetailsFactory.construct` →
  `MemoryDataDestination.createDestination(size)` → provider lambda →
  `MemoryDataDestinationProducer.getDestinationFactory()` → **back into the
  owning Computation** (`CollectionProducerComputationBase.adjustDestination`
  → `shapeForLength(getShape(), getCount(), isFixedCount(), len)` → allocate).
- **The information actually consumed is small and mostly static**: the
  computation's `TraversalPolicy` shape, its count, its fixed-count flag —
  plus the kernel size known only at dispatch. Nothing else from the
  Computation is used. A self-contained producer carrying those three values
  can do everything `adjustDestination` does, with no back-reference.
- Only three users construct `MemoryDataDestinationProducer`:
  `CollectionProducerComputationBase` (the main case),
  `KernelTraversalOperation` (trivial fixed-size allocator), and
  `PassThroughProducer` (holds the reference but doesn't meaningfully use it).
- **Cross-invocation destination reuse:** no caching mechanism exists. The
  `adjustDestination(existing, len)` reuse path only fires within an
  evaluation, and `DestinationEvaluable` explicitly leaves reuse to the
  caller. The suspicion that reuse "doesn't really work anyway" is
  consistent with what the code shows — there is nothing there to preserve.
- **Interlock with A (the important one):** plan step 1 of
  `DROP_OPERATION_OUTPUT_ARG.md` — the "hybrid destination producer"
  (`ProducerArgumentReference(0)` + auto-create) — and D's self-contained
  destination producer are **the same artifact**. A PassThroughProducer
  subtype that (i) references argument slot 0, (ii) carries
  shape/count/fixedCount, and (iii) creates the destination via normal
  evaluation when the slot is null, satisfies both goals in one type. D and A
  should be executed as one arc, D-first (the new producer can replace the
  factory machinery while `output` still exists, exactly as plan step 1
  prescribes behind a flag).

### E. test-cl / test-media-cl failures

- **Reproduced locally in 6 seconds**: `PackedCollectionSubsetTests` under
  `-DAR_HARDWARE_DRIVER=native,cl` fails exactly the three CI methods
  (`subsetHalfPad2d`, `subsetHalfPad2dSum3`, `subsetHalfPad2dSumAll`),
  deterministically, with values matching CI's magnitude. 11 of 14 methods in
  the class pass — **every failure involves `pad()`**, so the correctness bug
  localizes to how `PackedCollectionPad`'s expression is rendered/executed
  under OpenCL.
- Mechanisms ruled out by inspection:
  - *Uninitialized destination memory:* the pad kernel writes its zeros
    explicitly (`conditional(cond, value, 0.0)`); it does not rely on fresh
    buffers being zero-filled. (Note the asymmetry does exist — Metal
    zero-fills new buffers, `clCreateBuffer` contents are undefined — worth
    keeping in mind generally, just not the cause here.)
  - *Rounded-up global work size:* `CLOperator` enqueues the exact global
    size with a null local size; there are no phantom work items.
- Remaining candidates: the rendering of the conditional / index-projection
  expressions in OpenCL C vs MSL (type widths, comparison semantics), or
  argument offset/size binding differences. Next diagnostic step is
  mechanical: dump the generated kernel source for the pad computation under
  both backends and diff (profile tooling / instruction-set output dir).
- The **timeout** class of CL failures is separate: known
  `CLMemoryProvider` per-element read behavior (bulk-read gap) makes
  read-heavy tests crawl; several of the worst offenders live in the
  PackedCollectionMap test classes that B deletes. Treat timeouts as
  perf/infrastructure work, re-baselined after B.
- The **memory-max** class of CL failures is a third category, and an accepted
  parity gap for now. `CLMemoryProvider` never adopted `HardwareMemoryProvider`'s
  phantom-reference reclamation (Metal, native, and nio all free device memory
  when the owning Java object is collected; CL frees only on explicit
  `destroy()`), so allocation-churn workloads — `SimilarityOverheadTest`'s
  at-scale methods churn hundreds of thousands of undestroyed transients —
  climb monotonically to the reservation ceiling under CL while Metal shrugs
  them off. Both fix routes were probed and hit real hazards: migrating the
  provider onto the reclamation base requires refcounting the `cl_mem` buffers
  it shares across host-pointer allocations (`reverseHeap`), and adding
  explicit `destroy()` calls to the test raced the asynchronous copy-out chain
  (null source reads on a compute-context thread) and the native provider's
  own reclamation (double-free warnings). The systemic fix — GC-driven CL
  reclamation with refcounted shared buffers — is backlog; until then the CL
  lane will report memory-max in groups containing high-churn tests.
- These jobs are informational (not in the all-checks gate), so nothing
  blocks on E — but a trustworthy CL backend doubles the regression oracle
  for A/D (the output-arg plan's validation gate explicitly wants both
  backends exercised).

---

## Interdependencies (summary)

- **A ⇄ D:** same artifact at step 1; must be one arc, D-first.
- **B → C:** B deletes one `getValueRelative` implementation and its uses.
- **B → E:** B deletes/rewrites several of the worst CL test offenders
  (timeouts); E's correctness fixes are independent of B.
- **E → (A, D):** a green CL lane is a second correctness oracle for the
  dispatch-path surgery; conversely, an unexplained CL miscompile would
  contaminate validation of every index-affecting refactor (B, C, and the
  `+1` shift in A).
- **C ⊥ A/D:** different layer (expression indexing vs dispatch/destination),
  independently testable.

## Recommended order

1. **E (correctness class only, timeboxed).** Chase the pad-under-CL
   miscompile via generated-source diff. Small, already reproduced, and it
   removes a hidden-issue confounder before the refactors start reshaping
   index expressions and destinations. If it turns out systemic rather than
   local, write up the mechanism and defer the fix — knowing *why* CL
   diverges is the deliverable that de-risks the rest. Defer the timeout
   class entirely.
2. **B.** Self-contained, already deprecated, single production chokepoint.
   Deletes the nesting workaround, shrinks C, and removes the worst CL
   timeout tests. Re-baseline the CL lane after.
3. **C.** Small and now smaller; pure expression-layer change with the two
   flagged non-mechanical spots (ArrayVariable delegate chain, Interpolate).
   Landing it before the dispatch surgery keeps "what does an index mean"
   changes separate from "how is a kernel dispatched" changes.
4. **D + A as one arc.** Build the self-contained destination producer
   (PassThroughProducer subtype: slot-0 reference + shape/count/fixedCount +
   auto-create) behind a flag — this is simultaneously D's replacement and
   plan step 1 of A. Then proceed through A's remaining steps per
   `DROP_OPERATION_OUTPUT_ARG.md`, with the strongest validation battery
   (both backends, commit-attribution before/after) now available.

The alternative of starting with A (original intuition) effectively pulls D
forward anyway — they can't be sequenced apart. Starting with E+B+C instead
costs a few days but means the deepest cut lands on a smaller, cleaner,
dual-backend-verified codebase.
