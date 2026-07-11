# Plan — Global migration from `MemoryDataCopy` to `Assignment` (kernel-based copy)

> **One-paragraph statement of intent.** Make `Assignment` (a compiled assignment *kernel*) the
> default mechanism for copying between `MemoryData` regions, replacing the host-mediated
> `MemoryDataCopy`. In the cases where an `Assignment` would run *by itself* (a standalone copy not
> batched with anything), it may *short-circuit* back to a direct `MemoryProvider::setMem`-style host
> copy when that is cheaper. **This inverts today's default** (today: host copy by default, kernel
> only behind a flag) and is the long-stated goal that has not yet been achieved. This document is the
> single consolidation point for that effort so it can be picked up across sessions.
>
> **Status (updated 2026-07-06):** default **OFF again**, and the primary motivation has been
> partially superseded. The flag history: default-enabled `de5f2a961` (2026-06-28) → reverted
> `b20a4a175` → re-enabled `468fe809d` → **currently disabled**
> (`MemoryDataFeatures.enableAssignmentCopy` reads `AR_HARDWARE_ASSIGNMENT_COPY` with
> `.orElse(false)`); the javadoc there records why the flip cannot hold yet:
> assignment-based layer input recording **diverges some gradient-descent trainings**
> (`AssignmentRecordingDiagTest` in `engine/utils` reproduces the divergent shape — a single
> `dense(2, 1)` step under both `DefaultCellularLayer.enableMemoryDataCopy` modes — and saves
> per-mode operation profiles for comparison).
> Separately, the §1 batching motivation was addressed by a different design:
> `a3b20e285` (2026-07-02, "Chain every copy on the Semaphore mechanism and deprecate
> `MemoryDataCopy`") makes `AcceleratedOperation.apply` non-blocking — prepare copies chain
> ahead of the kernel, the kernel chains on the last copy-in, replacement copy-back and
> de-aggregation chain after — so host copies no longer force the per-op `waitFor` this plan
> was written to eliminate. `MemoryDataCopy` is now `@Deprecated` with guidance toward
> `Assignment`, `MemoryData.copyFrom`, and the `copy(...)` helpers, and internally performs its
> physical copy through the owning `ComputeContext` (`Hardware.getComputeContext(Memory)`), with
> host `toArray`/`setMem` only as the unmanaged-memory fallback — but it is still **not**
> `Submittable`, so as an `OperationList` member it forces a `pending.waitFor()` chain reset
> (see §9.1). The §3 direct-construction sites have been **partially** migrated (see the updated
> §3 table: aggregation and cross-provider replacement now use `ComputeContext.copy`
> `Submittable`s; `WaveOutput` and `DefaultChannelSectionFactory` route through the flag-gated
> `copy()` helper; `PackedCollection`, `CollectionProvider`, and `FilterEnvelopeProcessor`
> still construct `MemoryDataCopy` directly). The remaining motivations for *this* migration are
> **batch-continuity** (`Submittable` copies keep the semaphore chain intact inside composite
> operations — the audio §9.1 case), optimizer visibility (`Assignment` is a `ParallelProcess`;
> `MemoryDataCopy`'s producer tree is invisible to strategies), and the ML copy cost (§1 Qwen
> numbers) — re-baseline the Qwen numbers before acting on them.

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
  copy paths are `MemoryDataCopy`. (The audio-side diagnosis lived in
  `audio-scene-redesign/BATCHED_AGGREGATE_COPY.md`, removed 2026-07-09 as superseded by the
  Semaphore chaining arc — full text in git history.)
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
- **The flag + helpers.** `MemoryDataFeatures.enableAssignmentCopy` (`:164`, runtime via
  `AR_HARDWARE_ASSIGNMENT_COPY`, **default disabled** — see the header status for the flag
  history and the divergence that blocks the flip).
  `MemoryDataFeatures.copy()` (`:191-251`) and `CodeFeatures.copy()` (`:305-322`) switch
  `MemoryDataCopy → Assignment` when the flag is on, constructing the `Assignment` to **avoid** the
  short-circuit (lambda producers / `traverseEach`, so `Computable.provider(out)` is false → kernel).
- **`Assignment.Runner`** (`Assignment.java:558`) is a `Submittable` — a provider→provider
  `Assignment` already chains through `ComputeContext.copy` (blit-backed on Metal) with no host
  wait. The remaining non-`Submittable` result of `Assignment.get()` is `DestinationEvaluable`
  (source is an `AcceleratedOperation`, `:419-421`), which evaluates into the destination on the
  host — this is the "source is a kernel program" gap (§10.3).

### Call-site inventory (updated 2026-07-06)
The original table listed the sites that bypassed `enableAssignmentCopy` by constructing
`new MemoryDataCopy(...)` directly. Their current status:

| Site | Role | Status |
|---|---|---|
| `MemoryDataArgumentMap` | **Aggregation** copy-in / copy-out | **RESOLVED** — compiled per-replacement `Submittable`s via `ComputeContext.copy` (`ensureCopyOperations()`, `copyInOperations`/`copyOutOperations`) |
| `MemoryReplacementManager.java:309,310` | **Cross-provider replacement** Temp Prep/Post | **RESOLVED** — `context.copy(data, tmp, dependsOn)` lambdas, semaphore-chained |
| `WaveOutput.java:358` | export | **Helper-routed** — `copy(...)`, so flag-gated (still `MemoryDataCopy` while the flag is off) |
| `DefaultChannelSectionFactory.java:314,316` | section input / output | **Helper-routed** — `copy(...)`, flag-gated |
| `PackedCollection.java:229` | constructor copy | **Still direct** (immediate `.get().run()` — standalone, setup-time) |
| `CollectionProvider.java:163` | evaluate-into | **Still direct** |
| `FilterEnvelopeProcessor.java:150,152` | input / output | **Still direct** (immediate `.get().run()`) |

> **Important:** "helper-routed" is necessary but not sufficient. While `enableAssignmentCopy`
> is off, the helper still returns a `MemoryDataCopy`, which is **not `Submittable`** — inside an
> `OperationList` it forces `pending.waitFor()` and resets the semaphore chain
> (`OperationListRunner.run()`). The audio §9.1 work therefore migrates those members to
> `Submittable` form **per call site**, without waiting for the global flag flip. Under all-Metal
> (`AR_HARDWARE_OFF_HEAP_SIZE=0`, now the default) the cross-provider replacement path largely
> does not trigger (no provider mismatch).

## 4. How `Assignment` becomes a *batchable* GPU copy (the mechanism)

`super.get()` compiles `Assignment` to an `AcceleratedOperation`. As a `Submittable`, it dispatches
through `MetalOperator.accept → MetalCommandRunner.submit` into the **open** command buffer. When a
copy-in, a kernel, and a copy-out all encode into one buffer and all touch the **same shared buffer**
(e.g. the aggregate), Metal's per-dispatch-encoder hazard tracking serializes them
(`copy-in → kernel → copy-out`) **without any host wait** — the identical mechanism that already
orders dependent kernels. The only requirement is that the copy be a kernel (not a host op) **and**
that the surrounding code not insert a host `waitFor` around it.

## 5. Scope & sequencing

- **Sub-scope formerly specified (audio, all-Metal):** `BATCHED_AGGREGATE_COPY.md`
  (removed 2026-07-09; git history) routed the two `MemoryDataArgumentMap` aggregation copies
  through the `Assignment` path under `OFF_HEAP_SIZE=0`. Its batching motivation was addressed
  by the Semaphore chaining arc (`a3b20e285`, PR #340), so the beachhead is no longer required
  for batching — only for the `Assignment`-default goal itself.
- **Global migration (this doc):** progressively route *every* direct `new MemoryDataCopy` site
  (§3 table) through the `Assignment` path (or the `copy()` helper), implement the **inverted
  short-circuit** (§2), and make `Assignment`-copy the default — once the baseline (§6) says what
  must be fixed first.

## 6. The first task — baseline "what breaks when `enableAssignmentCopy=true`"

`enableAssignmentCopy` is **not close to ready**; what breaks is currently unknown and must be
*measured*, not guessed.

- **Mechanics.** `enableAssignmentCopy` is resolved at runtime from `AR_HARDWARE_ASSIGNMENT_COPY`
  (enabled/disabled), **default disabled**. It is still a `public static final` interface field, so
  the value is read once at class initialization and a **clean full rebuild** is required for
  consumers to observe a change; until then, modules compiled against the old constant keep
  the old value. Enabled, the **helper-routed** copies (`MemoryDataFeatures.copy` /
  `CodeFeatures.copy`) become `Assignment`; the direct `new MemoryDataCopy` sites (§3) are unaffected
  until separately routed.
- **Plan.** Run the full suite on a clean build with the flag enabled (CI) and record the **exact
  failing set** (test → failure mode) as the baseline; a full-suite run is the right signal because
  the blast radius is unknown, and the failing set becomes the migration's work-list. (To A/B
  without a rebuild, set `AR_HARDWARE_ASSIGNMENT_COPY=disabled`.)
- **Record results below** (date, commit, pass/fail set) so the next session starts from data, not
  memory.

> Baseline results: the flag **was** default-enabled during the 2026-06-28 → 2026-07-02 window
> (see the header flag history). The known failure mode from that period: **assignment-based
> layer input/output recording diverges some gradient-descent trainings** (recorded in the
> `enableAssignmentCopy` javadoc; the related `DefaultCellularLayer.enableMemoryDataCopy=false`
> experiment showed the same shape). `AssignmentRecordingDiagTest` (`engine/utils`, currently
> uncommitted on `feature/assignment-copy-migration`) reproduces the minimal divergent case —
> one forward/backward step of `dense(2, 1)` under both recording modes with identical weights
> and input — and saves per-mode operation profiles under `results/` for `ar-profile-analyzer`
> comparison. A per-test enumeration of the failing set on current master has not been recorded;
> re-run CI with `AR_HARDWARE_ASSIGNMENT_COPY=enabled` to regenerate it when resuming the flip.

## 7. Validation & acceptance (per case and globally)

- **Parity is the gate, not non-silence.** A kernel copy with a mis-ordered hazard yields
  wrong-but-non-silent output. Every case must show **output parity** (bit-exact where deterministic,
  else tight tolerance) flag-on vs flag-off.
- **Batching recovered** (where batching is the point): the `MetalCommandRunner` batch-size
  counters (`totalDispatchCount`/`totalCommitCount`/`meanBatchSize()`) and the commit-cause
  attribution (`getHostCompleteCommitCount()`, `getBridgeCommitCount()`, etc., plus the
  `hostCompleteRequesters` histogram), all logged by `PdslHotPathBreakdownTest`, show
  `meanDispatchesPerCommit` rising and host-complete commits/tick falling.
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

- `audio-scene-redesign/BATCHED_AGGREGATE_COPY.md` (removed 2026-07-09; git history) — the
  audio aggregation sub-case, whose batching diagnosis was superseded by the Semaphore chaining arc.
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
record, handed to the performance effort in
[`audio-scene-redesign/PERFORMANCE_HANDOFF.md`](audio-scene-redesign/PERFORMANCE_HANDOFF.md);
its parity gates and measurements can serve as the first §7 case study.

## 10. Open questions / next actions (updated 2026-07-11)

1. **Resolve the recording divergence (the flip-blocker).** Root-cause why assignment-based
   layer input/output recording diverges gradient-descent trainings, using
   `AssignmentRecordingDiagTest` and its per-mode operation profiles (`ar-profile-analyzer` on
   the generated kernel source is the prescribed instrument). Until this is fixed,
   `enableAssignmentCopy` cannot default on.
2. ~~**Audio beachhead** — aggregation copies via the `Assignment`/`Submittable` path.~~
   **DONE** on master: `MemoryDataArgumentMap` aggregation and `MemoryReplacementManager`
   replacement copies run through `ComputeContext.copy` `Submittable`s;
   `meanDispatchesPerCommit` recovered 1.07 → ~3.9 and the per-op `waitFor` collapse is gone
   (measured history in `audio-scene-redesign/PERFORMANCE_HANDOFF.md` and the pre-2026-07-09
   revisions of `audio-scene-redesign/NEXT_STEP.md` in git history).
3. **Inverted short-circuit / kernel-source `Assignment` (§2).** The remaining structural gap:
   `Assignment.get()` returns a host-side `DestinationEvaluable` when the source is an
   `AcceleratedOperation` (`Assignment.java:419-421`) — exactly the "source is itself a kernel
   program" case. Design the `Submittable` path for it (compile the kernel and chain it, with a
   standalone-execution fallback to direct `setMem` where cheaper).
4. **Route the audio tick's non-submittable members** — the current plan of record, handed to
   the performance effort in `audio-scene-redesign/PERFORMANCE_HANDOFF.md` (item 2): migrate
   the `copy()`-helper members of the tick/forward composites (`CompiledModel`
   Forward/Backward Output capture, `DefaultChannelSection` Input/Output, `WaveOutput` Export,
   plus glue lambdas) to `Submittable` form per call site, scoped, parity-gated — NOT via the
   global flag flip. Success metric: host-complete commits/tick ~42-47 → ≲ 5.
5. **Route the remaining direct sites** (`PackedCollection:229`, `CollectionProvider:163`,
   `FilterEnvelopeProcessor:150,152`) through the helper or `Assignment` path — lower priority;
   the first two are standalone/setup-time copies where the §2 short-circuit would apply anyway.
