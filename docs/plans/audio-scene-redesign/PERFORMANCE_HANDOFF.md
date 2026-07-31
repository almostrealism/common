# Performance Handoff — the Fixed-Cost Floor and the 1024 Buffer Target

> **Written 2026-07-09 for the agent taking over performance work** (working from the
> other clone). The quality/parity work continues separately on this clone; this
> document is the complete brief for the three performance items, with the sweep data
> that motivates them and the acceptance bar. The owner has already switched
> `AudioScene.DEFAULT_REALTIME_BUFFER_SIZE` to **1024**, accepting that it is not
> reliably real-time on all hardware *until this work lands* — closing that gap is the
> mission.

## Why 1024, and what stands in the way

Smaller buffers buy audible quality and playability: at 1024 every efx feedback gene
delay sits inside the block-parallel ring band at any usable tempo (no clamping
compromise), the automation update rate quadruples (10.8 Hz → 43 Hz — smaller steps on
hot buses, finer delay-modulation approximation), and ~23 ms latency is compatible with
the live controller-driven automation goal. The DSP itself is already buffer-size
agnostic (the 2026-07-09 ring fixes made every ring delay-derived or
seconds-denominated).

What stands in the way is a **frame-count-independent per-tick cost floor**, measured
directly:

## Buffer-size sweep (2026-07-09, worker M1, dense curated scene, seed 58, 1126 elements)

Sustained 200-tick p50, early arrangement, efx+reverb on, `renderAheadSlots=24`,
`PdslHotPathBreakdownTest` with `AR_HARDWARE_ASYNC=disabled` (see "measurement notes"):

| buffer | budget ms | p50 ms | p50 ratio | over-budget | commits/tick | a2 ms/tick |
|---|---|---|---|---|---|---|
| 4096 | 92.9 | 27.5 | 0.30 | 0/200 | 46.8 | 18.8 |
| 2048 | 46.4 | 23.4 | 0.50 | 3/200 | 43.2 | 11.3 |
| 1024 | 23.2 | 22.2 | **0.96** | 82/200 | 41.5 | 7.6 |
| 512 | 11.6 | 22.4 | 1.93 | 200/200 | 40.8 | 4.4 |

Fit: **tick ≈ 21.6 ms fixed + ~1.4 µs/frame** (early content). Full-arrangement
anchors (`GenerateAudioFileTest`, 120 s, async mode): `generateRealtimeX` 1.13 @ 4096
(mean tick 81.9 ms) → 0.39 @ 1024 (59.9 ms) — dense content raises the floor to
**~52 ms fixed + ~7.4 µs/frame**. An 8× frame reduction moved the p50 by 5 ms: the
floor is the whole game, and it is made of exactly two frame-independent components,
visible in the counters:

1. **Host-completion commits: ~41–47 per tick at every buffer size**, 100 % of all
   commits (`maxOpenCommits=0`, `bridgeCommits=0` — the `MTLSharedEvent` bridge never
   fires, even after the PR #340 chaining arc). Each is a synchronous host wait; the
   count is per-tick operation structure, so it does not shrink with the buffer.
2. **Per-note fallback dispatches: ~15–19 per tick at every buffer size** (continuing
   notes scale with active note count, not frames; `fallbackCount` ≈ 3.0–3.7 k per 200
   ticks at all four sizes while `batchedDispatchCount` scales down with window width).
   On dense sections this component roughly doubles the floor — it is the difference
   between the 21.6 ms early-content floor and the 52 ms full-arrangement floor.

## The three work items

### 1. Fix the argument-preparation NPE — RESOLVED 2026-07-10 (PR #344)

> Fixed on `defect/process-details-init` and merged: the cause was exception-unsafety
> in `ProcessDetailsFactory.init` (a Metal 31-buffer compile failure thrown mid-init
> poisoned the operation's fast path), **not** the concurrency race hypothesized
> below; the fix builds an immutable `PreparedArguments` snapshot published once,
> guarded by the ungated `ProcessDetailsFactoryRecoveryTest`. Full record:
> `docs/plans/ARGUMENT_PREPARATION_NPE.md`. The benchmark passes in default (async)
> mode again — post-fix baseline on the worker M1: p50 39.4 ms @ 4096 (ratio 0.42),
> commits/tick ≈ 47 with `bridgeCommits = 0`, so items 2 and 3 below are unchanged.
> The original brief is kept for context:

### (original item 1) Fix the argument-preparation NPE (prerequisite — it broke the benchmark)

`PdslHotPathBreakdownTest` dies on master with
`NullPointerException: kernelArgEvaluables[i] is null` at
`ProcessDetailsFactory.construct(:526)` in the a2 batched path. Full diagnosis in
`ARGUMENT_PREPARATION_NPE.md` (delivered separately to the performance clone). The
short form: `init()` unconditionally recreates the factory's argument arrays per call
(`:380-382`) and `construct()` reads them unsynchronized; PR #340's asynchronous
argument delivery lets two threads interleave `init`/`construct` on a shared compiled
operation (the runner has an a2 producer thread and an a3 consumer thread; compiled
operations are shared JVM-wide). Supporting evidence: the whole sweep above ran green
with `AR_HARDWARE_ASYNC=disabled`, which serializes argument delivery. The likely
proper fix is thread-confined details construction (build into locals, publish once)
rather than a coarse lock. Add an ungated two-thread regression test — the benchmark
that catches this is curated-gated and never runs on CI.

### 2. Collapse the host-completion commits (the submittable-composite migration)

The ~41–47 waits/tick come from non-`Submittable` members of the tick/forward
composites (plain lambdas and copy helpers), each forcing `pending.waitFor()` in
`OperationListRunner.run()` and chopping the dependency chain before it can reach
`MetalCommandRunner.submit` — which is why `bridgeCommits = 0`: the bridge is starved
by the very waits it was built to replace. The migration (inventory the
non-submittable members, route copies through the `Submittable` machinery scoped to
those call sites, let the chain reach the bridge) was specified in this folder's
pre-2026-07-09 `NEXT_STEP.md` revision (git history) with the full mechanism notes.
Success looks like commits/tick collapsing from ~42 toward ~1–5 and `bridgeCommits`
becoming nonzero.

### 3. Route continuing notes through the batched scatter-add

The per-note fallback (`fallbackCount`) is the *continuing-note* path:
`BatchedPatternLayerRenderer` batches newly-starting notes but continuing notes fall
back to per-note `renderNotes`/`sumToDestination` dispatches. The batched placement
primitive already exists and is the literal replacement:
`BatchedPatternRenderer.buildScatterAdd` (its own javadoc says it is the batched form
of the per-note ranged accumulate). Routing the continuing notes' cached clips through
it is a wiring change reusing existing infrastructure. Success looks like
`fallbackCount` ≈ 0 for batchable note shapes and the dense-content floor dropping
toward the early-content floor. Guard with the batched sentinel battery
(`studio/music`: `BatchedDispatchSentinelTest`, `BatchedVsPerNoteRmsTest`,
`BatchedPercussionVsPerNoteRmsTest`, `BatchedRealtimeTickTest`) plus
`PdslSetupBreakdownTest`.

## Acceptance

On the pinned dense scene (seed 58), sustained ≥ 200 ticks at **1024**, efx+reverb on,
default (async) hardware mode, `PdslHotPathBreakdownTest` green:

- p50 ratio ≤ **0.5** at 1024 on the worker M1 (the sweep machine) — which projects
  comfortably under budget on the production M4 (~3× faster at 4096);
- commits/tick ≤ ~5 with `bridgeCommits > 0`;
- `fallbackCount`/tick ≈ 0 on batchable shapes;
- batched sentinels and `PdslSetupBreakdownTest` green; `GenerateAudioFileTest`
  non-silent with `generateRealtimeX` improved at 1024.

## Measurement notes

- `PdslHotPathBreakdownTest.BUFFER_SIZES` is a hardcoded array (`{4096, 8192}`) — edit
  it to sweep candidates (the 2026-07-09 sweep used `{4096, 2048, 1024, 512}`), and
  revert after. `GenerateAudioFileTest.BUFFER_SIZE` likewise.
- Until item 1 lands, run the benchmark with `-DAR_HARDWARE_ASYNC=disabled` (via the
  test-runner `jvm_args`) to dodge the race. Cross-mode anchors at 4096 measured sync
  within ~20 % of async (sync mildly faster), so sync-mode sweeps are representative —
  but re-verify that anchor after item 1, and take acceptance numbers in async mode.
- Absolute numbers above are the worker M1; the owner's M4 measured ~0.163 ratio at
  4096 in realistic conditions (≈3× faster). The relative curve is machine-stable;
  re-measure on the M4 before declaring the acceptance bar met or missed.
