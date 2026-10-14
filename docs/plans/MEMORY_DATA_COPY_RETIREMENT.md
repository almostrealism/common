# Why `MemoryDataCopy` was made faster instead of removed — and what removing it actually takes

> **Context.** On `qa/performance-20261013-132831` (PR #652), `MemoryDataCopy.get()` was given a
> `Submittable` runner so that a copy inside an `OperationList` chains on the device instead of
> forcing a host wait. `MemoryDataCopy` is `@Deprecated`, the project's recommendation everywhere
> is `Assignment`, and `SEMAPHORE_TECH_DEBT.md` §4.4 is titled *"Copies chain — by retiring
> `MemoryDataCopy`, not by improving it"*. The branch did the opposite of that heading. The owner
> asked for the full set of inputs to that choice: what was evaluated, what trade-offs were
> weighed, and how the removal was scoped. This document answers that, including the parts of
> the answer that do not reflect well on how the decision was made, and replaces the decision
> with one grounded in evidence gathered on 2026-10-13.

---

## 1. Short answer

The choice was **not** the product of a scoping exercise, and it should not be defended as
one.

- **The only input to "can we remove it?" was a status line.** The session that wrote the
  runner read the progress section of `SEMAPHORE_TECH_DEBT.md` ("The 4.4a flag flip was landed
  and then reverted ... The flip is blocked on root-causing that divergence") and the matching
  §10.1 of `ASSIGNMENT_COPY_MIGRATION.md`, and accepted both as current. It did not re-test the
  flip, look for a fix, or check when those lines were written.
- **That blocker had already been fixed.** The divergence was root-caused and fixed on master on
  2026-07-06 by `dd3facb92` ("Subdivide operation lists at read-after-write hazard boundaries").
  That commit names it as "the mechanism behind the gradient-descent divergence that has kept
  assignment-based layer recording (and with it `enableAssignmentCopy`) disabled". Nobody tried
  the flip again afterward, and the two plan documents were never updated, so they kept
  reporting a blocker that had not existed for three months.
- **The inputs that *were* evaluated were all performance measurements.** They were commit
  counts per training step (`CommitCauseMeasurementTest`, 10 → 4) and wall-clock times for four
  training tests. Those numbers show that the interim runner removes the host waits. They say
  nothing about whether removal was feasible, because removal was never measured.
- **The costs of keeping the class were not weighed at all.** The branch added about 220 lines,
  two nested classes, a `fixedOperands` flag, and a `Hardware` API overload (the one PR review
  rejected) to a deprecated class. All of it is code whose purpose is to stop being needed.

A re-run of the flip on 2026-10-13 (§4) shows that the original divergence no longer
reproduces. The remaining blockers are concrete and small. Retiring `MemoryDataCopy` is the
right next step; §5 is the work-list.

## 2. How the choice was actually made (reconstruction)

This is reconstructed from the branch's commit messages (`6486090a8`, `ae35b0b7b`), the interim
note it added to `SEMAPHORE_TECH_DEBT.md` §4.4, and the session memories on the branch. It
describes what happened, not what should have happened.

| Step | What the session did | What it should have done |
|---|---|---|
| Problem | Measured that layer input/output records (`LayerFeatures.into` → `MemoryDataCopy`) cost up to two commit-and-wait round trips per copy on Metal: `CommitCauseMeasurementTest` showed 10 host-wait commits per step under copy recording vs 4 under assignment recording. | Same. This measurement was sound. |
| Framing | The 10-vs-4 comparison already showed that **assignment recording is the fix**: the same test with `enableMemoryDataCopy=false` reaches 4. The session read it instead as "copy recording needs to become as fast as assignment recording". | Ask why assignment recording was not the default, and verify the answer. |
| Blocker check | Took "4.4a still blocked on the assignment-recording divergence (`AssignmentIsolationDiagTest`)" from the plan documents. `AssignmentIsolationDiagTest` asserts nothing (it only logs), so nothing in CI would have shown that it had started producing correct values. | Run the tests that diverged originally (`GradientDescentTests.linear2/linear4`) with the flag flipped. That is one test run of about five minutes. Also run `git log -S` on the flag and on `OperationList` for later fixes. |
| Scoping | None. No census of call sites, no estimate of migration size, no list of remaining blockers. | §5 of this document. |
| Decision | "Interim measure" that leaves 4.4a–c "unaffected". | Flip, or at least record why not with evidence. |
| Follow-on | Two later review passes on the branch extended the interim design (`fixedOperands`, `Hardware.getComputeContext(Memory, boolean)`) without revisiting the premise. | Re-check the premise when the design keeps growing. |

### Trade-offs that were implicitly accepted

The session never wrote these down. They are listed here so they can be judged.

1. **Smaller diff now, larger debt later.** The runner improves only the deprecated path. When
   the class is removed, all of it (`Runner`, `Delivery`, `fixedOperands`,
   `MemoryDataCopyChainingTest`) is deleted unused.
2. **Two copy mechanisms with diverging semantics.** `Assignment.Runner` and
   `MemoryDataCopy.Runner` both chain through `ComputeContext.copy`, but each has its own way of
   resolving operands. Every future ordering fix now has to be made twice.
3. **Optimizer opacity is preserved.** `MemoryDataCopy`'s producer tree cannot be seen by
   optimization strategies, whereas `Assignment` exposes it (`ASSIGNMENT_COPY_MIGRATION.md` §1,
   and the `ExpansionWidthTargetOptimization` comment in `Hardware`). Speeding up the opaque copy
   weakens the incentive to remove it.
4. **API creep outside the class.** The `preferDevice` overload of `Hardware.getComputeContext`
   existed only because `MemoryDataCopy` needed it. Review rejected it. The selection now lives
   in `DataContext.getComputeContext(Memory)` (§6), which is useful on its own.

What the interim change did buy was real but temporary: 10 → 4 host-wait commits per training
step on Metal, and the measured times recorded in `SEMAPHORE_TECH_DEBT.md` §4.4. **The flip
reaches the same 4 commits per step**, because that was the comparison in the measurement.

## 3. What "remove `MemoryDataCopy`" consists of: census

This is a census of production (`src/main`) paths that construct a `MemoryDataCopy` at run
time. It was taken on 2026-10-13 by searching the tree.

### 3.1 Flag-selected paths (both default to `MemoryDataCopy`)

| Flag | Default | Selects |
|---|---|---|
| `DefaultCellularLayer.enableMemoryDataCopy` | `true` (mutable static) | Layer input/output recording in `LayerFeatures.into(...)`: the copy branch when `copy == true` and the input count is ≤ 1 |
| `MemoryDataFeatures.enableAssignmentCopy` | `false` (`AR_HARDWARE_ASSIGNMENT_COPY`) | The `copy(...)` helpers in `MemoryDataFeatures` and the `CodeFeatures` override |

`into(...)` callers that reach the copy branch:

- `DefaultCellularLayer.init` — input record ("layer (Input Record)").
- `DefaultCellularLayer.output` — output record.
- `LayerRoutingFeatures.compose` — the auxiliary input record.

The other 11 `into(...)` call sites pass `copy=false` and already use `Assignment`.
`CompiledModel`'s forward and backward output captures are already `Assignment`s.

Callers of the `copy(...)` helpers:

- `WaveOutput.export` — the `Supplier` form; becomes a member of export lists in
  `CellFeatures` and `GridSequencer`.
- `DefaultChannelSectionFactory` `Section` — two `Supplier` form calls, the input and output
  members of the section's `OperationList`.
- `TestFeatures` — kernel-test helper in `engine/utils` `src/main`.
- `LayerFeatures.into` — the layer recording path above.

### 3.2 Direct constructions

| Site | Use | Replacement |
|---|---|---|
| `PackedCollection(PackedCollection)` copy constructor | standalone, immediate `get().run()` | `setFrom`/`copyFrom` (resolved `MemoryData` on both sides, so no graph is involved) |
| `FilterEnvelopeProcessor.process` (×2) | standalone, immediate | `copyFrom` / `ComputeContext.copy` |
| `CollectionProvider.into(Object)` | evaluable that copies into a destination | an `Assignment` from the provider into the destination (the provider-to-provider `Assignment.Runner` case) |
| `MemoryDataCopy.generate` | rebuild during optimization | removed with the class |

The total is 3 flag-selected recording sites, 4 helper callers, and 4 direct sites, with no
external modules involved.

## 4. The experiment that should have been run (2026-10-13)

Configuration: the `enableMemoryDataCopy` default set to `false` (a local edit, reverted
afterward), plus `-DAR_HARDWARE_ASSIGNMENT_COPY=enabled`. The host ran the JNI, MTL and CL
contexts, with JNI sharing `MetalMemoryProvider` memory. Run `547170fc`:

| Class | Result |
|---|---|
| `GradientDescentTests` (`linear1`–`linear5`, including the `linear2`/`linear4` that diverged in July) | **5/5 pass** |
| `NormTests` (`normModel` included; the July `normModel` failure is fixed) | 23 pass, 5 skipped by depth or long-test settings, **1 error** (`normLayerTrainable`, below) |
| `TrainModelTest` (`trainSmallest`, `trainVerySmall`, `convPool`, `pool`) | 4 pass, 5 skipped by depth or long-test settings |
| `BackPropagationTests` | 5 pass, 1 skipped |
| `OperationListSubdivisionTest` | 1 pass |

The one failure is not numeric:

```
NormTests.normLayerTrainable:
IllegalArgumentException: Shape mismatch in 'norm layer (Input Record)': (12, 10) does not match (120)
  at LayerFeatures.into
```

The copy branch of `into(...)` accepts any two shapes with the same total size. The
`Assignment` branch requires `equalsIgnoreAxis`. Layers whose recorded input has a different
shape from their declared input shape, but the same total size, therefore fail at build time
under the flip. This is a missing reshape, not a correctness hazard in `Assignment`.

**Not yet measured:**

- the full CI matrix;
- the native and CL drivers on their own;
- the `engine/ml` groups;
- the audio and compose paths that use the `Supplier` helpers;
- `SyntheticDenseTrainingTest` (it passed under the flip in the July experiment, which predates
  `dd3facb92`).

## 5. Work-list to retire `MemoryDataCopy`

Ordered so that each step can land and be verified on its own.

1. **`LayerFeatures.into` shape tolerance.** In the `Assignment` branch, when
   `shape.getTotalSize() == outShape.getTotalSize()` but the shapes differ, reshape `in` to the
   output's shape before the `traverse(axis, ...)` assignment instead of throwing. This is what
   the copy branch has always done implicitly. Verify with `NormTests.normLayerTrainable` under
   the flip.
2. **Flip layer recording.** Set `enableMemoryDataCopy` to default `false`, then remove the flag
   and the `copy` parameter's copy branch once CI is green across the full matrix (all drivers,
   the mac jobs, the `engine/ml` groups). Gate: `GradientDescentTests`, `NormTests`,
   `TrainModelTest`, `SyntheticDenseTrainingTest`, `CommitCauseMeasurementTest` (expect 4
   host-wait commits per step).
3. **Fix the `Supplier` helper's `Assignment` form before flipping `enableAssignmentCopy`.**
   `MemoryDataFeatures.copy(String, Supplier, Supplier, int)` builds `new Assignment(length, ...)`
   over non-`Countable` lambda evaluables. That unrolls `length` statements into a single-thread
   kernel, and the constructor throws once `length` exceeds `ScopeSettings.maxStatements`. This is
   the same defect fixed for aggregation copies on `feature/argument-prep-assignment`. Its two
   production users (`WaveOutput.export`, `DefaultChannelSectionFactory`) resolve both operands
   to `MemoryData` at run time, so the right form is a provider-to-provider `Assignment`, which
   resolves to the chained `Assignment.Runner` → `ComputeContext.copy`. Then flip
   `enableAssignmentCopy` and remove the flag. Gate: audio and compose parity tests, and the
   `PdslHotPathBreakdownTest` commit counters.
4. **Migrate the direct sites in §3.2.** These are mechanical changes, each with an existing
   test that exercises it.
5. **Delete `MemoryDataCopy`** with its `Runner`, `Delivery`, `fixedOperands`, `generate`, and
   the javadoc that references it. Retarget the useful parts of `MemoryDataCopyChainingTest`
   (ordering, a single commit per list on Metal, profile attribution) to `Assignment` copies,
   and delete the rest.

Steps 1 and 2 are a small change. The cost lies almost entirely in the CI matrix run, which is
the same cost the interim runner was implicitly avoiding.

## 6. What stays useful from PR #652 after removal

- **`DataContext.getComputeContext(Memory)`.** The context that sets up memory sharing (the
  native context allocating from a device's host-accessible provider) names the device context
  for that memory, and `Hardware.getComputeContext(Memory)` asks the managing `DataContext`
  rather than taking whichever context is listed first. `Assignment.Runner.contextFor` uses the
  same lookup, so provider-to-provider `Assignment` copies into shared memory now queue on the
  device whichever order the drivers are configured in. This replaces the rejected
  `preferDevice` overload.
- **The decision not to delegate inside `ComputeContext.copy`.** A tempting variant had the CPU
  context's `copy` hand shared-memory copies to the device. It was tried and reverted.
  `AcceleratedOperation.apply` chains each kernel on its own context's copy-in completions, so a
  host kernel's argument copies would have become device round trips. Worse, they would have
  been queued behind device work gated on a host-signalled bridge that was itself waiting for
  that host work. `MemoryDataCopyChainingTest.fixedOperandCopyFollowsDependency` failed 3 out of
  3 runs with a Metal GPU timeout. The reasoning is recorded on
  `AbstractComputeContext.copy`.
- **`OperationListRunner` per-member timing attribution.** This is independent of copies.

## 7. Process lessons

- A plan document's "blocked on X" is a claim with a date, not a fact. Before building around a
  blocker, re-run the reproduction (`git log -S` on the flag, `git log` on the area since the
  note was written).
- A diagnostic test that only logs (`AssignmentIsolationDiagTest`) cannot tell anyone that a
  blocker has cleared. A blocker should be represented by a test that asserts.
- When a measurement already compares the bad path with the intended path (10 vs 4 commits),
  the intended path is the fix. Optimizing the bad path to match it needs an explicit reason,
  backed by evidence, for why the intended path cannot be taken.
