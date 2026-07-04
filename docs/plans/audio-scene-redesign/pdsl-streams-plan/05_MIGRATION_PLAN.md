# 05 — Migration Plan (phased, with mechanical gates)

> Each phase has an **entry condition**, a body of work, and an **exit gate** that is a machine
> check, not a narrative. No phase starts until the prior phase's gate is green. The ordering is
> deliberately *measurement-first*: the entire performance strategy is gated on one decomposition
> measurement (Phase 1) so we never spend a 14-hour block tuning a structure we have not shown can
> reach the target. All work is behind flags, parity-checked at each step, with CI on the exact
> commit as the only ground truth (integrity history demands this — 07/T7-T8).

## Phase 0 — Pinned harness + instruments (no feature work)

**Entry:** plan approved.
**Work:**
- Build the pinned, CI-identical proving harness (07/T1): the dense scene (seed + committed
  `pattern-factory.json` + committed/seeded arrangement), default driver/flags, real samples,
  with graceful CI skip when the library is absent and a loud local failure on accidental
  silence. Add a sparser scene for headroom.
- Add the render-once / placement instruments (07/T2): a `sumToDestination` dispatch counter
  (count + nanos) and the thread-tagged synthesis counter; wire the existing
  `cacheHits/cacheMisses/cachePuts`, `evalNanos`, `marshalNanos`, and `pdslTickStageTiming` into
  one breakdown report.
**Exit gate:** the harness reproduces the *same element set* and the *same per-stage breakdown
shape* across ≥3 runs of the pinned dense scene (G7 determinism); the breakdown report prints all
five terms (synth/place/gather/marshal/mix) with date/machine/flags. No claim about *values* yet.

## Phase 1 — Decomposition measurement + feasibility verdict (THE GO/NO-GO)

**Entry:** Phase 0 gate green.
**Work:**
- Produce the full attributed cost breakdown (04 §2) on the pinned dense scene, end-to-end, at
  the production buffer size. Confirm render-once empirically: synthesis misses ≈ distinct
  elements, **not** ≈ active-notes × windows.
- Compute the ceiling (04 §3) and record the gate verdict.
**Exit gate — DONE (2026-06-27): PIVOT to the a3 forward.** The measurement
([04 §0](04_FEASIBILITY_GATE.md)) showed `hotAwait ≈ 0.01 ms` (a3 never waits on a2 — a2 is **not**
the bottleneck) and the entire tick is the **a3 mixdown forward** (36.9 ms @4096, 57.5 ms @8192),
which exceeds the 5× budget at both sizes and scales sub-linearly with frame count (fixed
dispatch overhead). So the gate took the **"mix alone exceeds budget → re-scope to the a3
forward"** branch. The decoupling invariant is *measured*, not just asserted; the prior
"a2-bound / 5× needs an a2 kernel redesign" framing is refuted. **a2 placement batching is
demoted to a secondary optimization; the primary 5× lever is reducing the a3 forward's per-buffer
dispatch/encoding overhead (PDSL kernel fusion).** This gate was the project's central decision
and is now recorded with its receipts (`PdslHotPathBreakdownTest`).

## Phase 2 progress log (measured)

- **2026-07-04 — RE-ATTRIBUTION + fresh sustained numbers (post kernel-tooling merge).** Three
  updates to entries below, each with receipts:
  - **WIN 3's mechanism was misattributed and its fix is now removed.** The mid-stream ~29–33 s
    "compile" spike (and the ~14–29 s-per-shape setup cost the pre-warm hid) was **not** native
    kernel compilation — it was the `uniqueNonZeroOffset` gather-collapse probe running on the
    first `evaluate()` of each batched kernel shape and always failing (scatter-add chains sum
    overlapping notes, so no unique contributor exists). Fixed at the source by
    `BatchedPatternRenderer.sumNoteAxis` (`setReplaceLoop(false)` on the note-axis sums); the
    whole-arrangement pre-warm (`preWarmMaxSeconds`) now defaults to **0** and the render ring
    prefills one buffer — setup is honest: **128.6 s → ~2.3 s**, and `GenerateAudioFileTest`
    reports `setupSeconds=2.23` / `generateRealtimeX=1.79` @8192 (run `f5f8486a`). Full record:
    `../SETUP_FRONT_LOADING_HANDOFF.md`; correction note: `HANDOFF_2026-06-28.md` §9.
  - **The copy-wait collapse was fixed by Semaphore chaining, not the planned copy migration.**
    `a3b20e285` chains prepare copies, kernel, replacement copy-back, and de-aggregation on the
    Semaphore mechanism (`apply` never blocks the host; `MemoryDataCopy` deprecated). Measured
    (run `1dadc516`, 200 sustained ticks, seed 58, efx+reverb): `meanDispatchesPerCommit`
    1.07 → **2.90 @4096 / 3.36 @8192**; commits still 100 % host-completion-driven
    (63.3 / 72.1 per tick, `maxOpenCommits=0`).
  - **Current sustained truth and the new lever ranking:** 4096 p50=36.7 ms (ratio **0.40**),
    p95=58.5; 8192 p50=50.5 ms (ratio **0.27**), p95=145. The commit-cause requester histogram
    attributes the waits: (1) `f_collectionProductComputation_*` ≈ 23/tick at both sizes — the
    per-render-cell `PatternSystemManager.sum` → `AudioSumProvider.adjustVolume` synchronous
    `.into().evaluate()`, currently a **multiply by 1.0** (auto-volume hardcoded off, volume
    never set) — a third of all commits; (2) `mtlBlitCopy` ≈ 17–20/tick, numerically matching
    the per-note fallback rate (`fallbackCount` 3190–3541 per 200 ticks vs
    `batchedDispatchCount` 748–1395); (3) a ~1/tick tail of forward-stage requesters. The
    "two deep levers" framing in WIN 3 is superseded accordingly — the ranked queue is now in
    [`../NEXT_STEP.md`](../NEXT_STEP.md) (rewritten 2026-07-04).

- **2026-06-28 — WIN 3: kernel pre-warm eliminates the compile-spike dropout.** Confirmed the 33 s
  spike was the a2 batched renderer compiling a new `(bucket, sourceLength, targetLength)` kernel
  shape mid-stream (`rendererCompilesDuringRun=2` at 8192). Fix: `AudioSceneRealtimeRunner` setup
  render-sweeps the **whole arrangement** once (a2 only — `renderOp` is clock-neutral) before the
  clock starts, forcing every shape to compile off the real-time path (`preWarmMaxSeconds`,
  default 300, ≤0 disables). **An adaptive "stop when no new shape for N buffers" early-stop is
  unsafe** — density varies, so a quiet stretch ≠ all shapes seen (my first attempt used it and
  still spiked; the full sweep fixed it). Result (200-tick sustained, 8192): `rendererCompiles`
  2→**0**, **max tick 28,038 ms → 248 ms** (dropout gone), `hotAwait` 310 → 38 ms. Cost: ~50–65 s
  one-time setup (renders the arrangement once; could later be optimized by enumerating shapes).
  - **Three wins now landed (≈82 LoC):** input-tracking-off (forward ~69→~41 ms), ring 8→24,
    pre-warm. **System: ~2× *with 28 s dropouts* → ~2.9× *robust* (no dropouts).**
  - **Sustained truth: 8192 p50 = 63.6 ms (2.9×), p95 = 175 ms (ratio 0.95); 4096 p50 = 38 ms
    (2.1×).** Still not 5×.
  - **Two deep levers remain (both needed; neither alone reaches 5×):** **(A) a2 sustained
    deficit** — even with no compile spikes, a2 falls behind over a long run (`hotAwait` ~38 ms),
    so the tick = forward (41 ms) + a2 wait. Cut a2's per-buffer GPU work (batch continuing-note
    placement via `BatchedPatternRenderer.buildScatterAdd`; reduce marshal) so a2 keeps up → tick
    ≈ forward (41 ms = 4.5×). **(B) a3 forward 41→≤37 ms** via the deep output-materialization
    fusion (entangled with `accum_blocks`) → 5×; this is also what 4096 needs (its forward is ~2×
    over the 18.6 ms budget).


- **2026-06-28 — CORRECTION: the "5.29× at 8192" was a SHORT-WINDOW ARTIFACT (4th time on this
  project). A SUSTAINED 200-tick run (~37 s audio at 8192) is the real acceptance measure:**

  | Buffer | p50 | p95 | max | over-budget |
  |---|---|---|---|---|
  | 8192 | **63 ms (2.9×)** | 176 ms (ratio 0.95) | **32,876 ms (≈33 s compile spike)** | 7/200 |
  | 4096 | **39 ms (2.0×)** | 73 ms | 136 ms | 5/200 |

  Over 200 ticks the 24-slot prefilled ring **drains** (a2's sustained deficit returns) and **lazy
  kernel-compile spikes** hit mid-run (a new note kernel shape → a ~33 s compile → a guaranteed
  audio dropout). So **sustained ≈ 2.9× (8192 p50), not 5×**, with a barely-real-time tail
  (p95 ratio 0.95). The input-tracking win IS real and sustained (forward ~44 ms vs original 69 ms);
  the deep ring helps the floor but not the sustained deficit.
  - **Reinforced lesson:** never trust a short measured window for a *sustained* requirement — the
    prefilled ring hides both the producer deficit and lazy-compile spikes. Measure ≫ ring depth.
  - **Real sustained-5× blockers (hard, multi-lever):** (1) **kernel pre-warm (R4)** — the 33 s
    mid-run compile spike is the most catastrophic; all note/mixdown kernel shapes must be compiled
    in `setup` before the clock starts (shapes are data-dependent → must enumerate/force them).
    (2) **a2 sustained deficit** — cut a2's per-buffer GPU work (batched continuing-note placement
    via `buildScatterAdd`, marshal reduction). (3) **a3 forward** still ~44 ms > 37.2 ms — the deep
    output-materialization fusion. None of these alone reaches 5×; all three are needed.


- **2026-06-28 — MILESTONE: ~2× → ~5× at 8192 (target reached, consistency pending).** Two simple,
  safe, general changes, both measured on the densest real scene (seed 58, efx+reverb ON, 24-tick
  warmup, output bit-identical at peak 0.49): **(W1)** per-layer *input* tracking off for the
  inference mixdown (below); **(W2)** render-ahead ring depth 8 → 24 (`renderAheadSlots`) — a2 and a3
  share the one Metal runner, so a2's per-buffer time varies and a shallow ring drained on the
  spikes, making a3 wait ~21 ms/tick; a deeper ring absorbs the variance (a2 keeps up on average),
  dropping `hotAwait` 21 → ~0. **Result: 8192 reached ratio 0.19 = 5.29× in a clean run** (base
  per-tick ~30 ms ≈ 6×); across clean runs it ranges ~4.6–5.3× (avg ~5×). 4096 ~2.3–2.9×.
  - **Remaining for *consistent* 5×:** (1) **variance** is now the #1 blocker at 8192 — base ticks
    are ~30 ms (≈6×) but occasional 42–48 ms spikes (GC/alloc churn — a2 `NoteAudioCache` double-free,
    per-tick percussion `fit()` copies — and/or M1 thermal) pull the mean to ~35 ms; tame these and
    8192 is reliably ≥5×. (2) **4096** needs the a3 forward cut ~2× more (it is ~33–39 ms vs the
    18.6 ms budget) — the deep output-materialization fusion. (3) A **sustained 2-minute** all-channel
    run with efx+stereo on is the real acceptance measurement (the 12-tick window only samples it).


- **2026-06-28 — WIN: per-layer input-tracking off for inference → a3 forward halved at 8192.**
  Root cause of the forward's dispatch cost: each PDSL layer (`LayerFeatures.layer`) was built with
  `init(inputShape, Layer.ioTracking, true)` — input tracking on — adding a per-layer entry-cell
  *copy* (`into(...)`) that records the forward input for backprop. Pure overhead for the
  inference-only mixdown. Measured (`PdslHotPathBreakdownTest`, densest seed=58, efx ON, 24-tick
  warmup): **a3 forward 8192 ~69 → ~36 ms (halved)**; tick ~58 ms = **3.19×** (was ~2×); 4096
  forward ~40 → ~39 ms (input copy smaller there). Output identical (peak 0.49).
  - **2026-06-28 — CORRECTION (how the win is implemented): use the documented inference API, not a
    static hack.** First implementation used a process-wide `DefaultCellularLayer.enableTracking`
    static toggled around the build — a thread-unsafe global, and (worse) it tempted a follow-on
    `enableOutputTracking=false` "fusion" that threw at `CodeFeatures.copy:306`. Per
    `docs/internals/layer-tracking.md`: (1) **output tracking on `DefaultCellularLayer` is a
    contract, not an option** — the exit-cell copy into a stable buffer is what `accum_blocks`/
    `route` (via `CaptureReceptor`) and the `CompiledModel` forward-output capture read; the
    `copy:306` throw is the architecture enforcing that contract, not a bug to route around. (2)
    Input-tracking-off is the *documented* inference path, applied automatically when a model is
    compiled with `backprop=false`. The mixdown was compiled with `model.compile()` ==
    `compile(model, true, …)` == **backprop=true**, so input tracking was *not* auto-disabled — that
    is why the static hack appeared to "do work." **Fix:** `compileMixdownModel` now calls
    `model.compile(false)`; this disables input tracking via the intended scoped path *and* skips
    building the unused backward graph. Both static flags and the `LayerFeatures` rewire are
    reverted — `domain/graph` is back to baseline; only `studio/compose` changed. Training/ML
    unchanged (they compile with backprop on).
    **Measured after the refactor** (`PdslHotPathBreakdownTest` run `6900496f`, 200-tick sustained,
    densest seed=58 / 1126 elements, efx+reverb ON, renderAheadSlots=24, `rendererCompilesDuringRun=0`
    at both sizes — pre-warm intact): **4096** p50=38.5 ms (ratio 0.41 = 2.4×), forward=36.7 ms,
    await=4.8 ms, a2Total=22.1 ms, peak=0.59, overBudget 1/200 — *no regression vs the pre-refactor
    4096 (~39 ms), the clean comparison.* **8192** p50=71.2 ms (ratio 0.38 = 2.6×), forward
    **avg**=52.5 ms, await=39.2 ms, a2Total=70.4 ms, peak=0.71, overBudget 13/200 (p95 ratio 1.13).
    The 8192 forward *average* is spike-inflated this run (tick mean 92.8 ≈ 52.5 fwd + 39.2 await; the
    13 over-budget ticks pull the mean up); at 8192 the binding cost is now **a2** (70 ms + 39 ms
    await), not the a3 forward. `compile(false)` builds strictly less than the old `compile()`+static
    path and yields an identical forward chain, so it cannot regress the forward graph — the worse
    8192 tail is the pre-existing GC/thermal spike variance (and 8192 runs second, hotter). Net: the
    refactor is **correct and non-regressive**; it does not by itself move the 5× needle.
- **Honest remaining math (GPU is shared a2+a3):** 5× needs total per-buffer GPU ≤ 37.2 ms (8192) /
  ≤ 18.6 ms (4096). Now ~57 ms (8192) / ~42 ms (4096). Must cut **both** a2 (~35 ms) and a3 (~36–39 ms)
  another ~1.5–2×. Next-lever direction is now set by **profiler evidence**, not theory (see below).
- **2026-06-28 — PROFILER EVIDENCE redirects the a3 lever (and rules out two prior ideas).** Loaded
  `studio/compose/results/pdsl-cutover/pdsl_tick_profile.xml` (an `OperationProfileNode` over the
  8192 tick) into `ar-profile-analyzer`. **`node_count = 48767` ops/tick**; `compiled_operations=0`.
  `find_slowest_by_category run` shows **no single fat kernel** — the largest *exclusive* run time is
  `add(1)` at 0.087 ms (673 inv); `multiply(41)`/`equals(41)` at ~0.5 µs each. The a3 forward is
  **dispatch/Java-encoding-count bound**: tens of thousands of microsecond ops, spread broadly
  (per-channel FIR, `delay-bank-head-write-6x8192`, reverb, `concat(6,8192)`=4.1 %, routing, sum).
  The biggest *inclusive* aggregates are three `PDSL Automation Refresh` nodes (6.3 s = 28 %), **but
  that is first-tick compile inflation, not steady state**: `automationRefresh` is a *separate* tick
  step (`AudioSceneRealtimeRunner` ~L475 `tick.add(automationRefresh)`), and the 24-tick-warmup
  `PdslHotPathBreakdownTest` shows steady-state tick ≈ `hotAwait + hotForward` (~1 ms left for
  automation). The `(41)`-shaped ops are the piecewise automation-curve eval — a compile cost.
  - **Ruled out (evidence-contradicted): a3 stateless-stage fusion via `DefaultBlock`.** Confirmed
    that `scale`/`repeat`/`sum_channels` build via `FEATURES.layer` → `DefaultCellularLayer`
    (`PdslBuiltins`), so converting them to `DefaultBlock` *would* drop their exit-cell copies — but
    the profile shows **no concentrated stateless-materialization cost** to recover. It would be a
    small local gradient next to a large gap = the §4 **STOP signal**, at high blast radius (every
    PDSL consumer; `accum_blocks` arms can't convert). Do not pursue it as the 5× lever.
  - **Also ruled out earlier: disabling `DefaultCellularLayer` output tracking** (a documented
    contract; threw at `CodeFeatures.copy:306`).
  - **Compile-vs-run PROOF (the clincher).** `get_timing_breakdown` on every "fat" forward node
    shows ~99 % is **one-time compile**, run is negligible: `multiOrderFilter(6,8192)` compile 392 µs
    / **run 17 µs**; `sum(8192,1)` compile 25.2 ms / **run 22 µs** (99.3 % compile); `Loop x8192`
    compile 199 ms / **run 169 µs** (99.4 %); `concat(6,8192)` compile 538 µs / **run 12 µs**. The
    big *inclusive* durations are child-op compile, paid once at setup (the `compiled.forward(...)`
    warm-up call). So the steady-state forward is the **sum of hundreds–thousands of ~10–20 µs ops +
    Java-side per-dispatch encode/submit** — purely **dispatch-COUNT bound**, no kernel hotspot, no
    materialization concentration.
  - **The kernels are ALREADY vectorized — corrected.** `get_source` on a `sum(8192,1)` shows the
    generated Metal kernel already loops `for (i = 0; i < 64; …)` *internally* (one fused dispatch
    summing 64 taps, 22 µs run); the FIR is fused over all 6 channels. So there is **no unbatched-loop
    antipattern** to fix — the earlier "batch the sums" idea is wrong; they're already batched. The
    ~1000-op count is the *inherent per-stage DSP structure* (FIR, delay banks, delay networks,
    reverb, routing, channel ops), each already a handful of well-vectorized dispatches.
  - **Fixed-overhead quantified (the hard target).** Fitting `forward = F + k·N` to the two measured
    points (36.7 ms @4096, 52.5 ms @8192) gives **F ≈ 20.9 ms of buffer-size-independent per-dispatch
    overhead** (the ~1000-op encode/submit/arg-resolve cost) and k ≈ 0.0039 ms/sample. Implication:
    *halving* per-dispatch overhead reaches only ~3.5× @4096; hitting 5× @4096 (≤18.6 ms) requires
    cutting that ~21 ms to **≤~3 ms** — i.e. **near-eliminating per-op overhead by batching the
    forward's ~1000 Metal commands into one command buffer / one submit** (an `ar-hardware`
    Metal-backend change; general and reusable; highest blast radius — validate against the whole
    model/test suite). The ratio also *improves* with buffer size as F amortizes (8192 forward ratio
    0.28 < 4096's 0.40), but enlarging the buffer to hit 5× violates the live-automation latency
    budget (owner Q3: >8192 unusable), so it is **not** a legitimate path.
  - **Tooling blocker for the Java-side profile (2026-06-28):** attempted a JMX/JFR profile of the
    steady-state `compiled.forward()` Java hot path; the test runner reported
    `forked_pid_discovery_failed`, so `ar-jmx` could not attach, and `-XX:StartFlightRecording` via
    `jvm_args` corrupts the surefire fork channel. (Fixing the forked-JVM PID discovery would restore
    JFR.) **Localized the target by source reading instead** — see next bullet.
  - **EXACT TARGET (source-localized, 2026-06-28):** the overhead is in
    `base/hardware/.../metal/MetalCommandRunner.java` `submit()` (~L126). Metal command buffers are
    **already batched** (encoded into one open buffer, committed only every `MAX_OPEN=256`), so the
    earlier "batch into one command buffer" idea was already done. The real per-dispatch cost is that
    `submit()` does `await(executor.submit(() -> runInPool(() -> command.encode(openBuffer))))` — a
    **blocking round-trip onto a single-threaded executor + a per-dispatch autorelease pool** — paid
    ~1000×/forward. It exists because the runner is **shared between the a2 and a3 threads** and all
    buffer state is "executor-thread only."
  - **EXPERIMENT (2026-06-28): executor hand-off hypothesis FALSIFIED.** Implemented a flag-gated
    (default-off) `useLockSerialization` path that does the encode inline on the caller thread under a
    `ReentrantLock` instead of the per-dispatch `await(executor.submit(...))`, built the full closure,
    and A/B'd on `PdslHotPathBreakdownTest`. **Clean 4096 comparison (both low-variance): forward
    35.69 ms (lock) vs 36.74 ms (executor) = ~1 ms / ~3 %.** (8192 looked ~16 % better but its baseline
    run was anomalously noisy; the hand-off saving is ~1 ms fixed regardless of buffer size, so that
    delta is noise.) **So the per-dispatch executor hand-off is only ~3 %, NOT the ~21 ms `F`.**
    Reverted the change (a ~3 % gain in the GPU execution core, for a falsified hypothesis, isn't
    worth keeping); execution core is back to pristine. The lock path *worked* (compiled, non-silent,
    no wedge, test green) — and *may* help tail latency (p95/overBudget looked better) — but that is
    unverified (one run) and not the median lever.
  - **The ~21 ms `F` is therefore the per-dispatch ENCODE itself** — `MTLComputeCommandEncoder`
    creation + per-argument binding (the `offsetArr`/`sizeArr` + ~6 device-buffer binds visible in the
    generated kernel signature) + dispatch, ~1000×/forward. **NOT** the thread hand-off (falsified),
    **NOT** command-buffer commits (already batched), **NOT** GPU compute (kernels ~10–20 µs). **Next
    target:** `MTLComputeCommandEncoder` / `MetalOperator` / the argument-binding path in
    `base/hardware` metal — measure and reduce per-dispatch encoder-create + arg-bind cost (e.g. reuse
    one encoder across dispatches in an open buffer; shrink/cache the per-dispatch argument set). Same
    high-blast-radius caveat: flag-gate, A/B on the sustained harness, validate broadly.
  - **CORRECTNESS WARNING for the encoder-reuse option (`MetalCommand` javadoc).** Each dispatch
    *intentionally* creates its own `MTLComputeCommandEncoder` — "one encoder each, so Metal's
    cross-encoder hazard tracking" auto-serializes dependent dispatches (B reads A's output). Reusing
    one encoder across dispatches **gives up that automatic tracking**, so it requires inserting
    explicit `memoryBarrier`s between dependent dispatches *by hand*. Unlike the executor→lock
    experiment (which was semantically equivalent serialization), this is **not** behavior-preserving:
    a missed barrier = silent GPU race = nondeterministic/wrong output across *every* model. This must
    be done with deep Metal-hazard care and validated for races (hard — they're intermittent), not
    rushed. And it is likely *insufficient for 5× alone* (encoder-create is only part of the ~21 ms),
    so 5× is a **multi-change** framework effort (encoder reuse + arg-bind reduction + possibly
    dispatch-count cuts), each flag-gated and broadly validated — not a single edit.
  - **The real a3 lever (general/reusable — owner's stated preference):** the wall-clock is
    **per-dispatch Java-side overhead** (command encode / submit / argument resolution) × op count,
    not GPU compute and not materialization. Two honest options, both substantial: **(a)** reduce
    *per-dispatch overhead* in the framework execution path (`OperationList.run()` / Metal command
    submission / argument resolution) — this is the general/reusable win, helps *every* model, but is
    the **highest blast radius in the codebase** and must be driven by a **Java-side (JFR/ar-jmx)
    profile of the steady-state forward**, not guessed; **(b)** cut op count by a *structural DSP
    redesign* (fewer stages / cross-stage fusion) — mixdown-scoped but risks acoustic behavior and is
    a deep rewrite. **Precise next step:** JFR-profile the steady-state `compiled.forward()` Java hot
    path to localize where the ~16–26 µs/op overhead goes (arg resolution? encoding? allocation?
    sync?), then optimize that. Phase-2 rebuild-vs-tweak escalation (§4); a focused multi-cycle
    session — not safely startable blind at a session tail.
- **a2 lever (for 8192):** batch continuing-note placement via `buildScatterAdd` (blocked today:
  cached audio sits in scattered per-note buffers — needs a contiguous render-once pool).
- **Variance:** per-tick 8192 ranges ~41–210 ms (min near budget); spikes are GC/alloc/thermal churn.

## Phase 2 — Reduce the a3 mixdown-forward dispatch overhead (the primary 5× lever)

**Entry:** Phase 1 = PIVOT to a3 forward (done).
**Work:**
- **2a — Profile the forward. DONE (2026-06-27).** Two run-time points give a linear fit:
  **`forward ≈ 16.3 ms fixed dispatch overhead + 0.005 ms/frame`** (frame-proportional DSP =
  20.6 ms @4096, 41.2 ms @8192). The `pdslTickProfile` graph has **48,767 operation nodes** and
  takes ~22 s to **compile** (one-time; the profile's durations are compile-dominated — cross-
  checked because "PDSL Automation Refresh" reads 28% there but only 0.45 ms/tick at run). The
  heaviest forward subtrees are the **per-channel FIR convolutions** (`multiOrderFilter`,
  "fir layer (6,8192)") and the **reverb `delay_network`**. Two consequences:
  - **At 4096 (preferred), the fixed 16.3 ms dispatch overhead alone is ~88% of the 18.6 ms 5×
    budget** ⇒ cutting the per-buffer **dispatch count** (graph fusion) is *mandatory* for 4096,
    independent of the FIR.
  - **At 8192, the frame-proportional FIR cost (41 ms) dominates** ⇒ optimizing/fusing
    `multiOrderFilter` matters most there.
  - The 22 s compile of the 48,767-node graph is a one-time spike ⇒ kernel pre-warm (R4) for
    run-to-run consistency, separate from the steady-state run cost.
- **2b — Fuse / reduce dispatches.** Cut the per-buffer dispatch count: fuse channel-uniform
  stages (vectorized `for each channel` is already on — verify it actually collapses to one
  kernel), fuse adjacent elementwise stages (clip→fir→scale), and reduce the
  `delay_network`/`feedback` per-line dispatch fan-out. These are PDSL-platform / compiled-model
  improvements ("more GPU parallelism / better PDSL platform"), **not** "do less DSP."
- Keep changes behind a flag; the legacy forward stays for A/B.
**Exit gate:** on the pinned dense scene (efx+reverb ON), `hotForward` drops below the 5× budget
(≤18.6 ms @4096 / ≤37.2 ms @8192) — or, if it lands between ratio-1 and 5×, the *measured*
remaining gap is attributed and the next reducible dispatch is named; **same-output correctness**
holds (the refactor must not change the rendered audio — a regression check, distinct from
acoustic parity); build validator + relevant tests green in CI. Secondary: optionally batch a2
continuing-note placement via `buildScatterAdd` (03 §4) to widen a2 headroom, but only if a2
ever threatens to stop hiding behind the forward.

## Phase 3 — The PDSL `stream` construct + migrate a1/a2/a3 onto it (behind a flag)

**Entry:** Phase 2 gate green (the perf fix works as a Java change; now make it structural).
**Work:**
- Extend the grammar/AST (`PdslNode.Program`) with `stream` / `realtime` / `ahead … by …`
  declarations; resolve them in `PdslInterpreter` into a stream graph (03 §3).
- Generalize `PatternRenderStream` into the reusable stream **runtime** (rings, producer threads
  / cooperative scheduler, back-pressure, content-keyed render-once, epoch-based live-swap —
  R2/R3/R6), driven through the single Metal command runner (R1).
- Re-express a1/a2/a3 as `patterns` / `rendered = render_ahead(patterns)` / `out =
  mixdown_master_wet(rendered, …)`; cut the hand-built `createPdsl` wiring over to the construct.
**Exit gate:** the pipeline runs entirely on the stream construct; G3 (2-min all-channel
non-silent real-time) passes; the defining invariant holds *by construction* (the runtime, not a
hand-check, guarantees a2 never blocks a3); parity holds; legacy `PatternRenderStream` wiring
retired only after parity is signed off.

## Phase 4 — Stereo + effects + automation (the proving configuration)

**Entry:** Phase 3 gate green.
**Work:**
- True stereo as the sink shape (per-channel pan in `out`, one forward — R9); efx on; per-buffer
  automation verified audibly adequate (R7) or upgraded to a finer automation stream if parity
  (G8) demands.
**Exit gate:** the 2-minute proving run passes with **efx ON and stereo ON** (G4 — L≠R asserted).
No acoustic-parity sign-off is required here (owner Q1); the design review only confirms the
wet/reverb/stereo paths still *exist* so future parity is not precluded.

## Phase 5 — 5× + consistency (close out the acceptance suite)

**Entry:** Phase 4 gate green.
**Work:**
- Kernel pre-warm for the (few, enumerable) stream kernel shapes in setup, eliminating the
  first-encounter compile spike (R4).
- Run the full acceptance suite on the pinned dense scene through CI.
**Exit gate:** **G1–G7 all green** (07/00; G8 parity is demoted, owner Q1): render-once,
a3-clean, 2-min all-channel non-silent, efx+stereo on, **≤ 0.2 end-to-end at the production
buffer (4096 preferred, 8192 acceptable)**, consistent across ≥3 runs (no per-tick outlier over
budget after warmup), determinism. Done = this gate, in CI, on the exact commit.

## Cross-phase rules

- **Substantial change is expected; flags de-risk, they do not preserve (owner Q2).** We are
  replacing a system. The flag exists so we can A/B the new path against the old during
  development — **not** so we tiptoe around the old structure. If doing the real work means
  significantly rewriting `PatternRenderStream` / `createPdsl` / the per-note assembly, do it. The
  named failure mode to avoid here is *timidity*: declining the necessary change to protect
  backwards compatibility. Backwards compatibility is never a reason to not do the work.
- **Acoustic parity is NOT a per-phase gate (owner Q1).** Phases 2–3 carry a *same-output
  correctness* check (the refactor must not change what the existing path produces — a
  regression check, cheap and legitimate), but perceptual parity with the *released* sound is a
  separate effort; do not let it gate progress, and do not rewrite docs to claim it.
- **No base-branch test edits, no CI-gate weakening, no forced-driver/forced-flag "passes"**
  (07/T8). A phase gate that "passes" only under a diagnostic config has not passed.
- **The ledger (01) and these docs update in the same commit as the code** they describe.
- If any phase's measurement contradicts a premise here, **stop and update the plan** — do not
  push the premise through. The plan is falsifiable too.
