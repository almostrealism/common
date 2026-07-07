# Plan — Argument preparation must respect the Semaphore chain

> **Intent.** When an `AcceleratedOperation` is dispatched with a `dependsOn` completion
> (`AcceleratedOperation.apply(output, args, dependsOn)`), every part of its execution must be
> ordered after that completion — including the evaluation of its producer arguments. Today only
> the kernel dispatch is ordered (`Semaphore.all` over argument-delivery completions plus
> `dependsOn`); the argument evaluations themselves are launched unchained at submit time from
> `ProcessDetailsFactory.construct()`. A hoisted argument evaluation (an isolated subtree, a
> `DynamicProducer` lambda, a `HardwareEvaluable.into` destination evaluation) can therefore read
> memory before the operation it depends on has completed — on Metal, before the writing
> dispatch's command buffer has even committed.

## 1. Why now

This surfaced while root-causing the assignment-based layer recording divergence (the
`enableAssignmentCopy` flip-blocker; see `ASSIGNMENT_COPY_MIGRATION.md`). That investigation
found two distinct correctness gaps:

- **(A) Fused-composite hazard** — when an all-computation `OperationList` fuses into a single
  compiled operation, an isolated subtree of a later member is evaluated at argument-preparation
  time, before the fused kernel executes — so it reads memory that an *earlier member of the same
  fused operation* writes, one run late. Deterministic one-pass lag; reproduced minimally by
  `AssignmentIsolationDiagTest.compositeVariants` (`variantAll` vs `variantProvider`), and the
  cause of the `NormTests.normModel` failures on `feature/assignment-copy-migration`. Fixing (A)
  requires subdividing the list at write→read boundaries (segments; no semaphore can order a
  kernel after itself) and is **not addressed by this plan** — it is the follow-on.
- **(B) Unchained argument preparation** — this plan. Independent of fusion: even with members
  compiled separately and chained via `Submittable.submit(pending)`, the *next* member's argument
  evaluations run at submit time, unordered against `pending`. The runner's host wait before
  non-submittable members (`OperationListRunner.run()`) masks this for `DestinationEvaluable`
  members, and near-synchronous native execution masks it on the CPU backend — but a submittable
  member whose hoisted argument reads a buffer written by a still-uncommitted Metal dispatch
  reads stale data.

Fixing (B) first was chosen deliberately (2026-07-06): it repairs machinery that is already load
bearing everywhere, before any new subdivision behavior is introduced.

## 2. Current mechanics (all verified on master)

- `AcceleratedOperation.apply(output, args, dependsOn)` → `getProcessDetails(output, args)` →
  `ProcessDetailsFactory.init(output, args).construct()`. `construct()` creates
  `StreamingEvaluable`s for unresolved arguments and calls `request(args)` immediately — no
  dependency is available to it.
- Delivery is already dependency-aware: arguments delivered via `CompletionConsumer` carry a
  completion `Semaphore`, recorded by `AcceleratedProcessDetails.result(index, result,
  completion)`; `apply` merges `getArgumentCompletions()` with `dependsOn` (`Semaphore.all`) and
  chains aggregation copy-ins, replacement copies, and the kernel on the merged `ready`. The gap
  is only that the evaluations are *launched* before `dependsOn` has fired.
- The three `StreamingEvaluable` implementations reached from `construct()`:
  - `EvaluableStreamingAdapter` (plain host evaluation on an executor),
  - `DestinationEvaluable` (nested `apply(destination, args)` of a compiled kernel; delivers the
    destination with the nested completion),
  - `HardwareEvaluable` (delegates to its kernel's streaming evaluable, or evaluates its
    short-circuit on the host).
  All three either are created with, or override `async(Executor)` to preserve, their own
  `request` implementations — so threading a dependency through `request` reaches the right code.

## 3. The change

Add a dependency-aware request to the streaming protocol and thread `dependsOn` from `apply`
into argument preparation:

1. **`StreamingEvaluable.request(Object[] args, Semaphore dependsOn)`** — new default method:
   wait for `dependsOn`, then `request(args)`. The default is the conservative correctness
   fallback for implementations that cannot chain.
2. **`EvaluableStreamingAdapter`** — override: the executor task waits `dependsOn` *on the
   executor thread*, then evaluates. The host must genuinely wait before a host read; moving the
   wait off the submitting thread preserves non-blocking submission where the hardware is async.
3. **`DestinationEvaluable`** — override: pass `dependsOn` to the nested
   `apply(destination, args, dependsOn)`, so the nested kernel chains on the device with no host
   wait. Delivery is unchanged (already carries the nested completion).
4. **`HardwareEvaluable`** — override: delegate `dependsOn` to the kernel's streaming evaluable;
   the short-circuit branch waits before host evaluation.
5. **`ProcessDetailsFactory.construct(Semaphore dependsOn)`** — both `request` call sites pass
   the dependency through; the no-argument `construct()` (from the `Factory` contract) delegates
   with `null`.
6. **`AcceleratedOperation.getProcessDetails(output, args, dependsOn)`** — `apply` passes its
   `dependsOn` through.

Out of scope, recorded for later: the constant-argument cache
(`ProcessDetailsFactory.init`, `enableConstantCache`) evaluates `isConstant()` arguments once
and reuses the result across invocations. If a producer that reads mutable memory ever reports
`isConstant()`, that is a staleness defect in its own right, independent of chaining — it should
be audited separately.

## 4. Reproduction (written before the fix)

`ArgumentPreparationChainTest` (engine/utils, `org.almostrealism.hardware.test`): a two-member
`OperationList` with mismatched counts (so it cannot fuse and takes the `OperationListRunner`
path with submittable members): member 1 is a compiled kernel writing buffer `X`; member 2 is a
compiled kernel whose source contains a `DynamicCollectionProducer` whose lambda reads `X` on
the host. On Metal, member 1 is encoded but uncommitted when member 2's argument preparation
runs the lambda, which reads stale contents deterministically. On the native backend the
near-synchronous dispatch masks the defect (the test still asserts correctness there).

## 5. Validation

- The reproduction passes on `AR_HARDWARE_DRIVER=mtl` after the change.
- Semaphore batteries: `SemaphoreChainBatchingTest`, `AssignmentSemaphoreChainTest`,
  `SemaphoreCompositionTest`, `AsyncResultDeliveryTest`, `StreamingEvaluableTests`.
- Model battery on default and `mtl` drivers (`BackPropagationTests`, `TrainModelTest`,
  `SyntheticDenseTrainingTest`, `ConvolutionModelTests`, `LayerTrackingTest`).
- Deadlock watch: a `dependsOn.waitFor()` on an executor thread that must commit a Metal buffer
  is the same shape as existing host waits (`MetalSemaphore.waitFor` requests the commit on the
  command runner's executor); the new waits occur only on argument-evaluation threads, never on
  the `MetalCommandRunner` executor itself. `MetalCommandRunner` commit-cause counters
  (`getHostCompleteCommitCount`, `hostCompleteRequesters`) can attribute any unexpected new
  waits.

## 5.1 Follow-on: streaming delegation for reshape wrappers

The investigation that produced the short-circuit fix exposed a structural gap worth its
own change: `ReshapeProducer.get()` wraps every non-provider producer — which includes
every isolated subtree the optimization strategies create — in a `HardwareEvaluable`
whose short-circuit synchronously evaluates the underlying kernel and reshapes the
result. Two costs follow:

- **With a dependency**: the short-circuit branch of `request(args, dependsOn)` must
  wait for the completion on the host (the correctness fix), forcing a commit at every
  dependency boundary that feeds a reshape-wrapped argument.
- **Without one**: the wrapper's request path is synchronous regardless — the underlying
  kernel is evaluated with a completing wait instead of delivering
  `(result, completion)` the way `DestinationEvaluable` does, so a parent kernel never
  chains on an isolated argument's completion. Every isolated-argument evaluation has
  been paying a host wait independent of this plan's change.

Reshape is metadata-only (it re-wraps the handle; it never reads contents), so the
wrapper can deliver asynchronously: delegate `request(args, dependsOn)` to the
underlying kernel evaluable and apply the reshape to the result at delivery — transform
the handle before passing it downstream, leaving the completion semaphore untouched.
Design shape: a delivery-time result transform on `HardwareEvaluable` (the underlying
smell is that `ReshapeProducer` conflates "short-circuit" — a CPU fallback — with
"post-processing" — a result transform); `ReshapeProducer` sets the transform for the
streaming path and keeps the short-circuit for plain `evaluate()`. This removes both
waits and lets isolated arguments chain, which matters most once hazard-aware
subdivision (§6) starts chaining segments: a segment boundary is only cheap if the
reshape-wrapped arguments behind it can chain rather than wait.

Sequencing: hazard-aware subdivision first (it unblocks the parked migration and its
parity gates), this delegation immediately after.

## 6. Relationship to the follow-on (A)

With (B) in place, the hazard-aware subdivision of fused `OperationList`s (cut only at
RAW dependencies between an earlier member's writes and a later member's *hoisted* read set;
overlap by root delegate and offset range; partitioned lists must resist re-fusion at `get()`)
becomes cheap: a segment boundary costs a chained dependency rather than a host wait. That work
un-parks `feature/assignment-copy-migration` (whose CompiledModel output-capture migration is
correct only once fused composites honor cross-member ordering) and is the prerequisite for the
layer-recording migration and the `enableAssignmentCopy` flip.
