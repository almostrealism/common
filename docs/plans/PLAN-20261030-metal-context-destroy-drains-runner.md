---
status: proposed
created: 2026-10-30
closed:
tracker: [1f0095ae-3361-49ba-b1af-92f0d4f9ca6a]
---

# Metal context destroy drains its command runner

Plans tracker task **1f0095ae-3361-49ba-b1af-92f0d4f9ca6a** —
*"Metal: MetalComputeContext.destroy() never destroys its MetalCommandRunner
(runner thread outlives the context; queue released under a live runner)"* —
in the release currently under development.

## Motivation

As filed, `MetalComputeContext.destroy()` released `queue`/`fastQueue`, the
operator-map pipelines, and the copy kernels, but never called
`runner.destroy()`. The consequences the task describes are real and serious:

1. the runner's `ConfinedExecutor` thread (and its timeline `MTLSharedEvent`)
   outlives every created-then-destroyed context, so threads and native events
   leak per context;
2. in-flight work is not drained — an open or held command buffer is never
   committed, committed buffers are never waited for, and their completion
   callbacks never run, so a waiter on one of their semaphores can hang; and
3. the queue, the operator-map pipelines, and the copy kernels are released
   while buffers encoded against them may still be pending on the GPU.

`CudaComputeContext.destroy()` already calls `runner.destroy()`; Metal did not.
The task is explicitly **blocked by b3b032a7** (the extraction of a shared
dispatch core out of the per-backend runners) and instructs the implementer to
*first check whether b3b032a7 already fixed this on master after it lands*, and
only apply the fix if any of three criteria is still missing.

## Investigation — b3b032a7 has landed and fixed this

b3b032a7 (plan `docs/plans/PLAN-20261029-extract-shared-dispatch-runner-core.md`)
landed on master as commit `e8aa8039b` *"Build the Metal and CUDA runners on a
shared submission-stream core"*, merged via PR #781 (merge commit `8ac94394c`),
which is an ancestor of this branch. Its item 5 ("Teardown is inconsistent") and
its done-criterion "the runner is destroyed and drained on context destroy
(Metal)" cover this bug directly. Checking the three criteria the task names
against the current source:

1. **Does `MetalComputeContext.destroy()` destroy and drain the runner?**
   Yes. `MetalComputeContext.destroy()` now calls `runner.destroy()`.
   `MetalCommandRunner.destroy()` performs the full drain the task describes: it
   commits the open buffer on the executor thread, waits for the last committed
   buffer on the calling thread while the executor still accepts work, repeats
   the commit-and-wait until a round finds nothing committed, then destroys the
   executor; the executor's final task submits the held buffers, drains every
   committed buffer, and releases the timeline event. The wait is kept off the
   runner's own thread because a bridged buffer's foreign work may itself need
   this runner.

2. **Before the queue, pipelines and copy kernels are released?**
   Yes. `destroy()` runs `runner.destroy()` inside a `try`, and the release of
   the operator maps, `queue`/`fastQueue`, and the copy kernels and programs is
   moved into `releaseResources()`, called from the `finally`. So the runner is
   fully drained before anything it may still be using is released, and the
   release still runs even if the drain throws (the task's "failure during
   destroy" point — satisfied by the `try`/`finally`; the runner's own
   `destroy()` additionally uses `Destroyable.releaseAll` internally so a
   throwing drain cannot leak the remaining buffers or the shared event).

3. **Is there a test proving it?**
   Yes. `MetalComputeContextTeardownTest.destroyDrainsCommandRunner`
   (`engine/utils`) submits a dispatch that stays open in its command buffer,
   destroys a dedicated context, and asserts the dispatch's completion callback
   ran (`completed` becomes true — the "pending dispatch settles rather than
   hanging" behaviour), that the runner `isClosed()`, and that the destroyed
   context's runner refuses further work with an `IllegalStateException`. The
   test skips cleanly when no Metal context is available.

The remaining points the task raises are also addressed by the merged code:

- **Calling thread.** `MetalCommandRunner.destroy()` begins with
  `requireOffStreamThread()` and throws `IllegalStateException` if reached from
  the runner's own thread (a completion callback); `MetalDataContextLifecycleTest`
  already requires destroy to fail fast inside a compute-context scope.
- **`fastQueue`.** It has no runner and is released in `releaseResources()`
  after the drain; nothing encodes on it during the drain.
- **Javadoc.** `MetalComputeContext.destroy()` and `releaseResources()` now
  document that the runner is destroyed and drained first, matching the
  behaviour CUDA already had.

**Conclusion:** all three criteria the task uses as its close-as-covered test
hold, the fix matches the shape the task prescribes, and an equivalent test
exists. By the task's own decision rule the correct outcome is to **close the
task as covered**, citing commit `e8aa8039b` (PR #781) and
`MetalComputeContextTeardownTest`.

## Scope

No production code change is required — the fix is already on master. This
branch's substantive work is **verification and closure**:

- Re-run the Metal regression tests the task names on a Metal host to confirm
  they are green on this branch's tree: `MetalComputeContextTeardownTest`,
  `MetalCommandFailureTest`, `MetalDataContextLifecycleTest`,
  `MTLEventLifecycleTest`, and `CommitCauseMeasurementTest`.
- Set the task to a covered/closed state in the tracker, citing the commit and
  test above.

**Optional, reviewer's choice — small test hardening (in scope only if the
reviewer does not accept the existing test as the "equivalent one" the task
allows).** The task's Tests section suggests two specific behaviours. The
existing test fully covers the second (a pending dispatch settles) and covers
the first (runner thread gone) *by proxy* — it asserts `isClosed()` and that
the runner refuses work, which follow from `destroyExecutor()` having shut the
`ConfinedExecutor` thread down, but it does not count live threads. If a direct
regression guard is wanted, add to `MetalComputeContextTeardownTest`:
  - a test that counts live threads whose name matches the runner's
    `ConfinedExecutor` thread before and after several create/destroy cycles of
    a dedicated `MetalComputeContext`, asserting the count returns to baseline
    (no per-context thread leak); and
  - a test that leaves a *bridged* dispatch (one depending on a foreign
    `Semaphore`) pending at destroy and asserts its semaphore settles rather
    than hanging — exercising the drain path across `super.destroy()`'s
    `executor.shutdown()`, which the current test does not.

Anything beyond this — refactoring teardown, touching CUDA parity, or changing
the shared submission-stream core — is out of scope; raise it as a separate
tracker task.

## Approach

Files involved (all already in their final shape on master; listed so a reviewer
and the verification step know where to look):

- `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalComputeContext.java`
  — `destroy()` (runner destroyed first, release in `finally`) and
  `releaseResources()`.
- `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalCommandRunner.java`
  — `destroy()` / `drainOldestCommitted()` / `awaitLastCommittedOrDestroy()` /
  `destroyExecutor()` (the drain-on-destroy machinery).
- `base/hardware/src/main/java/org/almostrealism/hardware/ctx/AbstractComputeContext.java`
  — `destroy()` (`executor.shutdown()`), relevant to the ordering open question.
- `engine/utils/src/test/java/org/almostrealism/hardware/test/MetalComputeContextTeardownTest.java`
  — the existing proof, and the home of any optional added test.

If the reviewer selects the optional hardening, the new tests belong on
`MetalComputeContextTeardownTest` (same module, same skip-when-no-Metal guard as
the existing test), as Metal tests that return early on a host without Metal.
No new class and no new Maven module.

## Success criteria

A reviewer can confirm the task is done by checking, on this branch:

- `MetalComputeContext.destroy()` calls `runner.destroy()` before any queue,
  operator-map pipeline, or copy kernel is released, inside `try`/`finally` so
  the release still runs if the drain throws — and the javadoc says so.
- `MetalComputeContextTeardownTest.destroyDrainsCommandRunner` exists and, on a
  Metal host, passes: a pending dispatch's completion runs at destroy, the
  runner is closed, and it refuses further work.
- On a Metal host, the five named tests pass:
  `MetalComputeContextTeardownTest`, `MetalCommandFailureTest`,
  `MetalDataContextLifecycleTest`, `MTLEventLifecycleTest`,
  `CommitCauseMeasurementTest`.
- If the optional hardening is taken, the added thread-leak and
  bridged-pending-at-destroy tests pass on a Metal host and skip cleanly
  elsewhere.

## Dependencies

- **b3b032a7** (`e8aa8039b`, PR #781) — the shared submission-stream core
  extraction. The teardown behaviour this task depends on — Metal's runner
  destroyed and drained on context destroy — **landed and merged** in that
  commit (verified against the source on this branch), so this task is
  unblocked and, in substance, already satisfied by it. Note that
  `PLAN-20261029-extract-shared-dispatch-runner-core.md` still carries
  `status: in-progress` (that plan stages further phases beyond the teardown
  fix), so do not read "the whole extraction plan is complete" into this
  dependency — only that the piece this task needs is in.

## Open questions

1. **Accept the existing test as the "equivalent one," or add the hardening?**
   The task permits skipping new tests "unless b3b032a7 already added an
   equivalent one." `MetalComputeContextTeardownTest` is an equivalent one
   (pending dispatch settles; runner closed and refusing work). The only gaps
   versus the task's literal suggestions are a *thread-count* assertion and a
   *bridged* pending dispatch. The reviewer should decide whether those gaps
   warrant the optional tests above or whether the task closes as-is.
2. **`super.destroy()` before `runner.destroy()`.** The merged code calls
   `super.destroy()` (which does `executor.shutdown()` on the
   `AbstractComputeContext` executor) *before* `runner.destroy()`. The task
   flagged that if a bridged buffer's foreign work is driven on that executor,
   the runner would need to be destroyed *before* `super.destroy()`.
   `ThreadPoolExecutor.shutdown()` lets already-submitted tasks finish and only
   rejects *new* submissions, and the runner keeps its own `ConfinedExecutor`
   accepting work throughout the drain, so the chosen ordering is defensible;
   but the current test does not exercise a bridged dispatch across the
   shutdown. Confirming this (and, if desired, the optional bridged-pending test
   above) is the one correctness point a reviewer should sign off on before
   closing. If the bridged case is found to stall, the fix is to move
   `runner.destroy()` ahead of `super.destroy()` — a one-line reordering within
   the same method, still in scope.

## Estimated complexity

**Small.** The production fix is already merged; the work is verification on a
Metal host plus an optional, additive, no-production-code test hardening.
