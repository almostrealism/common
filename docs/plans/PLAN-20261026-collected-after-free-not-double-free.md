---
status: proposed
created: 2026-10-26
closed:
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

- **Noise.** A detailed report prints three stack traces.
- **Real double frees get hidden.** Detail is limited by the static, never-reset
  `doubleFreeAttempts` counter to `DOUBLE_FREE_DETAIL_LIMIT` (64) per JVM. Benign races use up that
  budget, so a real ownership bug later in the same JVM shows up only as a periodic count with no
  stacks. An earlier change added this throttle to stop a CI timeout, but never traced where the
  warnings came from.

The ref is enqueued after an explicit free because a `PhantomReference` is enqueued only while
the ref object itself is still strongly reachable (held in `allocated` or `deferred`):

1. **Deferred release.** The owner drops its memory while a kernel still holds the block.
   `deferIfInUse` records a `DeferredRelease` and the GC enqueues the ref. `sweepDeferred` frees
   it, and then the submit thread tries again. This follows directly from the code.
2. **Inline release (inferred).** In `deallocateNow(T mem)`, `mem` is not used after
   `getNativeRef(mem)`, so the JIT may treat it as unreachable while the native release is still
   running.

Neither path clears the `NativeRef` after a successful explicit free, and the queue thread never
checks `isFreed()`.

## Scope

All production changes are in `base/hardware`, in
`org.almostrealism.hardware.mem.HardwareMemoryProvider`. Tests go in
`base/hardware/src/test/java/org/almostrealism/hardware/mem/`.

In scope:

- The GC (reference-queue) path silently drops a ref that has already been claimed. This is the
  expected outcome after an explicit free, not a defect.
- A second **explicit** release of an already-claimed ref still reaches `warnDoubleFree` and
  keeps its detailed report.
- The GC path no longer uses up the double-free detail budget.
- Close the inline-release window with `Reference.reachabilityFence`, and `clear()` the ref after
  a successful explicit release so the GC does not enqueue it in most cases.
- Regression tests for both behaviours.
- Bring the class Javadoc ("Two-Thread Deallocation Architecture") and the `warnDoubleFree`
  Javadoc in line with the new behaviour.

Out of scope:

- The cost of `MemoryReference.captureFreeStackTrace`, which captures a stack trace on every
  successful claim. That is a separate tuning question (see Open questions).
- The `DoubleFreeReport` thresholds and the static counter itself. They stay as they are. They
  only stop being consumed by events that are not double frees.
- The JUnit timeout / uninterruptible `CLProgram.compile` join mentioned in the task's "Related"
  section.
- The backend subclasses (`CLMemoryProvider`, `MetalMemoryProvider`, `CudaMemoryProvider`,
  `NativeMemoryProvider`). They only implement `deallocate(NativeRef)` and `nativeRef(T)`, and
  neither changes.

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
- `releaseNow(ref, origin)`: when `tryClaimFreed()` loses, call `warnDoubleFree(ref)` only for
  `EXPLICIT`, and return silently for `COLLECTED`. The `isFreed()` check above is only a fast
  path. This branch is what makes the race (claim lost between the check and the claim) silent.
- `DeferredRelease` keeps the origin of the request that created it. `sweepDeferred` passes that
  origin to `releaseNow`. A deferred explicit release that later loses its claim is still a real
  double free. A deferred collected release that loses to an explicit one is not.
  `computeIfAbsent` keeps the first request's origin. That is acceptable: either way the block is
  freed once, and only the losing claim decides whether to report.

### 2. Queued mode (`queueDeallocation = true`)

The deallocation queue currently holds bare `NativeRef`s, filled by both the submit thread and
the explicit `deallocate(int, T)`. A GC ref queued *before* an explicit release would be reported
when the process thread takes it. Change the element type to a small private record
(`PendingRelease(NativeRef<T> ref, ReleaseOrigin origin)`), still ordered by `ref.getSize()`
descending, so the process thread passes the origin through. The submit thread also skips refs
that are already `isFreed()` before queuing them. `getDeallocationQueue()` is `protected`, but no
subclass in the repository calls it (checked with grep across `base/hardware`), so its type can
change, or it can become private.

### 3. Close the windows instead of only hiding them

- In `deallocateNow(T mem)` (explicit path), wrap the release in `try { ... } finally {
  Reference.reachabilityFence(mem); }` so `mem` stays strongly reachable until the native release
  and the `allocated` removal have finished (window 2).
- In `releaseNow`, after a successful `EXPLICIT` release (the `released == true` branch), call
  `ref.clear()`. Clearing a `PhantomReference` before the GC finds it prevents enqueueing, so in
  most cases the submit thread never sees the ref. This is safe: the native release uses only
  cached fields (address, size and per-backend handles such as `CLMemoryRef.mem`), never the
  referent, and `getNativeRef` returning `null` for a cleared ref matches `isReleased` already
  reporting the block as released. A ref the GC has already enqueued is unaffected by `clear()`,
  which is why step 1 is still needed.

### 4. Tests (base/hardware, plain JUnit, `@Test(timeout = ...)`)

`base/hardware` sits below the engine layer, so the tests follow `HardwareMemoryProviderLeaseTest`:
plain JUnit, not `TestSuiteBase`. A new test class (e.g. `HardwareMemoryProviderDoubleFreeTest` in
`org.almostrealism.hardware.mem`) uses a minimal provider modelled on the one in
`engine/utils/.../AddressReuseDuringReleaseTest`. Its `deallocate(NativeRef)` counts releases and
records each released ref, and a fake `RAM` gives each instance its own address.

GC enqueueing is simulated deterministically with `Reference.enqueue()` on the tracked
`NativeRef`, which puts it on the provider's reference queue exactly as the GC would. The test
then waits for the submit thread with a sentinel: enqueue a second, unfreed ref and block until
the provider records its release. The queue is FIFO and the submit thread is single-threaded, so
the first ref has been handled by then.

Observability:

- Add a package-private static accessor for the double-free attempt count (`doubleFreeAttempts`)
  so tests can assert the counter's *delta*. That shows directly whether the detail budget was
  used.
- Capture warnings by overriding `console()` in the test provider to return a child `Console`
  with a listener (`Console.addListener`). This also keeps logging from touching
  `Hardware.console`.

Cases:

1. **Explicit free, then GC enqueue:** `deallocate(size, ram)` → `ref.enqueue()` → sentinel.
   Expect exactly one native release, a counter delta of 0, and no "Skipping double deallocate"
   line.
2. **Deferred explicit release completed by the sweep, then GC enqueue:** this uses the
   `KernelMemoryGuard` deferral (see the open question on the guard seam). Expect a delta of 0 and
   one release.
3. **Two explicit frees of the same ref:** `deallocate` twice on the same `RAM`. Expect one native
   release, a counter delta of 1, and a captured "Skipping double deallocate of ..." line
   followed by "First freed at:". This is the existing, still-loud behaviour.
4. **GC enqueue of an unfreed ref:** the native release happens once and nothing is reported.
   This guards against the new check swallowing real GC releases.
5. **Queued mode:** with `queueDeallocation = true` (restored in `finally`), a GC ref queued
   before an explicit release is released once and nothing is reported.

`HardwareMemoryProviderLeaseTest` and `engine/utils` `AddressReuseDuringReleaseTest`,
`MemoryReclaimTest` and `DestinationReuseTest` also exercise this class and must keep passing.

## Success criteria

- In `HardwareMemoryProvider`, the GC path never calls `warnDoubleFree`. An explicit second free
  still does.
- New base/hardware tests: case 1 shows no warning and no counter consumption after an explicit
  free followed by GC enqueue. Case 3 shows the detailed warning for two explicit frees. Cases 2,
  4 and 5 pass.
- Existing tests above pass, run through `ar-test-runner` with bounded `test_classes`.
- The build validator (checkstyle, code_policy, test_timeouts, duplicate_code) passes.
- After merge, the `test-media-cl` CI shards show no "Skipping double deallocate" report whose
  "Current free attempt at" stack is the Deallocation Submit Thread. Only CI can verify this.
  Reviewers should check the first CL media run after merge.

## Dependencies

None. The task has no `blocked_by` tasks, and everything it touches is already on master.

## Open questions

1. **Guard seam for tests.** `deferIfInUse` and `sweepDeferred` reach `KernelMemoryGuard` through
   `Hardware.getLocalHardware()`. Calling that from a base/hardware unit test triggers `Hardware`'s
   static initialization, which picks and starts a real backend. No existing test in
   `base/hardware/src/test/.../mem` does that. Options:
   (a) make the private `kernelMemoryGuard()` a `protected` hook, so a test provider can supply its
   own `KernelMemoryGuard` (or `null`) and case 2 can drive a deferral without real hardware; or
   (b) put the GC-path cases in `engine/utils` next to `AddressReuseDuringReleaseTest`, which
   already exercises the release path with `Hardware` initialized.
   The task asks for base/hardware, so (a) is proposed. The reviewer should confirm that the hook
   is acceptable.
2. **`captureFreeStackTrace` cost.** Each successful claim still captures a stack trace while
   detection is on, which is the default. Calling `clear()` does not change that. Should the
   capture be cut back separately (for example, record the claiming thread name only, and keep
   full stacks behind the property)? This is not proposed here.
3. **`getDeallocationQueue()` visibility.** Change its element type (protected API) or make it
   private? Nothing in the repository overrides or calls it. Private is proposed.

## Estimated complexity

Small. The work is one production class (origin plumbing, one fast-path check, a fence, a
`clear()`, Javadoc) and one new test class, and it needs no special hardware.
