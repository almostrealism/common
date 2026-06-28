# Next Step — Reduce the a3 Mixdown Forward's Per-Dispatch Metal Overhead

> **This is the single actionable next step for the AudioScene real-time effort** — deliberately
> surfaced here instead of buried in the migration plan. The full trail is in
> [pdsl-streams-plan/05_MIGRATION_PLAN.md](pdsl-streams-plan/05_MIGRATION_PLAN.md) Phase 2 and the
> [claim ledger](pdsl-streams-plan/01_CLAIM_VERIFICATION_LEDGER.md); this page is the summary you
> point a fresh session at.

## The one thing to do

Cut the **per-dispatch encode + argument-bind overhead** of the a3 PDSL mixdown forward
(`compiled.forward`): `MTLComputeCommandEncoder` creation + per-argument binding, paid ~1000× per
buffer. The target is `base/hardware/.../metal/MetalCommandRunner` / `MetalOperator` (the argument-
binding / encoder path). This is the gate to ~5× realtime.

## Why this is the lever (measured, with receipts)

- The steady-state tick is **~98% `compiled.forward`** — the a2 producer runs ahead and rarely
  blocks a3 (stage timing, run `074fecbf`).
- The forward is **fixed-overhead-bound**: `forward = F + k·N` with **F ≈ 21 ms** independent of
  buffer size (~37–40 ms @4096 → ~45–52 ms @8192, i.e. only ~1.2–1.4× for 2× the samples; runs
  `c87241fb`, `6900496f`).
- The overhead is **dispatch count, not GPU compute**: the real DSP kernels are cheap (FIR
  `multiOrderFilter` over 526k elements ≈ 17 µs run; `sum(8192)` ≈ 22 µs run vs ~25 ms one-time
  compile), and the forward is a ~50–68k-node logical graph compiling to only ~30 native kernels
  (OperationProfile, run `f4ac91ad`).
- 5× needs total per-buffer GPU ≤ 18.6 ms @4096 / ≤ 37.2 ms @8192. **At 4096 the fixed ~21 ms F
  alone already exceeds the 18.6 ms budget**, so F must fall to ≤ ~3 ms — near-eliminating per-op
  encode overhead, not shaving DSP.

## Already ruled out (do NOT re-chase)

- **Cross-scene kernel caching** — *not* a problem. The JVM-wide instruction cache reuses kernels
  flawlessly; a second `AudioScene` recompiles zero kernels (`instrMisses=0`, `instrEvictions=0`;
  run `68bfcc17`). This was the prior session's misdirection — see
  [pdsl-streams-plan/HANDOFF_2026-06-28.md](pdsl-streams-plan/HANDOFF_2026-06-28.md) §8.
- **Command-buffer batching** — already done (`MetalCommandRunner` encodes into one open buffer,
  commits only every `MAX_OPEN=256`).
- **Executor→lock hand-off** — tried (flag-gated `useLockSerialization`), measured only ~3%, reverted.
- **Stateless-stage fusion via `DefaultBlock` / disabling output tracking** — no concentrated
  materialization cost to recover (kernels already internally vectorized); a STOP-signal-sized
  gradient at high blast radius. Output tracking is a contract (`CodeFeatures.copy:306` enforces it).
- **An a2 kernel redesign** — a2 is healthy and hidden behind the forward at 4096; it only gates at 8192.

## The hard part (read before touching it)

- The concrete target is **`MTLComputeCommandEncoder` reuse + argument-bind reduction** across
  dispatches in an open command buffer. Today each dispatch *intentionally* gets its own encoder so
  Metal's cross-encoder hazard tracking auto-serializes dependent dispatches. **Reusing one encoder
  gives that up** — you must insert explicit `memoryBarrier`s between dependent dispatches by hand.
  A missed barrier = silent GPU race = nondeterministic/wrong output across *every* model. This is
  **not behavior-preserving**: flag-gate it, A/B on the sustained harness, validate for races
  (intermittent, hard).
- Expect a **multi-change** effort (encoder reuse + arg-bind reduction, possibly dispatch-count
  cuts); no single edit reaches 5×. Highest blast radius in the codebase (`ar-hardware` Metal
  backend, every model) — the owner's stated preference is this general/reusable framework win over
  a mixdown-only DSP rewrite.

## How to measure it

- Harness: `PdslHotPathBreakdownTest` (per-tick a2/a3 split via `hotAwaitNanos`/`hotForwardNanos`)
  and `AudioScenePdslBenchmarkTest.pdslTickStageTiming` / `pdslTickProfile`, on the pinned dense
  scene (seed 58, curated library at `/Users/Shared/Music`). **Measure ≫ ring depth** (≥200 ticks);
  short prefilled-ring windows hide both the a2 deficit and lazy-compile spikes.
- **Tooling to fix first:** a Java-side CPU/JFR profile of the steady-state `compiled.forward()`
  would pinpoint where the ~21 ms goes (encode vs arg-resolve vs alloc vs sync), but `ar-jmx`
  cannot attach to the forked surefire JVM — `forked_pid_discovery_failed`, because
  `tools/mcp/test-runner/server.py` `_discover_forked_pid` polls `jps` for only `range(30)` (~30 s),
  shorter than studio/compose's ~60 s test-compile. Raise that bound (or poll while the maven PID is
  alive) to restore JFR.

## Acceptance

~5× end-to-end (per-tick ratio ≤ 0.2) on the pinned dense scene at the production buffer (4096
preferred, 8192 acceptable), efx + stereo on, sustained 2 minutes, consistent across ≥3 runs. The
full mechanical gate is [pdsl-streams-plan/05_MIGRATION_PLAN.md](pdsl-streams-plan/05_MIGRATION_PLAN.md)
Phase 5; the design home for the run-ahead-stream construct (where this perf fix becomes structural)
is [pdsl-streams-plan/03_PDSL_STREAMS_DESIGN.md](pdsl-streams-plan/03_PDSL_STREAMS_DESIGN.md).
