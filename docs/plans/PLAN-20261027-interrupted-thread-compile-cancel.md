---
status: proposed
created: 2026-10-27
closed:
tracker: [f86ab017-e3c2-48a3-a906-943491d6aad1]
---

# Stop starting new kernel builds on an interrupted thread

**Tracker task:** f86ab017-e3c2-48a3-a906-943491d6aad1 — "Stop starting new OpenCL builds on a
thread that has been interrupted" (release Common 0.76).

## Motivation

When JUnit times out a test, it interrupts the test thread and moves on to the next test in the
shard. If that thread is compiling kernels in a loop, the interrupt currently stops nothing. In CL
CI job 120161922342 (run 39842281042, `test-media-cl (0)`),
`FixedPatternCorrectnessTest.testTwoMinuteContinuousAudio` timed out at 00:22:36, but deallocation
warnings with `writeAudioAt` frames from that thread were still appearing at 00:23:03. The
abandoned thread kept compiling and evaluating per-note kernels, competing for the single ROCm
device and the OpenCL compiler with the tests that followed it. One timeout therefore becomes a
likely cause of slowness or timeouts in the tests after it.

The *dispatch* half of this problem is already closed. `AcceleratedOperation.awaitCompletion`
throws `HardwareException("Interrupted before ... completed")` when the thread is interrupted
(commits 37eeb9762 and d7a1d018f, covered by `AcceleratedOperationInterruptTest` in engine/utils).
The *compile* half is not. Nothing between "the operation needs its kernel" and
`CL.clBuildProgram` checks the interrupt flag, so a loop whose every iteration compiles a new kernel
keeps going. With OpenCL this is costly, because each build takes from hundreds of milliseconds to
seconds.

## What stays as it is

`CLProgram.compile()` (`base/hardware`, `org.almostrealism.hardware.cl.CLProgram`) runs
`clBuildProgram` on its own 64MB-stack daemon thread (c153f8902). It waits for that thread with an
uninterruptible `join` loop and then restores the interrupt (131b644c8). The javadoc gives the
reason: `clBuildProgram` is a blocking native call that no interrupt reaches, so returning early
would leave the build running against a `cl_program` the caller can then `destroy()`, which is a
use-after-free. **A build that has started is never abandoned.** This plan adds checks only
*before* a build starts.

## Scope

1. **CL check at the build boundary.** Before `CLProgram.compile()` starts its build thread, it
   checks `Thread.currentThread().isInterrupted()`. If the flag is set, `compile()` throws a
   cancellation exception, does not start the build, and leaves the flag set.
2. **The same check, independent of backend, before `deliver`.** `ScopeInstructionsManager`
   (`org.almostrealism.hardware.instructions`) is the single place the dispatch path calls
   `ComputeContext.deliver(scope)`, in `getInstructionSet()` and `getOperator(K)`. Both get the
   same check *before* `getScope()` and `deliver(...)`. OpenCL, Metal, CUDA, native/JNI and
   external contexts then share one contract: an interrupted thread does not start a new
   compilation. It also saves the scope encoding work, not just the native build.
3. **No half-registered state after a cancelled or failed compile.**
   - `ScopeInstructionsManager`: the check runs before `getScope()`. Otherwise `scopeName`,
     `inputs` and `arguments` would be cached for a compile that never happened, and the next
     attempt would log "Repeated attempt to retrieve Scope". `operators` stays `null`, so a later
     call on an uninterrupted thread compiles normally.
   - `CLComputeContext.deliver` adds the `CLOperatorMap` to `instructionSets` only after its
     constructor returns, so the cache is already safe there. **However**, `CLOperatorMap.init`
     rethrows a compile failure without releasing the `cl_program` that `CLProgram.create` made,
     which leaks one native program per failed build. `init` will release the program (via
     `CLProgram.destroy()`, which is safe here because `compile()` has returned and no build is
     outstanding) before it rethrows. The CL check in (1) runs before `CLProgram.create`, inside
     `CLOperatorMap.init`, so a cancelled compile creates no `cl_program` at all. Only a build that
     fails for another reason reaches the release path.
4. **Tests** (see Success criteria).
5. **Docs:** a short paragraph in `docs/internals/backend-compilation-and-dispatch.md` stating the
   compile-side interrupt contract next to the existing description of `deliver()`. It also goes in
   the javadoc of `CLProgram.compile()`, `ScopeInstructionsManager.getOperator`,
   `AcceleratedOperation.load()` and `AcceleratedComputationEvaluable.evaluate()`, whose javadoc
   already describes the dispatch-side contract.

Out of scope: changing how the build waits, changing what any caller does with the exception, and
the `FixedPatternCorrectnessTest` per-note recompiles (fixed separately on
`feature/note-placement-test-fix`).

## Approach

### The cancellation exception

Add `CompilationInterruptedException extends HardwareException` in `org.almostrealism.hardware`.
It is a subtype of `HardwareException` for two reasons:
- `AcceleratedOperation.load()` passes a `HardwareException` through unchanged but wraps any other
  exception in "Could not obtain operator".
- Callers that already handle the dispatch-side interrupt failure (`awaitCompletion` throws a plain
  `HardwareException`) handle this one too.

The dedicated subtype lets a caller or test tell "cancelled by an interrupt" apart from a genuine
compile error, and its message says so plainly (e.g. "Compilation of <name> cancelled: the calling
thread was interrupted"). The class owns the check as a static method,
`CompilationInterruptedException.throwIfInterrupted(String operation)`, so the two call sites share
one implementation and one message format. The metadata argument comes first, as the code-quality
rules require. The method only reads the flag (`isInterrupted()`, never `Thread.interrupted()`).

### Call sites

- `CLOperatorMap.init(metadata, src)`: call `throwIfInterrupted(metadata.getDisplayName())`
  *before* `CLProgram.create`, so no `cl_program` is allocated for a cancelled build.
- `CLProgram.compile()`: call the same check before `new Thread(...)`. This covers callers that go
  straight to `CLProgram` and skip `CLOperatorMap`, so `compile()` keeps the contract on its own.
  The check cannot race with the build. Either it throws before the build thread exists, or the
  build starts and the existing uninterruptible join takes over.
- `ScopeInstructionsManager.getInstructionSet()` and `getOperator(K)`: call the check when
  `operators == null || operators.isDestroyed()`, *before* `getScope()`. The check sits inside the
  existing `try`/`finally` of `getOperator`, so the access listener still fires. When a compiled
  instruction set is already cached, the check is skipped, so an interrupted thread that only
  *reuses* a kernel reaches dispatch, where `awaitCompletion` already reports the interrupt. Kernel
  reuse therefore behaves exactly as it does today.
- `CLInstructionsManager.getOperator` (the deprecated `CLOperatorSources` path) compiles through
  `CLOperatorMap`, so the CL check covers it without a change of its own.

### Uninterrupted behaviour

Every new check is a single `isInterrupted()` read on the current thread, and it throws only when
the flag is set. An uninterrupted thread takes exactly the path it takes today.

## Success criteria

### New test, base/hardware: `CLProgramInterruptTest` (`org.almostrealism.hardware.test`)

Like `CLDataContextLifecycleTest` and `CudaBridgeTest`, this test cannot extend `TestSuiteBase`,
which lives in engine/utils above this module. It builds a `CLDataContext` directly and uses its
`CLComputeContext`. A host with no OpenCL device cannot run it, so it skips there with
`Assume.assumeTrue`, the way `CudaBridgeTest` skips without CUDA. This is a new test describing its
environment honestly, not a guard added to an existing test. The CL lane (`test-cl`, which runs
`base/hardware` with `AR_HARDWARE_DRIVER=native,cl`) is where it runs for real. Every `@Test` has a
timeout. Methods:

- **`compileOnInterruptedThreadDoesNotBuild`**: create a `CLProgram`, interrupt the current
  thread, call `compile()`. Expect `CompilationInterruptedException`, the interrupt flag still set,
  and `CL_PROGRAM_BUILD_STATUS` (via `clGetProgramBuildInfo`) equal to `CL_BUILD_NONE`, showing no
  build was started.
- **`interruptDuringBuildCompletesBuildAndStopsSequence`**: on a worker thread, compile a sequence
  of distinct kernels, the first of them large enough to build for a noticeable time. An
  interrupter thread waits until a live thread named `CLProgram build <name>` exists (found through
  `Thread.getAllStackTraces()`; the build thread name is set in `compile()`) and then interrupts
  the worker, so the interrupt is known to arrive *during* a build. Assert that:
  - the in-progress `compile()` returns normally;
  - its program reports `CL_BUILD_SUCCESS` and a kernel can be created from it, so the
    `cl_program` was not destroyed under the build;
  - the interrupt flag is set after `compile()` returns;
  - the next kernel in the sequence fails with `CompilationInterruptedException`, and no later
    kernel's build status is anything but `CL_BUILD_NONE` (or the program was never created, when
    going through `CLOperatorMap`).
- **`uninterruptedSequenceCompilesAll`**: the same sequence on an uninterrupted thread builds every
  program successfully.
- **`failedCompileReleasesProgram`** (for scope item 3): an invalid source passed to `CLOperatorMap`
  fails, and the `cl_program` is released. This is observed by the program's `CL_PROGRAM_REFERENCE_COUNT`
  before release, or by verifying through the map that `destroy` ran. The exact observation is
  settled during implementation; a test seam is acceptable only if JOCL offers no direct one.

### New test methods, engine/utils: `AcceleratedOperationInterruptTest`

New methods only; the existing ones are not modified.

- **`firstCompileOnInterruptedThreadFails`**: build a computation that has never been compiled,
  interrupt the thread, `get().evaluate()`. Expect a `HardwareException` whose cause chain contains
  `CompilationInterruptedException`, with the interrupt flag still set.
- **`compileAfterCancelledCompileSucceeds`**: after the above, clear the flag and evaluate the same
  evaluable again. It compiles and returns the correct value. This shows the cancelled attempt left
  no half-registered state in `ScopeInstructionsManager`, and no "Repeated attempt to retrieve
  Scope" warning appears.
- **`compileLoopStopsAtInterrupt`**: a loop that compiles distinct computations (distinct constant
  shapes, so nothing is reused), interrupted from another thread, ends with the exception within a
  bounded number of iterations instead of running to completion.

engine/utils runs on every lane, so this test checks the contract on CPU/native, Metal, CL and
CUDA alike.

### Regression checks

Run the existing tests that cover the touched classes: `AcceleratedOperationInterruptTest` (all
methods), `CLDataContextLifecycleTest`, `ExternalInstructionSetTest`, and a representative
engine/utils compile-heavy test class. Run the build validator with its default checks.

### CI evidence

On the CL lane, a test that times out no longer leaves its thread compiling. The log shows no
compile or deallocation activity from that test's thread after the timeout beyond the build that
was in progress. A reviewer can check this in the next `test-media-cl` run that contains a timeout.
No single test can force one for this purpose.

## Dependencies

- None blocking. This builds on the dispatch-side interrupt contract already on master
  (`AcceleratedOperation.awaitCompletion`) and on the uninterruptible build wait in `CLProgram`.
- The OpenCL test needs a host with an OpenCL device. The CL CI lane provides one. Any Linux or
  macOS FlowTree node can do the implementation: elsewhere the CL tests skip and the engine/utils
  tests run on the node's backend. No platform label is required, so this plan has no workstream
  settings file.

## Open questions

1. **Exception type.** The plan proposes a dedicated `CompilationInterruptedException` subtype of
   `HardwareException`. The alternative is a plain `HardwareException` with a cancellation message,
   matching `awaitCompletion`. The subtype makes the tests and diagnostics precise. Approve or
   prefer the plain form?
2. **Callers that swallow `HardwareException`.** If a loop catches `HardwareException` per
   iteration and continues (some audio/pattern render paths may), the thread still stops compiling
   new kernels. But the loop keeps iterating until it ends, now failing quickly each time instead of
   building. That is far cheaper, but it is not a full stop. Auditing such callers is outside this
   task. If the implementation finds one on the `writeAudioAt` path, it should be filed as a
   follow-up rather than fixed here.
3. **Stale interrupt flags.** A thread whose interrupt flag was restored by an unrelated wait and
   never cleared will now fail its next *first* compile, where before it failed only at its next
   dispatch (`awaitCompletion`). In practice the first compile is almost always followed straight
   away by a dispatch, so this should not newly break anything. Any test that newly fails for this
   reason exposes a real leaked interrupt, which must be fixed at its source and never by weakening
   the check.
4. **Metal `deliver` ordering (not changed here).** `MetalComputeContext.deliver` destroys a cached
   instruction set with the same key *before* compiling its replacement. A replacement compile that
   fails leaves the destroyed set in the map. The generic check keeps an interrupted thread from
   reaching that code, but a non-interrupt compile failure still can. This is outside this task and
   is a candidate follow-up task.

## Estimated complexity

**Small to medium.** The production change is three short checks, one small exception class and a
release-on-failure in `CLOperatorMap.init`. Most of the work is the OpenCL test: making "interrupt
arrives during a build" deterministic, and checking build status through JOCL.
