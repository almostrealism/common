# Plan — Batch the aggregation copy-in/out onto the Metal command buffer (all-Metal scope)

> **Objective.** Make argument aggregation's copy-in (`root → aggregate`) and copy-out
> (`aggregate → root`) execute as **GPU operations encoded into the same Metal command buffer as
> the kernel they wrap**, so they stop forcing the per-op host `waitFor()` that currently collapses
> command-buffer batching. **Scope is deliberately narrow: the all-Metal case only**
> (`AR_HARDWARE_OFF_HEAP_SIZE=0`, every operand on a Metal buffer). The cross-provider / hybrid case
> (JVM-heap or native memory on one side of the copy, plus the larger `processing` cross-provider
> replacement waits) is **explicitly out of scope** and deferred to a follow-on built on this one.
>
> Status: **plan only.** No production code has been changed for this plan. The `diag*`
> instrumentation referenced under "Validation" already exists in the working tree (uncommitted).

---

## 1. The measured problem (receipts)

On the pinned dense scene (seed 58, 1126 elements, sustained 200 ticks, efx+reverb, 4096), measured
across runs `0eece686` / `c05c475c` / `a1e8b92f` / `b6da932b`:

- Metal command-buffer batching is **collapsed**: `meanDispatchesPerCommit ≈ 1.07` despite
  `MetalCommandRunner.MAX_OPEN = 256`. Every dispatch gets its own committed buffer.
- **100 %** of commits are host-`waitFor()` completions (`cComplete`); `cDependency = 0`,
  `cMaxOpen = 0`, `withDep = 0`. It is **not** GPU-dependency chaining.
- The waits come from `AcceleratedOperation.apply()` host-blocking per op on the
  `processing || aggregateCopyOut` copy-back path. Split: `processing` (cross-provider replacement)
  ≈ 86.8 waits/tick; **`aggregateCopyOut` (argument aggregation) ≈ 67.7 waits/tick** (overlapping).
- The aggregation **threshold** is *not* a lever: a sweep of `maxAggregateLength` ∈
  {1024,512,128,32} left batching flat (`meanDispatchesPerCommit ≈ 1.07`), and {8,4,2}
  **fail to compile** — Metal "`'buffer' attribute parameter is out of bounds: must be between 0 and
  30`" (the 31-buffer limit). Aggregation is load-bearing; it cannot simply be turned down.

This plan targets the **`aggregateCopyOut`** half (~68 waits/tick) — the part attributable to
aggregation's own copy operations. (The `processing` half is the deferred hybrid follow-on.)

## 2. Current mechanism (receipts)

- **Aggregation packs small args into one buffer** to stay under Metal's 31-buffer limit.
  `MemoryDataArgumentMap.aggregate()` (`:223-238`) records a `Replacement(root, pos)` and returns an
  `ArrayVariable` delegating into the shared aggregate buffer at `pos`. The **kernel reads/writes the
  aggregate buffer in place**; `root` is a separate buffer that other ops / the caller reference.
- **The copies sync `root ↔ aggregate`:**
  - copy-in `MemoryDataArgumentMap.getPrepareData()` (`:296-306`): for each replacement, a
    `MemoryDataCopy(root → aggregate[pos], len)`.
  - copy-out `getPostprocessData(skipOutput)` (`:329-344`): for each replacement, a
    `MemoryDataCopy(aggregate[pos] → root, len)`, **skipping** any slice sharing memory with the
    kernel's explicit output (the in-place `x = x + y` guard).
- **`MemoryDataCopy` is a host round-trip, and not a `Submittable`.** `MemoryDataCopy.get()`
  (`MemoryDataCopy.java:290-308`) returns a plain `Runnable` doing
  `target.setMem(targetPosition, source.toArray(sourcePosition, length))`. `toArray` pulls the
  source to a Java array; `setMem` pushes it. Because it is a plain `Runnable` (not an
  `AcceleratedOperation`/`Submittable`), in `OperationList.Runner.run()` it is a **non-submittable
  member**, which forces `pending.waitFor()` (`OperationList.java:1386-1388`) — committing and
  draining the open buffer.
- **Even Metal→Metal `setMem` host-stages.** `MetalMemoryProvider.setMem(MetalMemory, …, Memory, …)`
  (`:336-354`) does `src.getMem().getContents(hostBuf, …); mem.getMem().setContents(hostBuf, …)` —
  a round-trip through a host buffer. So the copy needs the source kernel **complete** before it can
  read, which is why `apply()` host-`waitFor()`s at `:608` before running copy-out at `:619-622`.
- **`apply()` flow** (`AcceleratedOperation.java`): `:573-575` runs copy-in
  (`argumentMap.getPrepareData().get().run()`) before the kernel; `:585` dispatches the kernel;
  `:601-622` host-`waitFor()`s then runs copy-out — only when `processing || aggregateCopyOut`. The
  TODO at `:603-604` ("a new Semaphore that performs the postprocessing when the original finishes,
  rather than blocking the host here") names the deferral this plan implements for the all-Metal
  case. `MemoryDataCopy`'s own TODO (`:123-126, :305`) names the same-provider direct copy.

## 3. Why the all-Metal case first

`AR_HARDWARE_OFF_HEAP_SIZE` (`Hardware.java:1112`, **default 1024**) is the element threshold below
which an allocation lives in **JVM heap** instead of a Metal buffer (`MetalDataContext.offHeapSize`).
The aggregated args are *exactly* the small ones (`memLength ≤ maxAggregateLength = 1024`), so
**today they are JVM-heap-resident** — the aggregate copy is genuinely cross-provider (JVM↔Metal)
and *must* touch the host.

Setting `AR_HARDWARE_OFF_HEAP_SIZE=0` forces every allocation onto a Metal buffer. Now both `root`
and `aggregate` are `MTLBuffer`s, and the copy is **Metal→Metal** — which *can* be a device-to-device
GPU operation encoded into the command buffer, ordered by the GPU, with no host stall. This is the
clean sub-problem: prove correctness + batching with one memory provider, then layer the harder
"don't read a not-yet-available provider" ordering on top for hybrid later.

## 4. Design

Three pieces. Each is independently testable.

### 4.1 A device-to-device Metal copy (the primitive)

We need a copy `aggregate[pos..pos+len] ↔ root` that runs on the GPU inside the open command buffer.
**Decision point — choose one (recommend evaluating in this order):**

- **(A) Compute-kernel copy via the existing `Assignment` (CHOSEN).** The framework already has the
  copy-kernel generator: `org.almostrealism.hardware.computations.Assignment` (an
  `OperationComputationAdapter`). Its `getScope()` (`Assignment.java:276-328`) emits exactly the
  element-wise body — `out.getValueAt(index).assign(value.getValueAt(index))` over a `KernelIndex` —
  in the platform-neutral Expression/Scope form, so it compiles to Metal/OpenCL/native alike. The
  **`x[xoff+i] = y[yoff+i]`** offsets are carried by the **operand views** (a `Bytes(len, aggregate,
  pos)` delegates to the aggregate at `pos`, so its `ArrayVariable` reference resolves to
  `aggregate[pos+index]`) — *no kernel-body change is needed*. `super.get()`
  (`OperationComputationAdapter`) compiles that scope to an `AcceleratedOperation` — a `Submittable`
  that dispatches through `MetalOperator → MetalCommandRunner.submit` into the open buffer, exactly
  like every other kernel.

  **The one obstacle is `Assignment`'s short-circuit.** `Assignment.get()` (`:339-397`) diverts to
  `new DestinationEvaluable(ev, destination)` when the destination is a provider **and** the source
  is a `Provider`/`AcceleratedOperation` (`:383-387`) — and `DestinationEvaluable.evaluate()` runs
  `operation.into(destination).evaluate()` (`DestinationEvaluable.java:280`), which for a bare
  `Provider` source is a **host** write, not a batchable kernel. Our `root ↔ aggregate[pos]` copy hits
  exactly this case. **Fix:** bypass the short-circuit so `get()` returns `super.get()` (the kernel).
  Two ways, in order of preference:
  - **A flag on `Assignment`** (e.g. `enableShortCircuit`, default `true`; when `false`, `get()`
    returns `super.get()` immediately). Surgical, preserves all existing behaviour, and is the
    "adjust the short-circuit" path. The aggregation copies construct the `Assignment` with the
    short-circuit off.
  - **Construct operands so the short-circuit condition is false** ("use as is") — e.g. a destination
    operand that is not a bare `Provider`, so `Computable.provider(out)` is false and `get()` falls
    through to `super.get()` at `:396`. More fragile; prefer the flag.

  **Pros:** reuses the proven kernel-dispatch + batching + hazard-tracking path; no new JNI; one tiny,
  reversible change to `Assignment`. **Cons / must-verify:** (i) signature stability — confirm two
  same-shape aggregated copies share an instruction-cache signature (offsets are operand-view state,
  which the signature model already handles for ordinary kernels; add an explicit test); (ii) the
  operands must be wrapped as `Producer<MemoryData>` (the aggregation code currently passes
  `Supplier<MemoryData>` to `MemoryDataCopy`), a mechanical adaptation.

  *Historical note:* the prior bare-compute-kernel and blit options (below) are retained as
  fallbacks, but `Assignment` is the intended vehicle.

  **Existing infrastructure — leverage, do not reinvent.** This is part of an in-progress owner
  migration from `MemoryDataCopy` to `Assignment`: `MemoryDataFeatures.copy()` (`:180-204`) and
  `CodeFeatures.copy()` (`:305-318`) already switch `MemoryDataCopy → Assignment` when
  `MemoryDataFeatures.enableAssignmentCopy` (`:153`, default off — *in-progress*, **not** reverted)
  is set, and they construct the `Assignment` to dodge the short-circuit (lambda producers /
  `traverseEach`, so `Computable.provider(out)` is false → kernel). The aggregation copies in
  `MemoryDataArgumentMap.getPrepareData`/`getPostprocessData` **bypass** this — they call
  `new MemoryDataCopy(...)` directly — so the change is to **route them through the existing
  Assignment-copy path** (the `copy()` helper or its construction), gated by a flag + all-Metal,
  rather than author a new copy op. The migration's `ExpansionWidthTargetOptimization` and
  `docs/plans/EXPANSION_WIDTH_*.md` are prerequisite context; confirm with the owner whether that
  optimization is required for `enableAssignmentCopy` correctness/perf before relying on it.
- **(B) Blit copy (`MTLBlitCommandEncoder.copyFromBuffer:…`).** The "correct" primitive for
  buffer-to-buffer copy. **Pros:** no compute dispatch; minimal. **Cons:** **new JNI** — there is no
  `MTLBlitCommandEncoder` wrapper today (only `MTLComputeCommandEncoder`); `MetalCommandRunner` and
  `MTL.cpp` would need to encode a blit into the open buffer and still signal the completion
  `MTLEvent`. Larger native surface.
- **(C) `MemoryProvider`-level `directCopy`.** Add a same-provider copy entry point (the
  `MemoryDataCopy` TODO's `provider.directCopy(...)`) that, for the Metal provider, dispatches (A) or
  (B). Cleanest API seam; the chosen primitive still has to be one of (A)/(B) underneath.

**Recommendation:** prototype **(A)** behind the flag first (lowest blast radius, reuses the proven
dispatch path). Fall back to **(B)** only if signature stability for (A) proves intractable.

### 4.2 Make the copy `Submittable` into the open command buffer

`getPrepareData()` / `getPostprocessData()` must return operations that **dispatch into the runner's
open buffer** rather than host `Runnable`s. Concretely, in the all-Metal path, replace the
`MemoryDataCopy` host op with the 4.1 GPU copy op (a `Submittable` / `AcceleratedOperation`). Then in
`OperationList.Runner` they are submittable members (no `waitFor` between them), and in `apply()`
they accumulate into the same open buffer as the kernel.

### 4.3 Order the chain and remove the host wait

With copy-in, kernel, and copy-out all encoded into one command buffer, ordering is handled by
**Metal's in-buffer hazard tracking through the shared aggregate buffer** — the same mechanism that
already serializes dependent kernels (each dispatch gets its own encoder; Metal auto-serializes
read-after-write on a buffer across encoders):

```
copy-in dispatches  → write aggregate[pos_i]      (per replacement)
kernel dispatch     → read+write aggregate         (RAW on aggregate ⇒ waits for all copy-ins)
copy-out dispatches → read aggregate[pos_i], write root_i   (RAW on aggregate ⇒ waits for kernel)
```

Because every step touches the **same aggregate buffer**, the GPU orders them correctly with **no
host involvement**. Therefore, in the all-Metal aggregation path, the `apply()` host-`waitFor()` at
`:608` and the host-run copy-backs at `:614-622` are **replaced** by encoding the copy-out dispatches
into the buffer; the caller's eventual read of `root` (or the group-end `waitFor`) is the only sync,
and it now drains a *batch*, not one op.

## 5. Correctness analysis

- **In-buffer ordering is sufficient (all-Metal).** The aggregate buffer is the single shared hazard
  point; Metal's tracked hazards order copy-in → kernel → copy-out without a host wait. This is the
  *only* environment where that holds — hence the scope restriction. (Verify the aggregate buffer is
  allocated with hazard tracking enabled, as the kernels rely on.)
- **`skipOutput` preserved.** `getPostprocessData(skipOutput)` (`:329`) must keep skipping the slice
  that shares memory with the kernel's explicit output (the in-place `x=x+y` read-copy guard). Port
  this filter verbatim into the GPU copy-out construction.
- **Signature stability.** The copy op must not destabilize `CollectionProviderProducer.signature()`
  / instruction-cache reuse (the original reason aggregation was delicate — see
  `INSTRUCTION_CACHING.md` and the `aggregate`-signature history). Offsets/lengths are *arguments*,
  not kernel-body constants. Add a test asserting two same-shape aggregated ops share a signature.
- **No silent GPU race.** The failure mode is a copy-out that reads the aggregate before the kernel
  writes it (or a kernel that reads before copy-in writes). This is caught by **parity** (§7), not by
  "non-silent" — a race yields wrong-but-non-silent output. Parity is the gate.

## 6. Flag-gating & configuration

- New flag, default **off**: e.g. `AR_HARDWARE_BATCHED_AGGREGATE_COPY` (static read, like
  `enableArgumentAggregation`). The batched path engages **only** when the flag is on **and** all
  operands are Metal-resident (effectively `AR_HARDWARE_OFF_HEAP_SIZE=0`). Otherwise fall back to the
  existing host `MemoryDataCopy` + `waitFor` path unchanged.
- Belt-and-suspenders: if any operand of a given copy is not a Metal buffer, that copy takes the old
  host path even with the flag on (so a stray hybrid allocation degrades gracefully instead of
  racing). This keeps the change safe to leave on in the all-Metal config.

## 7. Validation (un-fakeable)

Reuse the existing `diag*` instrumentation (`MetalCommandRunner.diag*`, `AcceleratedOperation`
apply-counters) read by `PdslHotPathBreakdownTest`:

- **Batching recovered:** with the flag on + `OFF_HEAP_SIZE=0`, `meanDispatchesPerCommit` rises
  **well above 1** (target: the per-forward dispatch count, tens), `aggregateWaits/tick → ~0`, and
  `commitsPerTick` drops correspondingly. (Compare against the recorded baseline 1.07 / ~68 agg
  waits/tick.)
- **Parity (the correctness gate):** the all-Metal render with the flag on must match the all-Metal
  render with the flag **off** — bit-exact where the kernels are deterministic, else within a tight
  FP tolerance — **not merely non-silent**. Add an explicit A/B (two renders, compare sample
  buffers), since a hazard bug is silent under a non-silence check.
- **No regressions:** `OperationDispatchBatchingTests`, `AggregatedComputationTests`, and the audio
  sentinel tests pass with the flag on (all-Metal) and with it off (default).
- **Report what's measured:** date / machine / driver / `OFF_HEAP_SIZE` / flag / warmup depth /
  run-id on every number (per the project's admissible-evidence rules). `OFF_HEAP_SIZE=0` is a
  *diagnostic* config here, not the shipping default — say so.

## 8. Phased steps

1. **Baseline the all-Metal config.** Run `PdslHotPathBreakdownTest` (or a small unit) with
   `-DAR_HARDWARE_OFF_HEAP_SIZE=0`, flag **off**. Confirm: (a) it still works / is correct, (b)
   batching is **still collapsed** (the copies still host-stage via `getContents/setContents`). This
   proves the copy — not the JVM-heap residency alone — is the remaining blocker, and gives the
   parity reference.
2. **Implement the device-to-device copy (4.1, approach A).** Unit-test it in isolation
   (`aggregate[pos] ↔ root` round-trips correctly on Metal buffers, in a command buffer).
3. **Make `getPrepareData`/`getPostprocessData` emit the submittable GPU copy in the all-Metal +
   flag-on path (4.2).** Keep `skipOutput`.
4. **Remove the `apply()` host wait for the all-Metal aggregation path (4.3),** flag-gated; encode
   copy-out into the buffer.
5. **Validate (§7):** batching metrics + parity + regression suite.
6. **Decide default.** If parity holds and batching recovers, propose enabling the flag for the
   all-Metal config; leave `OFF_HEAP_SIZE` default unchanged (shipping config stays hybrid until the
   follow-on).

## 9. Acceptance criteria

- `meanDispatchesPerCommit` ≫ 1 (target: ≈ the per-forward dispatch count) and `aggregateWaits/tick`
  ≈ 0, with flag on + `OFF_HEAP_SIZE=0`, on the pinned scene, sustained ≥ 200 ticks, ≥ 3 runs.
- **Parity:** flag-on output == flag-off output (all-Metal), bit-exact or within tolerance, on the
  pinned scene and on the aggregation unit tests.
- Full regression suite green with the flag on (all-Metal) and off (default).
- A signature test proves aggregated-copy ops remain instruction-cache-stable.

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Silent GPU race (copy-out reads before kernel writes) | Parity gate (§7), not non-silence; flag-gated; A/B on the pinned scene. |
| Signature instability breaks instruction-cache reuse | Offsets/lengths as args; explicit signature-stability test; approach (A) reuses the proven dispatch path. |
| Blit JNI scope creep (if approach B) | Prefer (A) first; only do (B) if (A)'s signature stability is intractable. |
| `OFF_HEAP_SIZE=0` shifts other behaviour/perf (all small allocs on Metal) | Treat as a diagnostic config; measure; do **not** change the shipping default in this plan. |
| Mixing objectives | Hard scope: all-Metal only. Any "what if a native buffer appears" handling is the follow-on; here, non-Metal operands fall back to the host path. |

## 11. Out of scope (the follow-on this enables)

- **Hybrid copy deferral.** When an operand is JVM-heap or native, the copy must touch the host; the
  follow-on defers it behind the dispatch completion (a real `MetalSemaphore` continuation / the
  runner's `onComplete`) **and** orders any op that later reads the not-yet-available provider after
  that completion. That ordering machinery is the genuinely hard part and is intentionally not mixed
  into this plan.
- **The `processing` (cross-provider replacement) waits** (~87/tick) — the larger half of the
  per-op waits — are the same follow-on problem at the `MemoryReplacementManager` level.

Getting all-Metal aggregation copies batched and **proven correct** is the foundation both of those
build on.
