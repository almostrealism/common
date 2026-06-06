# Metal Sustained Dispatch — Resolved

> **Done record.** The Metal *dispatch ceiling* is fixed and merged. This file
> consolidates the prior implementation plan and the "what this unblocks for the
> audio redesign" framing into one record. Read it so you target the right
> backend and do not re-solve a problem that is now solved.

## 1. Status — FIXED and merged

Sustained Metal rendering used to wedge at **~2300–2560 cumulative kernel
dispatches**: the host parked forever in `MTL.waitUntilCompleted` on a committed
command buffer that never reported completion (process burning 100% CPU in the
driver). The stall buffer varied (1792 / 2304 / 2560), confirming a *cumulative*
effect of submission **count**, not a deterministic per-buffer bug. Ruled out
with evidence: not a Java leak, not GC, not tracked GPU memory (flat ~625 MB),
not RSS (flat ~1.46 GB). Root cause: `MetalCommandRunner` spanned one
Objective-C autorelease pool across its separate encode and commit/await
executor tasks, so the **second** command buffer to commit wedged forever (a
single buffer always completed; two were the minimal trigger).

The fix: each executor task runs in its own autorelease pool and command buffers
are explicitly retained across tasks —
`base/hardware/.../metal/MetalCommandRunner.java` (`runInPool`),
`base/hardware/src/main/cpp/MTL.cpp` (`commandBuffer()` retain /
`releaseCommandBuffer`), and `MTLCommandBuffer.release()`. There is **one open
command buffer per `ComputeContext`**, committed only at boundaries.
Regression-guarded by
`engine/utils/.../collect/computations/test/OperationDispatchBatchingTests`
(`independentDispatchesComplete`, 60s fail-fast; `manySmallDispatches`).
**Verified this session: 3000+ sustained batched Metal dispatches complete
without wedging.**

## 2. Durable architectural invariants

These outlast the bug fix and govern any future Metal dispatch work.

**The platform is a compiler, not a player piano.** The statements you add to an
`OperationList` and the `Producer` graphs you build are *source for a compiler*.
N statements like `X[a]=Y[a]+X[a]` typically compile to **one** kernel program
of N lines (one dispatch), not N dispatches. Dispatch / kernel / command-buffer
counts are **properties of the compiled program** — capture them with
`OperationProfileNode` and the `ar-profile-analyzer` MCP (`get_source`) before
asserting any count. Never infer counts from how many operations a tick contains.

**At most ONE active command buffer per `ComputeContext`.** A second open buffer
on one context is a bug. Within one buffer, Metal's same-buffer **hazard
tracking** already orders every read-after-write among encoded dispatches (and
runs independent ones concurrently), so there is nothing to "synchronize" across
buffers within a context. The buffer commits only at a genuine boundary:

1. an **explicit host wait** — `run()` / `evaluate()` (or any host read-back), or
2. a hand-off to a **different `ComputeContext`** (a real cross-context boundary).

Everything else keeps encoding into the one open buffer. A long chain of
operations the compiler could not fuse should cost **~one commit at the end**,
not N. (A Computation may legitimately *not* fuse when its operations have
different counts / global work sizes — no single `global_id` range — so the
compiler emits one dispatch per differently-counted operation. Those still
share the one buffer.)

**WITHIN-context batching vs CROSS-context chaining — do not conflate them.**
- *Within* one Metal context: batch into the one open buffer, commit at
  boundaries. No per-dispatch `dependsOn` / event chaining is needed or wanted —
  hazard tracking orders dependents. Target: commits ≈ number of boundaries.
- *Across* `ComputeContext`s: one host `waitFor` or event hand-off at the
  boundary. This is the only place `dependsOn` / `cl_event` / `MTLSharedEvent`
  chaining earns its keep. It is not a within-context tool.

## 3. Failure-mode lessons worth not repeating

Two prior performance follow-up attempts were tried and reverted. Their lessons:

- **Do not block the bounded executor pool on the runner.** Attempt 1 was a
  submission-ordinal turn gate that blocked a pool thread on the runner →
  thread-pool starvation (threads parked in `completeOnExecutor →
  waitUntilCompleted`). *Nothing on the shared bounded pool may block on the
  runner.*
- **Do not manufacture a second command buffer to "order" same-context
  dispatches.** Attempt 2 committed one buffer and had another
  `encodeWaitForEvent` it for a *same-context* dependency, then invented a
  cross-buffer failure mode to explain the resulting hang. The fix is not to make
  two buffers work — it is to never create the second buffer (see the one-buffer
  invariant). Its dispatch/buffer counting was also done on the wrong model,
  without ever reading compiled output — see invariant 2.
- **Do not carve exceptions into the completion contract.** Every provider always
  returns a real `Semaphore` (already-completed when synchronous); `dependsOn` is
  always honored; one completion concept per operation; the runner owns the
  batching decision internally; native handles stay typed. Litmus test: if
  removing a line would let an operation opt out of waiting on a semaphore it
  depends on, that line is the bug.

## 4. What this did NOT do

The dispatch-ceiling fix **unblocked Metal as a sustained backend; it did not by
itself make rendering real-time.** It operates at the command-buffer / provider
layer. The audio bottleneck lives one layer up.

- **Real-time still REQUIRES hybrid JNI+Metal routing.** Metal alone cannot
  compile the mixdown loop because of the **31-buffer-argument limit**. That
  limit is unaddressed by this work.
- **The a3 DSP / mixdown per-frame loop is now the bottleneck** — that is a
  per-frame *loop*, not a dispatch problem, and is untouched here.
- It does **not** remove `.evaluate()` round-trips at the top of the call stack.
  An `evaluate()` boundary is itself a commit boundary; collapsing N evaluates
  per tick into one batched `CollectionProducer` is separate graph-batching work.

Removing the ceiling makes Metal viable as a sustained backend for a2/a3 — you no
longer have to design around "Metal stalls past N dispatches" or default to
`-DAR_HARDWARE_DRIVER=native`. It does not move you under the per-tick budget;
only graph batching and resolving the mixdown routing do that.

## See also

- `STATE_OF_PLAY.md` — current real-time rendering state,
  per-tick cost breakdown, and backend routing (the state-of-play / known-issues
  reference for this area).
- `docs/internals/backend-compilation-and-dispatch.md` — one-buffer-per-context
  model and commit-at-boundaries.
- `OperationDispatchBatchingTests` (engine/utils) — regression harness.
- `RealtimeContinuousRenderer` (studio/compose) — sustained-dispatch harness.
