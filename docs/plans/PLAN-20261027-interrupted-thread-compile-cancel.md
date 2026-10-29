---
status: completed
created: 2026-10-27
closed: 2026-10-29
tracker: [f86ab017-e3c2-48a3-a906-943491d6aad1]
---

# Stop starting new kernel builds on an interrupted thread

**Tracker task:** f86ab017-e3c2-48a3-a906-943491d6aad1 — "Stop starting new OpenCL builds on a
thread that has been interrupted" (assigned to the release under development).

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

The backends do not behave alike today, and the difference matters for what this plan claims:

- **OpenCL** (`CLProgram.compile()`): nothing checks the flag before a build, and a build in
  progress is waited for uninterruptibly (see below). An interrupted thread keeps compiling.
- **Native/JNI** (`NativeComputeContext.deliver` and `CLNativeComputeContext.deliver`, through
  `NativeCompiler.compile` and its `runner`): the compiler runs as a child process and the caller
  blocks in `Process.waitFor()`, which *is* interruptible. An interrupt (already set, or arriving
  during the wait) makes `waitFor` throw `InterruptedException`, which `runner` catches and
  rethrows as `HardwareException(e.getMessage(), new UnsupportedOperationException(e))`. So the
  native path already stops, but badly: the exception message is the bare `InterruptedException`
  message, the interrupt flag is **cleared** by the catch, and the compiler process is left running
  unwaited.
- **Metal and CUDA**: no interrupt check before or during compilation.
- **External** (`ExternalComputeContext.deliver`) also compiles through `NativeCompiler.compile`,
  so it shares the native behaviour above.

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
   `ComputeContext.deliver(scope)`, in `getInstructionSet()` and `getOperator(K)` (verified: these
   are the only two `deliver(` calls in production code). Both are needed, because the subclass
   `ComputationInstructionsManager` — what `AcceleratedComputationOperation` uses when instruction
   set reuse is off — overrides `getOperator` to go through `getInstructionSet()`, while the shared
   managers handed out by `DefaultComputer.getScopeInstructionsManager` use the base `getOperator`.
   Both get the same check *before* `getScope()` and `deliver(...)`. OpenCL, Metal, CUDA,
   native/JNI and external contexts then share one contract: an interrupted thread does not
   *start* a new compilation. It also saves the scope encoding work, not just the native build.
   What happens to a compilation that has *already started* when the interrupt arrives still
   differs by backend (see Motivation and Open question 5); this plan changes only the start.
   Kernels compiled outside `deliver` are not covered by this check: the deprecated
   `CLOperatorSources` maps (covered by the CL check, see Call sites) and Metal's built-in copy
   kernels (`MetalComputeContext.copyKernel`, which compiles a `MetalProgram` directly, once per
   unit width, and caches it). The copy kernels are a fixed, small set compiled once per context,
   so leaving them out does not reopen the per-iteration loop this plan is about.
3. **No half-registered state after a cancelled or failed compile.**
   - `ScopeInstructionsManager`: the check runs before `getScope()`. Otherwise `scopeName`,
     `inputs` and `arguments` would be cached for a compile that never happened, and the next
     attempt would log "Repeated attempt to retrieve Scope". `operators` is left as it was
     (`null` on a first compile, or the destroyed instruction set on a recompile), so a later
     call on an uninterrupted thread compiles normally. The warning claim applies to the first
     compile only: when a manager recompiles after its instruction set was destroyed,
     `scopeName` is already set from the earlier compile, so `getScope()` logs "Repeated attempt
     to retrieve Scope" on that recompile today whether or not this plan lands. Note that this
     only covers the cancellation this plan adds. Any `deliver` that fails *after* `getScope()` — a genuine compile
     error, or a native compile whose `waitFor` was interrupted — already leaves `scopeName`,
     `inputs` and `arguments` cached today, and this plan does not change that.
   - `CLComputeContext.deliver` adds the `CLOperatorMap` to `instructionSets` only after its
     constructor returns, so the cache is already safe there. **However**, `CLOperatorMap.init`
     rethrows a compile failure without releasing the `cl_program` that `CLProgram.create` made,
     which leaks one native program per failed build. `init` will release the program (via
     `CLProgram.destroy()`, which is safe here because `compile()` has returned and no build is
     outstanding) before it rethrows, and clear its `prog` field so nothing can release it a
     second time (`CLProgram.destroy()` does not guard against a repeated call; it would pass
     `null` to `clReleaseProgram`). A second copy of the CL check (see Call sites) runs before
     `CLProgram.create`, inside `CLOperatorMap.init`, so a cancelled compile on that path creates no
     `cl_program` at all; the check in `CLProgram.compile()` itself (item 1) is the backstop for
     callers that create a `CLProgram` directly. Only a build that
     fails for another reason reaches the release path.
4. **Tests** (see Success criteria).
5. **Docs:** a short paragraph in `docs/internals/backend-compilation-and-dispatch.md` stating the
   compile-side interrupt contract next to the existing description of `deliver()` (the
   "ComputeContext — The Compilation Bridge" section), plus an update to that document's
   `getOperator` snippet, which reproduces today's compile-on-first-call code. It also goes in the
   javadoc of `CLProgram.compile()`, `ScopeInstructionsManager.getOperator` and
   `getInstructionSet`, `AcceleratedOperation.load()` and
   `AcceleratedComputationEvaluable.evaluate()`. Of these, only `evaluate()` describes the
   dispatch-side contract today (as do `AcceleratedOperation.run()` and
   `AcceleratedOperation.awaitCompletion`, which are not on the list because their contract does
   not change); `load()`'s javadoc says nothing about interrupts yet.

Out of scope: changing how the build waits, changing what any caller does with the exception, the
native compiler's handling of an interrupt that arrives during `Process.waitFor()` (Open question
5), and the `FixedPatternCorrectnessTest` per-note recompiles (already fixed on master by the
merged `feature/note-placement-test-fix` branch, commit 68f841a4a "Guard note placement against
per-position kernel compilation").

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
`CompilationInterruptedException.throwIfInterrupted(String operation)`, so the four call sites
listed below share one implementation and one message format. The metadata argument comes first, as the code-quality
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
  `CLOperatorMap` — once per context in `CLOperatorSources.init` and once per class in
  `getOperators(Class)`, both cached — so the CL check covers it without a change of its own. No
  production code constructs a `CLInstructionsManager` at all today (its constructor is its only
  occurrence outside javadoc), so this path matters only for completeness.
- **Native/JNI** gets the generic check from `ScopeInstructionsManager` and needs no change of its
  own for this plan; how `NativeCompiler.runner` reports an interrupt that arrives *during*
  `Process.waitFor()` is left as it is (Open question 5).

### Uninterrupted behaviour

Every new check is a single `isInterrupted()` read on the current thread, and it throws only when
the flag is set. An uninterrupted thread takes exactly the path it takes today.

## Success criteria

### New test, base/hardware: `CLProgramInterruptTest` (`org.almostrealism.hardware.test`)

Like `CLDataContextLifecycleTest` and `CudaBridgeTest`, this test cannot extend `TestSuiteBase`,
which lives in engine/utils above this module. It builds a `CLDataContext` directly and uses its
`CLComputeContext`. A host with no OpenCL device cannot run it, so it skips there with
`Assume.assumeTrue`, the way `CudaBridgeTest` skips without CUDA. This is a new test describing its
environment honestly, not a guard added to an existing test. The CL lane (`test-cl`, whose
matrix group 0 runs `base/hardware` with `AR_HARDWARE_DRIVER=native,cl`) is where it is guaranteed
to run for real. Because the test builds its own `CLDataContext` rather than following the
configured driver, it will also run on any other lane whose host exposes an OpenCL device (the
macOS lane, `test-mac`, runs `base/hardware` too, and macOS still ships a deprecated OpenCL), so it
must not assume a particular vendor's build timing. The same applies to the other lanes that run
`base/hardware` (`test`, with `AR_HARDWARE_DRIVER=native`, and `test-cuda`, with `native,cuda`):
the driver setting does not stop the test building its own `CLDataContext`, so it runs there too
whenever the host has an OpenCL ICD installed (an NVIDIA driver commonly provides one). Every
`@Test` has a timeout.

`CLDataContextLifecycleTest` only constructs and destroys contexts without triggering OpenCL
startup, so it shows how to build a context without `TestSuiteBase` but not how to reach the
device. `clGetProgramBuildInfo` needs a `cl_device_id`, and `CLDataContext` exposes none
publicly (only `getClContext()` and the value object `getMainDeviceInfo()`). The test can obtain
the device from the program itself with `clGetProgramInfo(..., CL_PROGRAM_DEVICES, ...)`, which
avoids adding an accessor purely for the test. Methods:

`CLProgram.compile()` names its build thread `CLProgram build ` followed by the metadata's display
name. `CLProgram`'s constructor substitutes an empty `OperationMetadata` when given `null`, so
every program built without metadata gets the same thread name. The interrupter below finds the
build thread by name, so each program the test creates needs `OperationMetadata` with its own
display name.

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

  Seeing the build thread alive does not prove the interrupt landed before the build finished: a
  fast compiler can finish between the interrupter's observation and its `interrupt()` call. All
  of the assertions above still hold in that case, so the test does not become flaky, but the run
  then does not actually exercise "interrupt during a build". The test should record which case
  occurred (for example by checking whether the build thread was still alive immediately after
  `interrupt()`), so a reviewer can tell whether the CL lane really covered the in-progress case.
  Making it fully deterministic would need a hook inside `compile()`, which this plan does not
  propose.
- **`uninterruptedSequenceCompilesAll`**: the same sequence on an uninterrupted thread builds every
  program successfully.
- **`failedCompileReleasesProgram`** (for scope item 3): an invalid source passed to `CLOperatorMap`
  fails, and the `cl_program` is released. Observing the release is harder than it looks, and the
  plan should not pretend otherwise. The failure is thrown from the `CLOperatorMap` constructor
  (which calls `init`), so the test never receives the map; the `CLProgram` is held only in the
  private `prog` field; and querying a `cl_program` after `clReleaseProgram` (for example its
  `CL_PROGRAM_REFERENCE_COUNT`) is undefined behaviour in OpenCL, not a check. JOCL offers no
  direct observation, so this test needs a seam. The least invasive one available today is that
  `CLOperatorMap.init` is `protected` and the class is not final: a test subclass can override
  `init`, call `super.init`, and observe the release through a small protected hook (for example
  a protected method that `init` calls to release the program, which the subclass records). The
  implementer chooses the seam; the approver should know that one is required (Open question 6).

### New test methods, engine/utils: `AcceleratedOperationInterruptTest`

New methods only; the existing ones are not modified.

- **`firstCompileOnInterruptedThreadFails`**: build a computation that has never been compiled,
  interrupt the thread *before calling `get()`*, then `get().evaluate()`. Where the compile happens
  depends on the producer: `CollectionProducerComputation.get()` builds a
  `DefaultCollectionEvaluable` and calls `load()` inside `get()`, while
  `CollectionProducerComputationBase.get()` returns a `HardwareEvaluable` whose kernel is created
  lazily on the first `evaluate()`. Interrupting before `get()` covers both, and the try block
  must enclose `get()` as well as `evaluate()`. Assert that the cause chain contains
  `CompilationInterruptedException` rather than that it is thrown bare:
  `AcceleratedOperation.load()` and `setupOperator` pass a `HardwareException` through unwrapped,
  but `AcceleratedComputationEvaluable.evaluate()` wraps any `HardwareException` raised inside its
  `try` block (which includes `apply()`, and `apply()` calls `load()` when no arguments are bound)
  in "Failed to evaluate ...". Also assert the interrupt flag is still set. On the native lane
  this must be the new exception, not the existing `NativeCompiler.runner` failure, which would show the generic check
  ran too late.
- **`compileAfterCancelledCompileSucceeds`**: after the above, clear the flag and evaluate the same
  evaluable again. It compiles and returns the correct value. This shows the cancelled attempt left
  no half-registered state in `ScopeInstructionsManager`, and no "Repeated attempt to retrieve
  Scope" warning appears (observable through a listener registered with `Console.addListener` on
  `Hardware.console`, which is the console `ScopeInstructionsManager.console()` returns; remove
  the listener in a `finally`). With `ScopeSettings.enableInstructionSetReuse` on (the default,
  controlled by `AR_INSTRUCTION_SET_REUSE`), the manager is shared
  through `DefaultComputer`'s signature cache, so the second evaluation reaches the same manager
  the cancelled attempt left behind — which is exactly the state this test must exercise.
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

## Related plans

- [`PLAN-20261026-collected-after-free-not-double-free.md`](PLAN-20261026-collected-after-free-not-double-free.md)
  deals with the deallocation warnings that the Motivation uses as evidence the abandoned thread
  was still running. It lists "the JUnit timeout / uninterruptible `CLProgram.compile` join" as
  out of its scope, which is the gap this plan fills. The two plans touch different code and can
  be approved independently.

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
5. **Native compiler interrupt handling (not changed here).** `NativeCompiler.runner` already stops
   on an interrupt, because `Process.waitFor()` throws, but it converts the `InterruptedException`
   into a `HardwareException` wrapping an `UnsupportedOperationException`, clears the interrupt
   flag in doing so, and does not destroy the compiler process it stops waiting for. With this
   plan, an interrupt that arrives *before* a native compile is reported by the new exception with
   the flag kept; one that arrives *during* the compiler run is still reported the old way, with
   the flag lost, so a caller further up may not see the interrupt at all. Restoring the flag (and
   reporting `CompilationInterruptedException`) there is small and would make the contract uniform
   across backends; whether to fold it into this plan or file it as a follow-up is for the
   approver. It is listed as out of scope above only so the plan does not grow silently.

6. **Test seam for the failed-build release.** `failedCompileReleasesProgram` cannot observe the
   release of a `cl_program` without a seam in `CLOperatorMap` (see Success criteria). Approve a
   small protected hook in `CLOperatorMap.init` for this, or prefer to drop that test and rely on
   review for the release-on-failure change?

## Estimated complexity

**Small to medium.** The production change is one small exception class with its static check,
called from four places (`CLOperatorMap.init`, `CLProgram.compile()`, and
`ScopeInstructionsManager.getInstructionSet()`/`getOperator(K)`), plus a release-on-failure in
`CLOperatorMap.init`. Most of the work is the OpenCL test: making "interrupt
arrives during a build" deterministic, and checking build status through JOCL.
