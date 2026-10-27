---
status: completed
created: 2026-10-26
closed: 2026-10-26
tracker: [be73ea2e-6ac1-4db4-b1e6-b37624b76570]
---

# Report Only Real Double Frees from HardwareMemoryProvider

**Tracker task:** be73ea2e-6ac1-4db4-b1e6-b37624b76570, "Stop HardwareMemoryProvider reporting
explicit-free-then-GC races as double frees". **Release:** Common 0.76.

## Motivation

`HardwareMemoryProvider.releaseNow` calls `warnDoubleFree` whenever `NativeRef.tryClaimFreed()`
loses. In the CL media shards, every report has the same two stacks. First, an *explicit* release:
instruction-cache eviction ending in `MemoryDataAdapter.destroy` → `deallocate(int, T)`, or
`sweepDeferred` finishing a release that `KernelMemoryGuard` had deferred. Second, the *GC* path:
the "Deallocation Submit Thread" takes the block's `NativeRef` off the reference queue and calls
`deallocateNow(ref)`. The claim is atomic, so the driver is never called twice. These are not
double frees, but each one is reported as a bug:

- **Noise.** A detailed report prints up to three stack traces: the allocation trace (only when
  `RAM.allocationTraceFrames` > 0, which is off by default), the first-free trace (only when
  `MemoryReference.captureFreeStackTrace` is on, which is the default) and the current thread's
  stack (always).
- **Real double frees get hidden.** Detail is limited by the static, never-reset
  `doubleFreeAttempts` counter to `DOUBLE_FREE_DETAIL_LIMIT` (64) per JVM. The counter is shared
  by every provider in the JVM. Benign races use up that budget, so a real ownership bug later in
  the same JVM shows up only as a periodic count (every `DOUBLE_FREE_SUMMARY_INTERVAL` = 100,000
  attempts) with no stacks. The throttle was added by commit `ec5374602` ("Bound
  prevented-double-free logging so it cannot starve a render") after `GenerateAudioFileTest` timed
  out on the Metal media runner. That change capped the reporting but never traced where the
  warnings came from.

A `PhantomReference` is enqueued only if the reference object itself is still strongly reachable
when the GC finds its referent phantom-reachable. The provider keeps each `NativeRef` reachable
through `allocated` (until `releaseNow` removes it) and through `deferred` (until `sweepDeferred`
removes it). This is how the GC path gets to see a ref that an explicit release is handling:

1. **Deferred release.** The owner drops its memory while a kernel still holds the block.
   `deferIfInUse` records a `DeferredRelease`, and the ref stays in both `allocated` and
   `deferred`. `KernelMemoryGuard` holds the `RAM` strongly until the kernel finishes. After that
   the RAM is phantom-reachable and the GC can enqueue the ref. Two orderings follow directly from
   the code, and both are reported today:
   - **Sweep first.** `sweepDeferred` frees the block, then the submit thread's
     `deallocateNow(ref)` loses the claim. "Current free attempt" is the Deallocation Submit
     Thread.
   - **GC first.** The submit thread takes the ref while it is still in `deferred`. The guard has
     already let go, so `deferIfInUse` returns `false` and the GC path frees the block. It does
     not remove the `deferred` entry. On the next sweep, `releaseNow(ref)` loses the claim.
     "Current free attempt" is the Deallocation Process Thread, and "First freed at" is the submit
     thread.
2. **Queued mode.** With `queueDeallocation = true`, `deallocate(int, T)` only puts the ref on
   the deallocation queue. The ref stays in `allocated`, so if the caller drops the RAM the GC
   can enqueue the same ref, and the submit thread queues it a second time. Whichever entry the
   process thread takes second loses the claim. `PriorityBlockingQueue` orders only by size, so
   the two entries can come off in either order. **This mode is off everywhere in the
   repository.** `queueDeallocation` is a plain static field (no system property or environment
   variable binds it), and nothing outside `HardwareMemoryProvider` assigns it (checked with grep
   across all modules), so it is always `false`. Queued mode therefore cannot produce the CI
   reports. It is covered here only so that the change stays correct if someone enables it.
3. **Inline release (inferred, not demonstrated).** In `deallocateNow(T mem)`, `mem` is not used
   after `getNativeRef(mem)`, so the JIT may treat it as unreachable while the native release is
   still running. Note that the main explicit caller, `MemoryDataAdapter.destroy`, keeps the RAM
   in its `mem` field until `deallocate` returns, and only then sets `mem = null`. A caller like
   that keeps the referent strongly reachable for the whole call, so this window can only open for
   callers that hold the RAM in nothing but a local variable. No such report has been traced to a
   specific caller.

The submit thread never checks `isFreed()`, so whatever the GC enqueues reaches the claim.

The converse matters just as much: a ref the provider no longer holds is not enqueued at all.
Once `releaseNow` has removed a ref from `allocated` (and it is not in `deferred` or on the
deallocation queue), nothing keeps the `NativeRef` reachable, so the GC collects it with its
referent and does not enqueue it. A GC race is therefore possible only while the ref is still
held: during a deferral, while it waits on the deallocation queue, or in the window between the
claim and the `allocated` removal inside `releaseNow`. This is why the three cases above are the
complete list, and why the fix has to be in the claim-lost branch rather than in clearing refs
after release (see Approach step 3).

One consequence is worth checking against the logs before implementing (Open question 9). With
`queueDeallocation = false`, an explicit release that is *not* deferred cannot race with the GC
when the caller keeps the RAM reachable, as `MemoryDataAdapter.destroy` does. So in the two
deferred orderings the explicit side's stack is `sweepDeferred` on the Deallocation Process
Thread, not `MemoryDataAdapter.destroy`: the eviction stack is where the release was *requested*,
and it does not appear in either "First freed at" or "Current free attempt at". A report whose
stack runs through `MemoryDataAdapter.destroy` → `deallocate(int, T)` is not explained by the
mechanisms above, unless it is the inferred item 3 with a caller other than
`MemoryDataAdapter.destroy`.

**What `warnDoubleFree` can actually catch today.** A second *sequential* explicit
`deallocate(size, ram)` never reaches `warnDoubleFree`. The first release removes the ref from
`allocated`, so the second call's `getNativeRef(mem)` returns `null`. `deallocateNow(T mem)` then
logs "Attempting to deallocate untracked memory" if `mem.isActive()`, and stays silent otherwise.
Whether it logs depends on the backend. `MetalMemory.isActive()` is `false` once its `MTLBuffer`
is released. `CLMemory.isActive()` returns `!released`, but nothing in the repository calls
`CLMemory.tryClaimReleased()` any more (the claim moved to `NativeRef` when `CLMemoryProvider`
moved onto `HardwareMemoryProvider`), so `released` is never set and a released `CLMemory` still
reports active. On the CL backend a sequential second `deallocate` therefore logs "Attempting to
deallocate untracked memory", not a double free. `RAM.isActive()` defaults to `true`, as for the
fake `RAM` in `AddressReuseDuringReleaseTest`, whose `staleHandleDoesNotResolveToReusedBlock`
relies on the second `deallocate` freeing nothing. The explicit-versus-explicit cases that do reach `warnDoubleFree` are two explicit
requests that both obtained the ref before either release finished. That happens with two
concurrent releases, or with two queued-mode `deallocate` calls for the same RAM. In practice
almost every report in today's logs is a race with the GC path, so this plan is mainly about
removing those reports, not about separating them from a large population of real double frees.

## Scope

All production changes are in `base/hardware`, package `org.almostrealism.hardware.mem`. Most of
them are in `HardwareMemoryProvider`. Recording which origin won a claim (Approach, step 1) also
touches `MemoryReference` or `NativeRef`. Tests go in
`base/hardware/src/test/java/org/almostrealism/hardware/mem/` (but see Open question 1).

In scope:

- The GC (reference-queue) path silently drops a ref that has already been claimed. This is the
  expected outcome after an explicit free, not a defect.
- An **explicit** release that loses its claim to another **explicit** release still reaches
  `warnDoubleFree` and keeps its detailed report. (A sequential second `deallocate` on a RAM that
  is already released does not reach it today and still will not. See "What `warnDoubleFree` can
  actually catch today".)
- The GC path no longer uses up the double-free detail budget.
- Close the inline-release window with `Reference.reachabilityFence`, and `clear()` the ref after
  a successful explicit release. Both are cheap hardening. Neither is expected to remove any of the
  reports traced so far (see Approach step 3 and Open questions 5 and 6).
- Regression tests for both behaviours.
- Bring the class Javadoc ("Two-Thread Deallocation Architecture") and the `warnDoubleFree`
  Javadoc in line with the new behaviour. The class Javadoc is already out of date in ways this
  change touches: its process-thread pseudo-code shows `deallocationQueue.take()` followed by
  `deallocateNow(ref)`, but the thread actually polls with a `DEFERRED_SWEEP_INTERVAL_MS` timeout
  and calls `sweepDeferred()` on every iteration, so it is the thread that finishes deferred
  releases even when `queueDeallocation` is `false`. The `warnDoubleFree` and
  `doubleFreeAttempts` Javadoc call every lost claim a "prevented double free"; after this change
  that is true only of explicit-versus-explicit races.

Out of scope:

- The cost of `MemoryReference.captureFreeStackTrace`, which captures a stack trace on every
  successful claim. That is a separate tuning question (see Open questions).
- The `DoubleFreeReport` thresholds and the static counter itself. They stay as they are. They
  only stop being consumed by events that are not double frees.
- The JUnit timeout / uninterruptible `CLProgram.compile` join mentioned in the task's "Related"
  section.
- The backend subclasses (`CLMemoryProvider`, `MetalMemoryProvider`, `CudaMemoryProvider`,
  `NativeMemoryProvider`). The release protocol they take part in is `deallocate(NativeRef)` and
  `nativeRef(T)`, and neither changes. The backend ref subclasses (`CLMemoryRef`,
  `MetalMemoryRef`, `CudaMemoryRef`, `NativeBufferRef`) only cache handles and inherit any field
  added to `NativeRef`.

## Approach

### 1. Track where each release request came from

Today one method, `deallocateNow(NativeRef)`, serves three callers: the GC submit thread, the
deallocation process thread (queued mode), and the explicit `deallocate(int, T)` →
`deallocateNow(T)` path. The double-free decision depends on which caller it is, so the origin
has to be explicit:

- Add a small private enum (e.g. `ReleaseOrigin { EXPLICIT, COLLECTED }`).
- `deallocateNow(NativeRef<T> ref, ReleaseOrigin origin)`:
  - For `COLLECTED`, return immediately when `ref.isFreed()`. This check must come **before**
    `deferIfInUse`. Otherwise a stale, already-freed ref whose address now belongs to a new block
    that a kernel holds would be deferred (`deferred` is keyed by address). `sweepDeferred` would
    later call `releaseNow` on it, which would itself trigger a spurious double-free warning.
  - Then `deferIfInUse` and `releaseNow(ref, origin)` as today.
- `releaseNow(ref, origin)`: when `tryClaimFreed()` loses, the report depends on **both** sides
  of the race. Call `warnDoubleFree(ref)` only when the losing request is `EXPLICIT` **and** the
  winning claim was also `EXPLICIT`. Return silently in every other case. The `isFreed()` check
  above is only a fast path. This branch is what makes the race (claim lost between the check and
  the claim) silent.
  - Deciding on the loser's origin alone is not enough. In two orderings an explicit request
    loses to a collected one: the "GC first" deferred ordering in the Motivation, and queued mode
    when the submit thread's entry comes off the queue first. Both are benign, and both would
    still be reported if only the loser were checked.
  - The winner's origin has to be recorded when the claim is made. One way is a field on
    `NativeRef`, set by the provider right after a successful `tryClaimFreed()` and reset by
    `unclaimFreed()`. Then a lost claim reads it. Because a lost claim can run between the winning
    `tryClaimFreed()` and that write, the write should either happen inside the claim (an
    origin-taking variant of `tryClaimFreed` on `MemoryReference`, under the same monitor) or
    default to "unknown, stay silent". The implementer should choose one. The choice changes
    `MemoryReference`/`NativeRef` in `org.almostrealism.hardware.mem`, which the Scope section
    above now includes.
- `DeferredRelease` keeps the origin of the request that created it. `sweepDeferred` passes that
  origin to `releaseNow` as the loser's origin. `computeIfAbsent` keeps the first request's
  origin. That is acceptable: either way the block is freed once, and the winner-origin rule above
  decides whether a lost claim is reported.
- A successful `COLLECTED` release should also remove any `deferred` entry that holds the **same**
  ref, compared by identity, as `releaseNow` already does for `allocated`. Without it the stale
  entry causes two problems. `sweepDeferred` removes an entry before it calls `releaseNow`, so it
  re-attempts the freed block only once, which the winner-origin rule makes silent. The larger
  problem is that `deferred` is keyed by address and `deferIfInUse` uses `computeIfAbsent`: if the
  backend reuses the address for a new block and that block's release is deferred while the stale
  entry is still present, the new ref is never recorded, and when the stale entry is swept its
  lost claim is all that happens. The new block is then left to the GC path or leaks until the
  provider is destroyed. The identity-checked removal closes that.

### 2. Queued mode (`queueDeallocation = true`)

The deallocation queue currently holds bare `NativeRef`s. Both the submit thread and the explicit
`deallocate(int, T)` fill it, so the same ref can be queued once by each (Motivation, item 2).
`PriorityBlockingQueue` orders only by size, so either entry can be taken first, and the second
is reported today. Change the element type to a small private record
(`PendingRelease(NativeRef<T> ref, ReleaseOrigin origin)`), still ordered by `ref.getSize()`
descending, so the process thread passes the origin through. With the winner-origin rule from
step 1, the second entry is silent whichever one wins, unless both entries are explicit. The
submit thread also skips refs that are already `isFreed()` before queuing them.
`getDeallocationQueue()` is `protected`, but nothing in the repository calls or overrides it
except `HardwareMemoryProvider` itself (checked with grep across all modules), so its type can
change, or it can become private.

### 3. Close the windows instead of only hiding them

- In `deallocateNow(T mem)` (explicit path), wrap the release in `try { ... } finally {
  Reference.reachabilityFence(mem); }` so `mem` stays strongly reachable until the native release
  and the `allocated` removal have finished (Motivation, item 3). This is cheap, but it addresses
  an inferred window. It is not expected to remove any of the reports traced so far, which come
  from the deferred path (queued mode is never enabled; see Motivation, item 2).
- In `releaseNow`, after a successful `EXPLICIT` release (the `released == true` branch), call
  `ref.clear()`, **after** the identity-checked `allocated` removal. Clearing a `PhantomReference`
  before the GC finds it prevents enqueueing. Its practical effect is small, though. Once the
  removal is done the provider no longer holds the ref (see the end of the Motivation), so the GC
  would not enqueue it anyway; `clear()` only closes the few instructions between the removal and
  the end of `releaseNow`, while the local variable still holds it. The orderings traced so far
  are not affected: in both deferred orderings the GC can enqueue the ref throughout the deferral,
  before any explicit claim succeeds. This is safe: `PhantomReference.get()` always returns
  `null`, so the native release already uses only cached fields (address, size and per-backend handles such as `CLMemoryRef.mem`
  or `NativeBufferRef`'s root buffer). The order matters because `getNativeRef` matches the
  tracked entry with `ref.refersTo(ram)`. A ref cleared while still in `allocated` would make a
  concurrent `getNativeRef` return `null`, and the caller would see "Attempting to deallocate
  untracked memory" instead of a lost claim. After the removal, the block is already absent
  from `allocated` and `isReleased` already reports it as released, so clearing changes nothing
  that is visible. A ref the GC has already enqueued is unaffected by `clear()`, which is why
  step 1 is still needed. Clearing does not help a release that is still deferred, because the
  ref is cleared only after the sweep finally frees it.

### 4. Tests (base/hardware, plain JUnit, `@Test(timeout = ...)`)

`base/hardware` sits below the engine layer, so the tests follow `HardwareMemoryProviderLeaseTest`:
plain JUnit, not `TestSuiteBase`. A new test class (e.g. `HardwareMemoryProviderDoubleFreeTest` in
`org.almostrealism.hardware.mem`) uses a minimal provider modelled on the one in
`engine/utils/.../AddressReuseDuringReleaseTest`. Its `deallocate(NativeRef)` counts releases and
records each released ref, and a fake `RAM` gives each instance its own address.

GC enqueueing is simulated with `Reference.enqueue()` on the tracked `NativeRef`. This puts the
ref on the provider's reference queue as the GC would. Three properties of that simulation shape
the cases (each was checked on the JDK 17 used here):

- **`enqueue()` clears the referent.** Afterwards `ref.refersTo(ram)` is `false`, so
  `getNativeRef(ram)` returns `null` and an explicit `deallocate(size, ram)` made *after*
  `enqueue()` only logs "Attempting to deallocate untracked memory". This matches the real GC,
  where the referent is gone before the ref is enqueued, so no explicit caller can still be
  holding it. Every case must therefore make the explicit request **before** calling
  `enqueue()`.
- **`ReferenceQueue` is LIFO, not FIFO.** `poll()` and `remove()` return the most recently
  enqueued reference. A sentinel enqueued after the target can be handled *before* it, so "wait
  for a sentinel's release" does not show that the target was handled. Wait on something the
  submit thread does after it handles each ref instead. One option is a package-private counter
  of refs taken off the reference queue, incremented at the end of each loop iteration. The test
  waits, with a timeout, until the counter reaches the number of refs it enqueued.
- **Every release path reaches `Hardware`.** `deallocateNow(NativeRef)` calls `deferIfInUse`,
  which calls `reservations()` → `kernelMemoryGuard()` → `Hardware.getLocalHardware()`. So
  `Hardware`'s static initializer (backend selection and start-up) runs on the first release in
  *every* case, not only in the deferral case. Likewise the inherited `console()` returns
  `Hardware.console`. See Open question 1.

Observability:

- Add a package-private static accessor for the double-free attempt count (`doubleFreeAttempts`)
  so tests can assert the counter's *delta*. That shows directly whether the detail budget was
  used. `warnDoubleFree` returns before it increments the counter when `RAM.enableWarnings` is
  `false` (`AR_HARDWARE_MEMORY_WARNINGS=false`). A "delta of 0" assertion would then pass
  vacuously. The tests must set `RAM.enableWarnings = true` and restore it in `finally`, as they
  do for `queueDeallocation`. The counter is static and shared by every provider in the JVM.
  Surefire runs other test classes in the same fork, and if one of them has already started
  `Hardware`, a real provider's deallocation threads can increment the counter while a case runs.
  An exact delta is then only reliable for 0-versus-nonzero in a quiet JVM. The captured console
  lines from the test provider's own `console()` (below) are provider-local and should be the
  primary assertion; the counter delta is a secondary check.
- Capture warnings by overriding `console()` in the test provider to return a child `Console`
  (`Console.root().child()`) with a listener (`Console.addListener`). This also keeps logging
  from touching `Hardware.console`. Remove the listener in teardown.

Cases:

1. **Explicit free, then GC enqueue:** `deallocate(size, ram)` → `ref.enqueue()` → wait for the
   submit thread. Expect exactly one native release, a counter delta of 0, and no "Skipping double
   deallocate" line. The test holds the ref (obtained with `getNativeRef` before the release, as
   `AddressReuseDuringReleaseTest.trackedRef` does), so it can enqueue it after the provider has
   dropped it. In production that only happens in the short window inside `releaseNow` (see the
   end of the Motivation). This case pins the claim-lost behaviour; case 2 is the one that matches
   the reports seen in CI.
2. **Deferred explicit release, both orderings:** the explicit `deallocate` is deferred because a
   guard reservation is held, then the reservation is released. (a) The sweep frees the block,
   then `ref.enqueue()`. (b) `ref.enqueue()` first, so the submit thread frees it, then the sweep
   runs. Both need the guard seam (Open question 1). Expect one release and a delta of 0 in both.
   Today, (b) is reported from the Deallocation Process Thread.
   Ordering (b) is hard to force. The process thread runs `sweepDeferred` every
   `DEFERRED_SWEEP_INTERVAL_MS` (100 ms) on its own, so between releasing the reservation and
   the submit thread handling the enqueued ref, the sweep may free the block first, which turns
   (b) into (a). Enqueueing *before* releasing the reservation does not help either: the submit
   thread then defers too (`computeIfAbsent` keeps the explicit entry) and the sweep does both
   releases. A deterministic (b) needs the seam to answer per caller, for example a hook that
   returns the `ReservationState` and reports "executing" to the process thread until the submit
   thread has finished. That shapes the choice in Open question 1. Without it, (b) is a timing
   test and may be flaky.
   With option (a) of Open question 1, a reservation on a test-owned `KernelMemoryGuard` is taken
   with `acquire(MemoryData...)`, so the case also needs a `MemoryData` whose `getMem()` returns
   the fake `RAM`.
3. **Two explicit releases racing for the same ref:** the test provider's native
   `deallocate(NativeRef)` blocks on a latch. Thread A calls `deallocate(size, ram)` and blocks
   inside the native release. At that point the ref is claimed but still in `allocated`, with its
   referent intact. The test thread then calls `deallocate(size, ram)`, loses the claim, and
   reaches `warnDoubleFree`. Release the latch. Expect one native release, a counter delta of 1,
   and a captured "Skipping double deallocate of ..." line followed by "First freed at:". This is
   the still-loud behaviour.
   *Not* this: calling `deallocate` twice in sequence on the same `RAM`. The second call finds no
   tracked ref and logs "Attempting to deallocate untracked memory" (see Motivation). It never
   reaches `warnDoubleFree`, today or after this change. `AddressReuseDuringReleaseTest` already
   relies on that.
4. **GC enqueue of an unfreed ref:** the native release happens once and nothing is reported.
   This guards against the new check swallowing real GC releases.
5. **Queued mode:** with `queueDeallocation = true` (restored in `finally`), call
   `deallocate(size, ram)` (queues the explicit entry), then `ref.enqueue()` (the submit thread
   queues a collected entry for the same ref). Whichever entry the process thread takes first,
   expect one release and nothing reported.

`HardwareMemoryProviderLeaseTest` and `engine/utils` `AddressReuseDuringReleaseTest`,
`MemoryReclaimTest` and `DestinationReuseTest` also exercise this class and must keep passing.

## Success criteria

- In `HardwareMemoryProvider`, a lost claim calls `warnDoubleFree` only when both the winning
  and the losing request were explicit. Neither the GC path losing to an explicit release, nor an
  explicit release losing to the GC path, is reported.
- New tests: case 1 shows no warning and no counter consumption after an explicit free followed
  by GC enqueue. Case 3 shows the detailed warning for two racing explicit releases. Cases 2(a),
  2(b), 4 and 5 pass. Case 2(b) forces its ordering through the seam rather than by timing, and
  each case passes on repeated runs (for example `repetitions` 10 in `ar-test-runner`).
- Cases 1, 2(a), 2(b) and 5 each fail against master's release logic (with only the test seam
  from Open question 1 added), because master reports a double free in each of them. That shows
  the tests detect the behaviour they guard rather than passing vacuously.
- Existing tests above pass, run through `ar-test-runner` with bounded `test_classes`.
- The build validator (checkstyle, code_policy, test_timeouts, duplicate_code) passes.
- After merge, the `test-media-cl` CI shards show no "Skipping double deallocate" report in which
  either stack ("First freed at" or "Current free attempt at") is the Deallocation Submit Thread.
  Checking only "Current free attempt at" would miss the "GC first" ordering, which is reported
  from the Process Thread. Only CI can verify this. Reviewers should check the first CL media run
  after merge. Any detailed reports that remain are explicit-versus-explicit races, and they are
  the real ownership bugs this change is meant to make visible.

## Dependencies

None. The task has no `blocked_by` tasks, and everything it touches is already on master.

## Open questions

1. **Guard seam for tests.** Every call to `deallocateNow(NativeRef)` goes through
   `deferIfInUse` → `reservations()` → `kernelMemoryGuard()` → `Hardware.getLocalHardware()`, and
   `sweepDeferred` takes the same route. So *every* case above, not only the deferral case,
   triggers `Hardware`'s static initialization, which picks and starts a real backend. No existing
   test in `base/hardware/src/test/.../mem` does that. (`HardwareMemoryProviderLeaseTest` never
   releases anything.) Options:
   (a) make the private `kernelMemoryGuard()` a `protected` hook, so a test provider can supply its
   own `KernelMemoryGuard` (it has a public no-argument constructor) or `null`. Then all cases run
   without real hardware, and case 2 can drive a deferral with `KernelMemoryGuard.acquire`; or
   (b) put the cases in `engine/utils` next to `AddressReuseDuringReleaseTest`, which already
   exercises the release path with `Hardware` initialized (by extending `TestSuiteBase`). Case 2
   would then have to reserve the address on the process-wide guard.
   The task asks for base/hardware, so (a) is proposed. The reviewer should confirm that the hook
   is acceptable, since it adds a `protected` extension point to production code only to serve
   tests. A hook at the `reservations(long)` level (returning `ReservationState`, a public record
   with a public constructor) would serve case 2(b) better than one at the `kernelMemoryGuard()`
   level, because it lets the test decide what the process thread's sweep sees independently of
   the submit thread (see case 2). Note that `sweepDeferred` also uses `kernelMemoryGuard()`
   directly, for `warnIfReserved` on expiry, so a `reservations`-only hook leaves that call on the
   real `Hardware` path. It is reached only when a deferral times out (30 s), which no case
   should do.
2. **Is recording the winner's origin worth it?** The winner-origin rule in Approach step 1 is
   what keeps the "GC first" deferred ordering and the queued-mode ordering silent. It needs a new
   field on `NativeRef`/`MemoryReference`. A simpler alternative would be to report only lost
   claims whose loser is explicit *and* whose first-free stack is not on the Deallocation Submit
   Thread. But that depends on `captureFreeStackTrace` (which can be disabled) and on thread names,
   so it is not proposed.
3. **`captureFreeStackTrace` cost.** Each successful claim still captures a stack trace while
   detection is on, which is the default. Calling `clear()` does not change that. Should the
   capture be cut back separately (for example, record the claiming thread name only, and keep
   full stacks behind the property)? This is not proposed here.
4. **`getDeallocationQueue()` visibility.** Change its element type (protected API) or make it
   private? Nothing in the repository overrides or calls it outside `HardwareMemoryProvider`.
   Private is proposed.
5. **Is the inline-release fence needed?** Motivation item 3 is inferred, not observed, and the
   main explicit caller (`MemoryDataAdapter.destroy`) already keeps the RAM reachable for the
   whole call. The fence is harmless and cheap. But if the reports turn out to come only from the
   deferred path, it can be dropped without losing anything this plan measures.
6. **Is the `clear()` after release worth it?** As Approach step 3 explains, a ref that has left
   `allocated` is no longer held by the provider and is not enqueued anyway, so `clear()` closes
   only a window of a few instructions. It is cheap, but it is a second mechanism aimed at the
   same symptom as step 1, and it adds an ordering rule (clear only after the identity-checked
   removal) that a later edit could break. The reviewer may prefer to drop it and rely on step 1.
7. **Is a GC-first deferred release correct, not only noisy?** In the "GC first" ordering the GC
   path frees a block whose explicit release was deferred because a kernel held it. That is safe
   only because the guard has already let go (`deferIfInUse` returned `false`), so no kernel is
   reading it. This plan does not change that decision. It only stops the sweep's later lost
   claim from being reported. If a reviewer suspects the guard's accounting (the `deallocateNow`
   Javadoc notes that a GC-path deferral "would indicate either a race window ... or a bug in the
   guard's accounting"), that is a separate investigation.
8. **Stale `CLMemory` released flag.** `CLMemory.tryClaimReleased()`/`unclaimReleased()` have no
   callers, so `CLMemory.isActive()` and `isReleased()` never report a released buffer (see "What
   `warnDoubleFree` can actually catch today"). A second explicit `deallocate` of a CL buffer is
   therefore reported as "Attempting to deallocate untracked memory", which is the same kind of
   misleading warning this plan removes elsewhere. Fixing it (for example by having
   `CLMemoryProvider.deallocate(NativeRef)` mark the buffer, or by removing the dead methods) is
   outside this plan's scope, which excludes the backend subclasses, and is a candidate for a
   follow-up task.
9. **Do the CI reports match the mechanisms?** The Motivation describes the explicit stack as
   either instruction-cache eviction ending in `MemoryDataAdapter.destroy` → `deallocate(int, T)`
   or `sweepDeferred`. As noted at the end of the Motivation, the code only explains the
   `sweepDeferred` form. Before implementing, take a sample of detailed reports from a recent
   `test-media-cl` run and classify each by the thread and top frames of "First freed at" and
   "Current free attempt at", against the orderings: sweep first (Process Thread
   `sweepDeferred`, then Submit Thread), GC first (Submit Thread, then Process Thread
   `sweepDeferred`), or something else. If any report shows `MemoryDataAdapter.destroy` itself as
   one of the two stacks, there is a path this plan has not identified, and the winner-origin rule
   could silence it or leave it loud without anyone knowing which. The fix is still correct for
   the orderings it covers, but the success criterion on the CI logs should not be relied on until
   the sample has been classified.

## Estimated complexity

Small to medium. The work is mostly one production class (origin plumbing, winner-origin
recording, one fast-path check, `deferred` cleanup, a fence, a `clear()`, Javadoc), a small
addition to `MemoryReference`/`NativeRef`, and one new test class. Case 3 needs a two-thread latch
test, and all cases need the guard seam from Open question 1 to run without special hardware.
