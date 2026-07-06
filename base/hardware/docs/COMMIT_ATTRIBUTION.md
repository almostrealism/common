# Metal Commit-Cause Attribution

## Table of Contents

- [Overview](#overview)
- [What Gets Measured](#what-gets-measured)
  - [Per-Runner Cause Counters](#per-runner-cause-counters)
  - [The Requester Distribution](#the-requester-distribution)
- [Taking a Measurement](#taking-a-measurement)
- [Interpreting the Results](#interpreting-the-results)
  - [Healthy vs. Collapsed Batching](#healthy-vs-collapsed-batching)
  - [Reading Requester Names](#reading-requester-names)
  - [Common Fingerprints](#common-fingerprints)
- [Worked Examples](#worked-examples)
- [Pitfalls](#pitfalls)

---

## Overview

On Metal, dispatches are *encoded* into an open command buffer and only run once that
buffer is *committed*. `MetalCommandRunner` batches encoded dispatches until either the
open buffer reaches its dispatch bound (`MAX_OPEN`) or something on the host waits for a
result (`MetalSemaphore.waitFor()`), which commits the buffer on demand. Sustained
throughput therefore depends on commits happening at the `MAX_OPEN` cadence rather than
being forced by host waits: a workload committing every few dispatches instead of every
few hundred has had its batching collapsed by synchronous waits.

The commit-cause attribution instrumentation answers the two questions that matter when
that happens:

1. **Why did each commit occur?** — partitioned by cause counters on the runner.
2. **Which operations forced the host-wait commits?** — recorded per requester in a
   `DistributionMetric`.

No configuration is required; the instrumentation is always on and has negligible cost.

## What Gets Measured

### Per-Runner Cause Counters

Each `MetalComputeContext` owns one `MetalCommandRunner` (obtained via
`MetalComputeContext.getCommandRunner()`), and each runner partitions its total commit
count by cause:

| Accessor | Meaning |
|---|---|
| `getCommitCount()` | Total command-buffer commits |
| `getHostCompleteCommitCount()` | Commits forced by a host-side wait (`complete()`, reached through `MetalSemaphore.waitFor()`) |
| `getMaxOpenCommitCount()` | Commits from the open buffer reaching its dispatch bound — the expected steady-state cadence |
| `getBridgeCommitCount()` | Commits forced so a dispatch that bridges a *foreign* dependency starts a fresh buffer (see below) |
| `getDestroyCommitCount()` | Commits performed while tearing the runner down |

The cause counters always sum to the total. All are written on the runner's
executor thread and safe to read from any thread.

**Bridge commits.** A dispatch whose `dependsOn` is a foreign `Semaphore` (produced by
another backend, another Metal context, or a composite latch) is ordered by encoding a
GPU wait on a fresh per-bridge `MTLSharedEvent`, signaled from the host when the foreign
work completes (`enableHostSignaledBridges`, default on). The submitting thread does not
block, but if the open buffer already contains work it is committed first — a composite
completion over this runner's own semaphores resolves by completing their whole buffer,
so a buffer containing both those dispatches and the bridge wait could never complete.
Each such fresh-buffer commit increments `getBridgeCommitCount()`. A workload whose
bridge commits approach its bridged-dispatch count is fragmenting buffers around bridges;
grouping same-context members so bridges land at buffer starts restores batching. A zero
bridge count on a workload with cross-context dependencies means those dependencies never
reach `submit` as dependencies at all — typically because non-submittable members in the
composite force per-member waits that chop the chain first (see the composite
fingerprint below).

To obtain the runner for the shared Metal context:

```java
MetalComputeContext metal = Hardware.getLocalHardware()
        .getComputeContexts(false, true, ComputeRequirement.MTL).stream()
        .filter(MetalComputeContext.class::isInstance)
        .map(MetalComputeContext.class::cast)
        .findFirst().orElse(null);

MetalCommandRunner runner = metal.getCommandRunner();
```

### The Requester Distribution

`MetalCommandRunner.hostCompleteRequesters` (metric name `mtlHostCompleteRequesters`) is
a `DistributionMetric` recording every **commit-forcing** host wait, keyed by the display
name of the operation whose completion was waited. Waits that force no commit (the
buffer was already committed, or already completed) are deliberately *not* recorded —
they cost nothing attributable, so the distribution contains exactly the events that
break batching.

The requester identity travels with the completion handle: `MetalCommandRunner.submit`
receives the dispatching operation's `OperationMetadata` and creates the dispatch's
`MetalSemaphore` carrying it, so `waitFor()` can attribute the wait no matter who
performs it or on which thread. Kernel dispatches are labelled with their compiled
kernel name (from `MetalOperator.getMetadata()`); blit copies queued by
`MetalComputeContext.copy` are labelled `mtlBlitCopy`.

The distribution is **static** — one histogram across all runners and the whole JVM —
so measurements over a window must snapshot and diff (see below).

## Taking a Measurement

The pattern is snapshot → run the workload → diff:

```java
// Warm up first so compilation and first-use setup are excluded
runWorkloadOnce();

long baseTotal = runner.getCommitCount();
long baseHost = runner.getHostCompleteCommitCount();
long baseMaxOpen = runner.getMaxOpenCommitCount();
Map<String, Integer> baseCounts =
        new HashMap<>(MetalCommandRunner.hostCompleteRequesters.getCounts());

runWorkload();   // the window being measured

long commits = runner.getCommitCount() - baseTotal;
long host = runner.getHostCompleteCommitCount() - baseHost;
long maxOpen = runner.getMaxOpenCommitCount() - baseMaxOpen;

MetalCommandRunner.hostCompleteRequesters.getCounts().entrySet().stream()
        .map(e -> Map.entry(e.getKey(),
                e.getValue() - baseCounts.getOrDefault(e.getKey(), 0)))
        .filter(e -> e.getValue() > 0)
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .forEach(e -> log("requesterWaits=" + e.getValue() +
                " requester=" + e.getKey()));
```

Normalize by the workload's natural unit (training step, audio tick, frame) —
`commits / steps` is the headline number, and the host/maxOpen split tells you whether
those commits are forced or the healthy cadence.

## Interpreting the Results

### Healthy vs. Collapsed Batching

- **Healthy sustained workload:** commits are dominated by `getMaxOpenCommitCount()`,
  plus a small number of host-wait commits at genuine boundaries (the end of a
  `run()`/`evaluate()` at the top of the call stack).
- **Collapsed batching:** commits are dominated by `getHostCompleteCommitCount()`. The
  requester distribution then names the operations whose completions were being waited.

### Reading Requester Names

A wait is attributed to the operation whose semaphore was waited — the *waited*
dispatch, not the caller that performed the wait. Two consequences:

- Kernel names (e.g. `f_packedCollectionEnumerate_412`) identify the compiled operation
  whose completion someone required synchronously. Finding the *caller* is the follow-up
  step (a debugger breakpoint or stack capture in `MetalSemaphore.waitFor()` filtered by
  that requester).
- `mtlBlitCopy` means the waited semaphore belonged to a blit dispatch. Because copy-out
  blits are chained *after* kernels, a boundary that waits "for the operation" often
  actually waits the trailing copy-out blit — so a large `mtlBlitCopy` share can reflect
  either synchronous copy machinery or ordinary boundary waits whose chain tail is a
  copy. Distinguish them by whether the blit waits scale with the number of copies or
  with the number of boundaries.

### Common Fingerprints

- **Every commit host-forced, `maxOpenCommits == 0`:** no batching is surviving at all;
  every unit of work ends in a synchronous wait.
- **Each of N distinct kernels waited exactly once per step:** per-member boundary waits
  inside a composite operation — some layer of the composition is completing its members
  synchronously instead of chaining their semaphores.
- **One requester dominating:** a single synchronous consumer (often a cross-context
  read: another backend reading Metal-produced memory must wait for it, and the wait
  commits).

## Worked Examples

- `SemaphoreChainBatchingTest` (engine/utils) — unit-level: verifies chained dispatches
  share a command buffer, that a commit-forcing wait increments the host-complete count
  and records its requester, and that `CompletionConsumer` delivery issues a dispatch
  with no commit.
- `CommitCauseMeasurementTest` (engine/utils) — workload-level: runs a small dense model
  for 20 training steps and logs the full breakdown and top requesters, under both
  copy-based and assignment-based layer recording. Use it as the template for measuring
  any other workload.

## Pitfalls

- **Warm up before snapshotting.** Compilation, first allocations, and instruction-set
  setup produce one-off commits that pollute a cold-start window.
- **The distribution is JVM-global.** Always diff against a snapshot; never read raw
  counts as a window measurement. Concurrent Metal work from other threads lands in the
  same histogram.
- **Counters are per runner.** A process with multiple Metal contexts (multiple data
  contexts) has multiple runners; measure the one your workload dispatches to, or sum
  across them.
- **Only commit-forcing waits are attributed.** The absence of a requester in the
  histogram does not mean it was never waited — only that its waits never forced a
  commit. `getCommitCount()` deltas remain the ground truth for batching behavior.
- **For what happens *inside* a kernel**, this tool is the wrong instrument — use the
  operation profile (`OperationProfile` written to `<module>/results/*.xml`) and its
  analysis tooling instead. Commit attribution tells you about dispatch and wait
  structure, not kernel content or timing.
