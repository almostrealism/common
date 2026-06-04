# Metal Dispatch Ceiling Lifted — What This Unblocks for the Audio Redesign

> **Audience.** The agent (re)starting the AudioScene a1/a2/a3 redesign. Read this
> before resuming so you target the right backend and do not re-solve a problem
> that is now solved elsewhere.
>
> **TL;DR.** The branch `feature/metal-sustained-dispatch` removes the Metal
> *dispatch ceiling* and the command-buffer leak/wedge that made sustained GPU
> rendering impossible. It does **not** batch your notes for you — that is still
> Phase 3 graph batching, your job. The two efforts are **complementary, not
> substitutive**: Phase 3 reduces *how many* dispatches a tick emits; this branch
> removes the *cumulative ceiling* on dispatches and collapses per-dispatch
> command-buffer commits to once-per-boundary. Net effect: **Phase 3 batched
> rendering can now target Metal/GPU as a sustained backend instead of being
> confined to the native-CPU workaround.**

---

## 1. What was blocking GPU audio rendering (before this branch)

Two distinct Metal defects, both now fixed on `feature/metal-sustained-dispatch`:

1. **The dispatch ceiling.** Real-time `AudioScene` rendering on Metal stalled at
   **~2300–2560 cumulative kernel dispatches**: the host parked forever in
   `MTL.waitUntilCompleted` on a committed command buffer that never reported
   completion. This is why the working real-time path on master forces the
   native backend (`-DAR_HARDWARE_DRIVER=native`) — see
   [`REALTIME_RENDERING_STATE_OF_PLAY.md`](REALTIME_RENDERING_STATE_OF_PLAY.md)
   and the melodic-channel result (native CPU sustains ~0.85–1.74× real-time;
   Metal stalled at ~2560 dispatches "despite correct autorelease-pool hygiene").

2. **The autorelease-pool wedge.** `MetalCommandRunner` pushed one Objective-C
   autorelease pool per *open* command buffer and popped it at commit, so the
   pool spanned the runner's separate encode and commit/await executor tasks.
   The **second** command buffer to commit wedged forever in
   `MTL.waitUntilCompleted`. A single buffer always completed; two were the
   minimal trigger. (Fix: each executor task runs in its own pool; command
   buffers are explicitly retained across tasks. Regression-guarded by
   `OperationDispatchBatchingTests#independentDispatchesComplete`, 60s fail-fast.)

The combined consequence was simple: **you could not keep a Metal context alive
across the thousands of dispatches a multi-minute render emits.** Even a correct
Phase 3 batched renderer that still emitted, say, a few dispatches per tick would
march into the ceiling over a long arrangement.

## 2. What the branch changes

- **One open command buffer per `ComputeContext`, committed at boundaries.**
  Dispatches the compiler emits for a context encode into a single open buffer;
  it commits only at a genuine boundary (a host `run()`/`evaluate()` wait or a
  cross-context hand-off), not once per dispatch. Same-buffer hazard tracking
  orders dependents — no per-dispatch event chaining. (See
  `docs/plans/METAL_SUSTAINED_DISPATCH.md` and
  `docs/internals/backend-compilation-and-dispatch.md`.)
- **The ceiling is gone.** Sustained Metal rendering no longer wedges at the
  ~2300–2560 mark; the autorelease fix is the root cause removed.
- **Per-dispatch commit overhead is amortized.** A group of independent
  dispatches that the compiler could *not* fuse now shares one commit at the
  boundary instead of one commit each.

## 3. What it does NOT change — read this twice

This branch operates at the **command-buffer / provider** layer. The audio
bottleneck lives one layer up, at the **`.evaluate()` / JNI** layer:

- Per `REALTIME_RENDERING_STATE_OF_PLAY.md` §3 and `AUDIO_SCENE_REDESIGN.md` §4,
  **~89% of per-tick cost is a2 per-note rendering, and ~99.4% of *that* is JNI
  dispatch overhead** — N separate top-of-stack `note.getProducer(-1).evaluate()`
  calls in `PatternFeatures.renderPerNote`.
- Command-buffer batching reduces *commits among kernels already dispatched
  within one run*. It does **not** remove `.evaluate()` round-trips at the top of
  the call stack. **Your N-evaluates-per-tick problem is untouched by this
  branch.** The fix for it is unchanged: **Phase 3 graph-level batching** —
  collapse N evaluates into one `CollectionProducer` (the benchmark's proven
  100–1500× win; see [`PATTERN_RENDERING_FLOOR.md`](PATTERN_RENDERING_FLOOR.md)).

Concretely: an `evaluate()` boundary is itself a commit boundary. As long as a2
renders one note per `evaluate()`, this branch cannot batch across those
boundaries — *by design*, because you defined each `evaluate()` as a boundary.

## 4. The complementary relationship (how the two efforts compose)

| Layer | Problem | Owned by |
|-------|---------|----------|
| a2 dispatch **count** per tick | N `evaluate()` JNI round-trips per tick | **Phase 3 graph batching** (audio redesign) |
| Cumulative dispatch **ceiling** + per-dispatch commit overhead | Metal wedges at ~2560 dispatches; one commit per dispatch | **`feature/metal-sustained-dispatch`** (done) |

1. **Enabling.** Once Phase 3 collapses a tick to ~one batched dispatch per
   pattern layer, you still run that for thousands of ticks across a render. The
   old ceiling would have killed sustained GPU playback regardless. **That
   ceiling is now removed, so Phase 3 can target Metal for sustained real-time
   rendering** instead of falling back to native CPU.
2. **Amortizing.** A Phase 3 batched graph still contains sub-kernels the
   compiler could not fuse (variable note scheduling, differently-counted ops —
   see [`VARIABLE_NOTE_SCHEDULING.md`](VARIABLE_NOTE_SCHEDULING.md) and
   [`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md)). Those now commit **once at the
   tick boundary** rather than once each — a smaller, second-order win on top of
   the graph batching.

## 5. What you can now assume when you resume

- **Metal/GPU is a viable sustained backend for a2/a3.** You no longer have to
  design around "Metal stalls past N dispatches" or default to
  `-DAR_HARDWARE_DRIVER=native`. Validate on the real arrangement, but the
  structural blocker is gone.
- **Still do Phase 3 first.** Removing the ceiling does not move you under the
  92.9 ms/tick budget — only graph batching does. Do not interpret "GPU works
  now" as "the dispatch problem is solved." It is solved *for the platform*; the
  *audio graph shape* is still yours to batch.
- **Keep the sentinel-counter discipline** from
  [`PRIOR_ATTEMPT_POSTMORTEM.md`](PRIOR_ATTEMPT_POSTMORTEM.md): `batchedDispatchCount`
  must advance and `fallbackCount` must stay 0, **measured at production density**.

## 6. Evidence / provenance

- Ceiling + native workaround: `REALTIME_RENDERING_STATE_OF_PLAY.md` §2–3;
  recalled findings on the ~2560-dispatch Metal stall and the native-backend
  sustained-render result.
- Autorelease wedge + fix: `base/hardware/.../metal/MetalCommandRunner.java`
  (`runInPool`), `MTL.cpp` (`commandBuffer()` retain + `releaseCommandBuffer`),
  `MTLCommandBuffer.release()`; regression test
  `engine/utils/.../OperationDispatchBatchingTests#independentDispatchesComplete`.
- One-buffer-per-context model + commit-at-boundaries: `docs/plans/METAL_SUSTAINED_DISPATCH.md`,
  `docs/internals/backend-compilation-and-dispatch.md`.
- Per-dispatch overhead microbenchmark (measured numbers):
  `OperationDispatchBatchingTests#manySmallDispatches` — see §7.

## 7. Measured per-dispatch overhead

`OperationDispatchBatchingTests` is the reference harness: an `OperationList` of
N tiny independent doublings (unfused — `OperationList(name, false)`), run under
the active backend, reporting **µs/dispatch**. Measured on this branch, M-series
box, `manySmallDispatches` (32 ops × 32 iterations = **1024 dispatches**):

| Backend | Wall time | µs/dispatch |
|---------|-----------|-------------|
| Native CPU (default routing for these `count=1` ops) | 196 ms | **191.9** |
| Metal (forced `-DAR_HARDWARE_DRIVER=mtl`) | 236 ms | **231.3** |

**Read these numbers honestly — what they do and do not show:**

- **They are a liveness/viability result, not a per-dispatch speedup.** On these
  trivially small (`[1,4]`) unfused ops there is no compute to amortize, so Metal
  is *expectedly slightly slower per-dispatch* than CPU. That is not the point and
  not a regression — the platform's router correctly prefers CPU for fixed
  single-count ops anyway.
- **The load-bearing evidence is that it completed at all.** 1024 Metal dispatches
  *plus* the aggregated multi-command-buffer regression case
  (`independentDispatchesComplete`) ran clean. Pre-branch, the **second** command
  buffer wedged forever in `MTL.waitUntilCompleted`, and the stream stalled near
  ~2560 dispatches. The capability — sustained Metal dispatch without wedging — is
  the deliverable this demonstrates.
- **This micro-harness does not isolate the commit-count win.** Per-dispatch host
  submission dominates here; the actual batching benefit (one commit per boundary
  instead of one per dispatch) shows up at audio scale, not on 32 tiny ops. Per
  `METAL_SUSTAINED_DISPATCH.md`, commit/dispatch counts are only meaningful when
  read from compiled output via the profile analyzer — do not infer them from this
  wall-clock number. To watch the ceiling removal directly, raise the iteration
  count past ~2560 (the test comment notes this is where pre-branch Metal stalled)
  and confirm it now completes.
