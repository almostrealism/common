# Plan — Global migration from `MemoryDataCopy` to `Assignment` (kernel-based copy)

> **One-paragraph statement of intent.** Make `Assignment` (a compiled assignment *kernel*) the
> default mechanism for copying between `MemoryData` regions, replacing the host-mediated
> `MemoryDataCopy`. In the cases where an `Assignment` would run *by itself* (a standalone copy not
> batched with anything), it may *short-circuit* back to a direct `MemoryProvider::setMem`-style host
> copy when that is cheaper. **This inverts today's default** (today: host copy by default, kernel
> only behind a flag) and is the long-stated goal that has not yet been achieved. This document is the
> single consolidation point for that effort so it can be picked up across sessions.
>
> **Status (updated 2026-07-04):** default **OFF again**, and the primary motivation has been
> partially superseded. The flag history: default-enabled `de5f2a961` (2026-06-28) → reverted
> `b20a4a175` → re-enabled `468fe809d` → **currently disabled**
> (`MemoryDataFeatures.enableAssignmentCopy` reads `AR_HARDWARE_ASSIGNMENT_COPY` with
> `.orElse(false)`); the javadoc there records why the flip cannot hold yet:
> assignment-based layer input recording **diverges some gradient-descent trainings**.
> Separately, the §1 batching motivation was addressed by a different design:
> `a3b20e285` (2026-07-02, "Chain every copy on the Semaphore mechanism and deprecate
> `MemoryDataCopy`") makes `AcceleratedOperation.apply` non-blocking — prepare copies chain
> ahead of the kernel, the kernel chains on the last copy-in, replacement copy-back and
> de-aggregation chain after — so host copies no longer force the per-op `waitFor` this plan
> was written to eliminate. `MemoryDataCopy` is now `@Deprecated` with guidance toward
> `Assignment`, `MemoryData.copyFrom`, and the `copy(...)` helpers, and the §3 direct-construction
> sites have been migrated. The remaining motivations for *this* migration are optimizer
> visibility (`Assignment` is a `ParallelProcess`; `MemoryDataCopy`'s producer tree is invisible
> to strategies) and the ML copy cost (§1 Qwen numbers) — re-baseline both before resuming.

---

## 1. Why — the benefit (measured, cross-cutting)

Host-mediated copies are the dominant reason GPU **command-buffer batching collapses**, because a
host copy must wait for the producing kernel to finish (it reads the result back through the host),
which commits and drains the open command buffer. Two independent workstreams hit this:

- **Audio (AudioScene real-time).** Measured on the pinned dense scene (seed 58, sustained 200
  ticks, runs `0eece686`/`c05c475c`/`a1e8b92f`/`b6da932b`): Metal batching is collapsed to
  `meanDispatchesPerCommit ≈ 1.07` (vs `MAX_OPEN=256`); **100 %** of commits are host-`waitFor`
  completions; the per-op host waits come from `AcceleratedOperation.apply()` on the
  `processing || aggregateCopyOut` copy-back path (≈ 87 + 68 waits/tick, overlapping). Both of those
  copy paths are `MemoryDataCopy`. See
  [`audio-scene-redesign/BATCHED_AGGREGATE_COPY.md`](audio-scene-redesign/BATCHED_AGGREGATE_COPY.md).
- **ML (Qwen).** [`QWEN_PERFORMANCE.md`](QWEN_PERFORMANCE.md) records `copy_4864` (11.0 %) +
  `copy_896` (6.8 %) = **17.8 % of total inference time** in memory copies, and names
  "`Assignment` in place of `MemoryDataCopy` via `MemoryDataFeatures.enableAssignmentCopy`" as a
  lever.

A kernel-based copy (`Assignment`) is a normal `AcceleratedOperation` — a `Submittable` that
dispatches into the **open command buffer** alongside the kernels it sits between, ordered by the
GPU's in-buffer hazard tracking, with **no host round-trip and no per-op wait**. That is the win:
copies stop breaking batches.

## 2. The goal / north-star design

1. **Always emit the `Assignment` kernel** for a `MemoryData`→`MemoryData` copy.
2. **Short-circuit to a direct host copy only when it pays** — specifically when the `Assignment`
   would execute *standalone* (not batched into a surrounding command buffer / op group), where a
   single `MemoryProvider::setMem` is cheaper than compiling+dispatching a kernel. This is the
   **inverse** of today's short-circuit (today it diverts to host *by default* when the source is a
   `Provider`/`AcceleratedOperation`).
3. The decision is about *execution context* (am I alone, or am I one dispatch among many?), not
   about operand types.

## 3. Current state (what exists vs. what bypasses it)

### What already exists
- **`Assignment`** (`base/hardware/.../computations/Assignment.java`) compiles the copy kernel.
  `getScope()` (`:276-328`) emits `out.getValueAt(index).assign(value.getValueAt(index))` over a
  `KernelIndex`, in platform-neutral Expression/Scope form (Metal/OpenCL/native). Offsets
  (`x[xoff+i] = y[yoff+i]`) come from the **operand views** (a `Bytes(len, buf, pos)` resolves to
  `buf[pos+index]`) — no kernel-body change needed.
- **The short-circuit** (`Assignment.get()`, `:339-397`) diverts to `new DestinationEvaluable(ev,
  destination)` when the destination is a provider **and** the source is a
  `Provider`/`AcceleratedOperation` (`:383-387`); `DestinationEvaluable.evaluate()` runs
  `operation.into(destination).evaluate()` (host write for a bare `Provider`). `super.get()`
  (`OperationComputationAdapter`) is the real kernel path.
- **The flag + helpers.** `MemoryDataFeatures.enableAssignmentCopy` (`:153`, runtime via
  `AR_HARDWARE_ASSIGNMENT_COPY`, **default enabled** as of 2026-06-28).
  `MemoryDataFeatures.copy()` (`:180-204`) and `CodeFeatures.copy()` (`:305-318`) switch
  `MemoryDataCopy → Assignment` when the flag is on, constructing the `Assignment` to **avoid** the
  short-circuit (lambda producers / `traverseEach`, so `Computable.provider(out)` is false → kernel).
- This is an **in-progress owner migration**, commit `06b1dd32d`. The default-off is *in-progress*,
  **not reverted**. (`ExpansionWidthTargetOptimization`, also in that commit, is **coincidental and
  out of scope** here unless separately needed.)

### What bypasses the flag (must be covered by a global switch)
Many call sites construct `new MemoryDataCopy(...)` **directly**, so they do not respond to
`enableAssignmentCopy`:

| Site | Role |
|---|---|
| `MemoryDataArgumentMap.java:302,340` | **Aggregation** copy-in / copy-out (the audio `aggregateCopyOut` waits) |
| `MemoryReplacementManager.java:289,290` | **Cross-provider replacement** Temp Prep/Post (the audio `processing` waits) |
| `PackedCollection.java:229` | constructor copy |
| `CollectionProvider.java:163` | evaluate-into |
| `WaveOutput.java:359` | export |
| `FilterEnvelopeProcessor.java:150,152` | input / output |
| `DefaultChannelSectionFactory.java:315,317` | section input / output |

> **Important connection:** the aggregation (`MemoryDataArgumentMap`) *and* the cross-provider
> replacement (`MemoryReplacementManager`) copies — i.e. **both halves** of the audio per-op-wait
> problem — are `MemoryDataCopy`. A correct global switch therefore addresses both. And note that
> under all-Metal (`AR_HARDWARE_OFF_HEAP_SIZE=0`) the cross-provider replacement path should largely
> *not trigger* (no provider mismatch), which is why the audio sub-plan scopes to all-Metal first.

## 4. How `Assignment` becomes a *batchable* GPU copy (the mechanism)

`super.get()` compiles `Assignment` to an `AcceleratedOperation`. As a `Submittable`, it dispatches
through `MetalOperator.accept → MetalCommandRunner.submit` into the **open** command buffer. When a
copy-in, a kernel, and a copy-out all encode into one buffer and all touch the **same shared buffer**
(e.g. the aggregate), Metal's per-dispatch-encoder hazard tracking serializes them
(`copy-in → kernel → copy-out`) **without any host wait** — the identical mechanism that already
orders dependent kernels. The only requirement is that the copy be a kernel (not a host op) **and**
that the surrounding code not insert a host `waitFor` around it.

## 5. Scope & sequencing

- **Sub-scope already specified (audio, all-Metal):**
  [`audio-scene-redesign/BATCHED_AGGREGATE_COPY.md`](audio-scene-redesign/BATCHED_AGGREGATE_COPY.md)
  — route the two `MemoryDataArgumentMap` aggregation copies through the `Assignment` path under
  `OFF_HEAP_SIZE=0`, prove batching + parity. This is the smallest correctness-provable beachhead.
- **Global migration (this doc):** progressively route *every* direct `new MemoryDataCopy` site
  (§3 table) through the `Assignment` path (or the `copy()` helper), implement the **inverted
  short-circuit** (§2), and make `Assignment`-copy the default — once the baseline (§6) says what
  must be fixed first.

## 6. The first task — baseline "what breaks when `enableAssignmentCopy=true`"

`enableAssignmentCopy` is **not close to ready**; what breaks is currently unknown and must be
*measured*, not guessed.

- **Mechanics.** `enableAssignmentCopy` is now resolved at runtime from `AR_HARDWARE_ASSIGNMENT_COPY`
  (enabled/disabled), **default enabled**. It is still a `public static final` interface field, so the
  value is read once at class initialization and a **clean full rebuild** is required for consumers to
  observe the new default (CI does this); until then, modules compiled against the old constant keep
  the old value. Enabled, the **helper-routed** copies (`MemoryDataFeatures.copy` /
  `CodeFeatures.copy`) become `Assignment`; the direct `new MemoryDataCopy` sites (§3) are unaffected
  until separately routed.
- **Plan.** Run the full suite on a clean build with the new default (CI) and record the **exact
  failing set** (test → failure mode) as the baseline; a full-suite run is the right signal because
  the blast radius is unknown, and the failing set becomes the migration's work-list. (To A/B later
  without a rebuild, set `AR_HARDWARE_ASSIGNMENT_COPY=disabled`.)
- **Record results below** (date, commit, pass/fail set) so the next session starts from data, not
  memory.

> Baseline results: _to be filled in._

## 7. Validation & acceptance (per case and globally)

- **Parity is the gate, not non-silence.** A kernel copy with a mis-ordered hazard yields
  wrong-but-non-silent output. Every case must show **output parity** (bit-exact where deterministic,
  else tight tolerance) flag-on vs flag-off.
- **Batching recovered** (where batching is the point): the audio `diag*` counters
  (`MetalCommandRunner.diag*`, the `apply()` counters in `PdslHotPathBreakdownTest`) show
  `meanDispatchesPerCommit` rising and `aggregateWaits/tick → 0`.
- **No regressions:** the §6 baseline failing-set is driven to zero; the full suite is green with the
  flag on before it can become the default.
- **Signature stability:** aggregated/standalone copies remain instruction-cache-stable (offsets are
  operand-view state). Add an explicit signature test.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Unknown breakage when flag flips | §6 baseline is the first task; treat the failing set as the work-list, don't guess. |
| Silent GPU hazard race (copy reads before producer writes) | Parity gate (§7); flag-gated; A/B on a pinned scene. |
| Signature instability breaks instruction-cache reuse | Offsets as operand-view state; explicit signature test. |
| Standalone copies regress (kernel overhead vs `setMem`) | The §2 inverted short-circuit: emit kernel by default, fall back to `setMem` when executing standalone. Measure both. |
| Mixing this with cross-provider correctness | All-Metal first (audio sub-plan). Hybrid "don't read a not-yet-available provider" ordering is its own follow-on. |

## 9. Intersecting materials (the "one place" index)

- [`audio-scene-redesign/BATCHED_AGGREGATE_COPY.md`](audio-scene-redesign/BATCHED_AGGREGATE_COPY.md)
  — the audio aggregation sub-case (all-Metal beachhead), with the full batching diagnosis.
- [`QWEN_PERFORMANCE.md`](QWEN_PERFORMANCE.md) — ML motivation (copies = 17.8 % of Qwen); names the
  `enableAssignmentCopy` lever (§1/§2 there).
- [`EXPANSION_WIDTH_OPTIMIZATION.md`](EXPANSION_WIDTH_OPTIMIZATION.md) — how `MemoryDataCopy` vs
  `Assignment` interact with the optimization cascade (`MemoryDataCopy` is an optimize-leaf;
  `Assignment` exposes a producer tree). Coincidental to the migration but relevant when
  `Assignment`-copies enter the optimizer. **Not a prerequisite.**
- `SKYTNT_MIDI_MODEL.md`, `REMOVE_PREPARE_ARGUMENTS.md` — touch `MemoryDataCopy`/`Assignment`; review
  for overlap when those areas are migrated.
- Code anchors: `Assignment.java`, `MemoryDataCopy.java`, `MemoryDataFeatures.java`
  (`copy`/`enableAssignmentCopy`), `CodeFeatures.java:305-318`, `DestinationEvaluable.java`,
  `MemoryDataArgumentMap.java`, `MemoryReplacementManager.java`.

## 9.1 Audio connection (2026-07-05)

The audio real-time effort independently converged on a **scoped** version of this
migration as its top lever: `OperationListRunner` forces a host wait before every
non-submittable member, and the PDSL tick's composites still contain `copy()`-helper
`MemoryDataCopy` members (~15 waits/tick at 4096, plus their blit chain-tails) — which
also starves the new `MTLSharedEvent` foreign-dependency bridge (`bridgeCommits=0`),
since each wait resets the dependency chain before a cross-context handoff can reach
`MetalCommandRunner.submit`. Routing just those call sites through the `Submittable`
copy machinery (per §5's incremental path, not the global flag flip) is the plan of
record in [`audio-scene-redesign/NEXT_STEP.md`](audio-scene-redesign/NEXT_STEP.md);
its parity gates and measurements can serve as the first §7 case study.

## 10. Open questions / next actions

1. **Baseline (§6)** — flip `enableAssignmentCopy=true`, run the full suite (CI), record the failing
   set here. *(First action; owner offered to run CI.)*
2. **Audio beachhead** — implement the all-Metal aggregation copy via `Assignment` per
   `BATCHED_AGGREGATE_COPY.md`; prove batching + parity. Smallest provable win.
3. **Inverted short-circuit (§2)** — design the "standalone ⇒ may fall back to `setMem`" decision;
   where in `Assignment.get()` / the execution context that signal is available.
4. **Route direct sites (§3 table)** through the `Assignment` path incrementally, parity-gated.
