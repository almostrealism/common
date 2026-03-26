# Native Memory GC Lifecycle — Final Review

**Date:** 2026-03-17
**Branch:** `feature/native-memory-gc-lifecycle`
**Reviewer:** Claude Code (automated)

---

## Executive Summary

All four phases address real vulnerabilities in the native memory lifecycle. Phases 1, 3,
and 4 are directly traceable to concrete crash-inducing code paths. Phase 2 is
defensive/forward-looking — it protects against a real code path (`AcceleratedProcessDetails`
async dispatch) but currently all kernel backends are synchronous, meaning the semaphore is
always `null` or already-completed when registered. Phase 2 should be retained as it is
low-risk and protects against a documented vulnerability.

One code-level issue was found: `KernelMemoryGuard.release()` can silently leak map entries
if `resolveRAM()` returns `null` during release (e.g., if the `MemoryData` was explicitly
destroyed between acquire and release). A test has been added to document this behavior.

One unrelated commit (`a3e2aaaab` — CachedStateCell.tick() fix) was made on this branch
but has already been merged to master, so it does not appear in the branch diff.

**Recommendation: Safe to merge**, with the `resolveRAM` leak documented as a known
limitation to address in a follow-up.

---

## Concern 1: Are All Changes Necessary and Traceable?

### Phase 1: `Reference.reachabilityFence()` in Kernel Backends

**Files changed:** `NativeExecution.java`, `CLOperator.java`, `MetalOperator.java`

#### 1. Is there a real, reachable code path?

**Yes.** The code path is:

1. `NativeExecution.accept()` calls `prepareArguments()` → stores result in local `data[]`
2. Lambda captures `data` and submits to `executor` thread pool
3. JIT determines `data` is not used after the loop (the variable is only read inside the
   lambda, but JIT can prove the lambda doesn't escape the method in a way that requires
   the local to stay live)
4. GC collects `MemoryData` objects referenced by `data[]`
5. GC triggers deallocation of backing `RAM` via `NativeRef` → `HardwareMemoryProvider`
6. `NativeInstructionSet.apply()` accesses freed native memory → **SIGSEGV**

This is documented JVM behavior (JLS §12.6.1, JEP 421). The branch history confirms this
was observed in practice: SIGSEGV crashes in `AggregatedComputationTests` after the
PhantomReference migration made GC-triggered deallocation more effective (Memory ID
`1659c964`).

For `CLOperator` and `MetalOperator`, the same pattern applies: `data[]` is a local captured
by a lambda/closure passed to `recordDuration()`. Although these backends block until
completion (`processEvent()` / `run.get()`), the JIT is allowed to determine that `data` is
dead before the blocking call returns.

#### 2. Does the fix close the code path?

**Yes.** `Reference.reachabilityFence(data)` after the synchronization point
(`latch.waitFor()` / `processEvent()` / `run.get()`) is the standard JDK mechanism to
prevent the JIT from allowing early collection. The fence has no runtime cost — it is a
compiler hint only.

#### 3. Are there any non-traceable changes?

**No.** The only changes are the `reachabilityFence` calls and their imports.

---

### Phase 2: Heap Stage Semaphore Tracking

**Files changed:** `Heap.java`, `AcceleratedOperation.java`

#### 1. Is there a real, reachable code path?

**Partially.** The documented vulnerability is:

1. `Heap.stage(Runnable r)` calls `r.run()` which dispatches kernels
2. `finally` block calls `pop()` → `HeapStage.destroy()` → `HeapDependencies.destroy()`
3. `destroy()` calls `MemoryData.destroy()` on all tracked memory
4. Native memory freed while async kernel is still running

The vulnerability is architecturally real — `AcceleratedProcessDetails.checkReady()` has:
```java
if (Hardware.getLocalHardware().isAsync()) {
    executor.execute(this::notifyListeners);  // Async dispatch
}
```

However, **currently all kernel backends return `null` or already-completed semaphores**:
- `CLOperator.accept()` returns `null` (synchronous after `processEvent()`)
- `MetalOperator.accept()` returns `null` (synchronous after `run.get()`)
- `NativeExecution.accept()` returns `latch` but blocks on `latch.waitFor()` first

The `Heap.addPendingKernel(nextSemaphore)` call in `AcceleratedOperation` will always
receive `null`, making the registration a no-op today.

#### 2. Does the fix close the code path?

**Yes, when the code path becomes active.** If a future backend returns a non-completed
semaphore, `HeapStage.destroy()` will correctly block until kernel completion.

#### 3. Are there any non-traceable changes?

**The entire Phase 2 is defensive/forward-looking.** It protects a real architectural
vulnerability but is not triggered by any current code path. It is low-risk (adds an
`ArrayList<Semaphore>` per heap stage and a no-op `addPendingKernel(null)` call per kernel
dispatch) and should be retained.

---

### Phase 3: `KernelMemoryGuard` Reference-Counting Registry

**Files changed:** `KernelMemoryGuard.java` (new), `Hardware.java`, `HardwareMemoryProvider.java`,
`CLOperator.java`, `MetalOperator.java`, `NativeExecution.java`

#### 1. Is there a real, reachable code path?

**Yes.** The code path is:

1. Multiple `PackedCollection` objects share the same underlying `RAM` (via delegation chain)
2. Kernel dispatched against `MemoryData` backed by this `RAM`
3. A different `MemoryData` referencing the same `RAM` goes out of scope
4. GC collects it → `NativeRef` enqueued → deallocation thread calls `deallocateNow()`
5. Without the guard, `deallocateNow()` frees the native memory
6. Kernel still running against freed memory → **SIGSEGV or data corruption**

The guard addresses this by:
- **`acquire()`**: Holds strong references to `RAM` objects in `heldMemory` map, preventing GC
- **`canDeallocate()`**: Checked by `HardwareMemoryProvider.deallocateNow()` before freeing;
  if guard is active, the `NativeRef` is re-queued for later deallocation

This is a distinct vulnerability from Phase 1. Phase 1 prevents the JIT from optimizing away
*local variables*; Phase 3 prevents GC from collecting *shared RAM objects* when one reference
drops while another is still in use by a kernel.

#### 2. Does the fix close the code path?

**Yes, with a caveat.** The guard correctly prevents deallocation during kernel execution.
However, `release()` uses `resolveRAM()` to resolve the native address, and if `resolveRAM()`
returns `null` (e.g., the `MemoryData` was explicitly destroyed between acquire and release),
the reference count is never decremented. This causes:

- The entry in `activeReferences` leaks (count stays > 0 forever)
- The entry in `heldMemory` leaks (holds strong reference to `RAM`, preventing GC)

**Risk assessment:** Low. This can only happen if `MemoryData.destroy()` is called explicitly
while a kernel is actively using it — which is itself a programming error. The guard's strong
reference in `heldMemory` prevents GC-triggered destruction, so this leak only occurs with
explicit `destroy()` calls during kernel execution. The leak is bounded (one map entry per
affected address) and self-corrects when the `Hardware` instance is replaced.

#### 3. Are there any non-traceable changes?

**No.** All changes are part of the guard infrastructure or its integration points.

---

### Phase 4: `WeakReference` → `PhantomReference` Migration

**Files changed:** `MemoryReference.java`, `NativeRef.java`, `NativeBufferMemoryProvider.java`,
`NativeBuffer.java`, `NativeBufferRef.java` (new)

#### 1. Is there a real, reachable code path?

**Yes.** The code path is:

1. `RAM` object goes unreachable
2. With `WeakReference`: `NativeRef` cleared and enqueued **before** finalization
3. Deallocation thread processes `NativeRef` from queue → calls `deallocateNow()`
4. `MemoryDataAdapter.finalize()` runs **after** the native memory is already freed
5. Finalizer accesses freed memory → **undefined behavior**

With `PhantomReference`, the `NativeRef` is only enqueued **after** finalization completes,
eliminating this race.

Additionally, the old `NativeBufferMemoryProvider.deallocate()` called `ref.get()` to access
the `NativeBuffer`. With `PhantomReference`, `get()` always returns `null`. Without
`NativeBufferRef` to cache the needed fields, this would be a `NullPointerException`.

#### 2. Does the fix close the code path?

**Yes.** The PhantomReference ensures deallocation waits for finalization. `NativeBufferRef`
caches `rootBuffer`, `sharedLocation`, and `deallocationListeners` at construction time,
making them available for post-GC cleanup.

**Note:** Deallocation listeners now receive `null` instead of the `NativeBuffer` instance
(since it's been collected). The only production usage
(`CLMemoryProvider.java:246`) ignores the parameter, so this is safe. However, any future
listener that depends on the `NativeBuffer` parameter would receive `null`.

#### 3. Are there any non-traceable changes?

**`NativeBuffer.getRootBuffer()` and `NativeBuffer.getSharedLocation()`** are new public
getters. These are directly required by `NativeBufferRef` to cache fields at construction
time. They are traceable.

---

## Concern 2: Test Coverage Assessment

### Phase 1: `Reference.reachabilityFence()`

#### Is there a test that directly exercises the specific change?

**No, and one cannot be written reliably.** `Reference.reachabilityFence()` prevents a JIT
compiler optimization (early liveness analysis of local variables). This optimization is:
- Non-deterministic (depends on JIT compilation tier, GC timing, heap pressure)
- Platform-specific (varies across JVM implementations and versions)
- Not triggerable on demand in a unit test

The branch history confirms this: the SIGSEGV was observed in `AggregatedComputationTests`
under specific conditions (after PhantomReference migration made GC more aggressive), not
in a repeatable unit test.

#### Assessment

**Acceptable gap.** `Reference.reachabilityFence()` is a standard JDK primitive used in
`java.util.concurrent` itself. Its correctness does not need to be tested — only its
placement. Code review confirms correct placement (after all synchronization points, before
return statements) in all three backends.

---

### Phase 2: Heap Stage Semaphore Tracking

#### Is there a test that directly exercises the specific change?

**Yes.** `HeapStagePendingKernelTest` (8 tests) directly tests:
- `addPendingKernel(null)` is a no-op ✓
- `destroy()` blocks until a pending semaphore completes ✓
- `destroy()` blocks until ALL pending semaphores complete ✓
- Static `Heap.addPendingKernel()` with no active heap is a no-op ✓
- Static `Heap.addPendingKernel()` delegates to active stage ✓
- `destroy()` handles failing semaphores gracefully ✓
- `Heap.stage()` blocks for pending kernels before pop ✓
- Already-completed semaphores do not block ✓

#### Does the test isolate the changed behavior?

**Yes.** Tests use `Heap` directly with `DefaultLatchSemaphore`, without requiring Hardware
initialization or kernel dispatch. Each test would fail if `HeapStage.destroy()` did not wait
for pending semaphores.

#### Missing tests?

The `AcceleratedOperation` integration (the `Heap.addPendingKernel(nextSemaphore)` call) is
not tested in isolation, but this is a single line that delegates to a well-tested API.
Acceptable.

---

### Phase 3: `KernelMemoryGuard`

#### Is there a test that directly exercises the specific change?

**Yes.** `KernelMemoryGuardTest` (12 tests) directly tests:
- Single acquire/release cycle ✓
- Multiple acquires require multiple releases ✓
- Independent address tracking ✓
- Null args array handling ✓
- Null elements in args array ✓
- `NoOpMemoryData` handling ✓
- Non-RAM Memory handling ✓
- Release without prior acquire ✓
- Unknown address is deallocatable ✓
- Exception in `getMem()` handling ✓
- Static helpers with no Hardware ✓
- Concurrent acquire/release safety ✓

#### Does the test isolate the changed behavior?

**Yes.** Tests use stub `MemoryData` and `RAM` implementations, without requiring Hardware
initialization or native memory allocation.

#### Missing tests?

1. **`HardwareMemoryProvider.deallocateNow()` integration**: No test verifies that
   `deallocateNow()` actually re-queues a `NativeRef` when `canDeallocate()` returns false.
   This requires the full hardware stack and is better covered by integration tests.

2. **`resolveRAM` returning null during release**: No test verifies the leak behavior when
   `resolveRAM()` fails during `release()`. A test has been added (see below).

---

### Phase 4: `PhantomReference` Migration

#### Is there a test that directly exercises the specific change?

**Yes.** `MemoryReferencePhantomTest` (7 tests) verifies:
- `MemoryReference` extends `PhantomReference` ✓
- `get()` always returns `null` ✓
- `NativeRef` caches address and size ✓
- Allocation stack trace preserved ✓
- Equality based on cached fields ✓
- Inequality for different addresses ✓

`NativeBufferRefTest` (10 tests) verifies:
- Root buffer cached from `NativeBuffer` ✓
- Shared location cached (null for non-shared) ✓
- Shared location cached (non-null for shared) ✓
- Deallocation listeners captured ✓
- Listeners added after construction not captured ✓
- Cached list is independent copy ✓
- `get()` returns null ✓
- Address and size inherited from `NativeRef` ✓
- Empty listener list handled ✓
- Multiple listeners all captured ✓

#### Does the test isolate the changed behavior?

**Yes.** Both test classes use minimal stub implementations (`TestRAM`, `TestNativeBuffer`)
that avoid native library dependencies. Tests would fail if `MemoryReference` reverted to
`WeakReference` (the `get()` test would return non-null, the `instanceof PhantomReference`
test would fail).

#### Missing tests?

**`NativeBufferMemoryProvider.deallocate()` with `NativeBufferRef`**: No narrow test verifies
that deallocate correctly uses `NativeBufferRef`'s cached fields instead of `ref.get()`.
However, this is a simple method whose behavior follows directly from the `NativeBufferRef`
field-caching tests. Acceptable.

---

## Issues Found

### Issue 1: `KernelMemoryGuard.release()` Map Entry Leak (Low Severity)

If `resolveRAM()` returns `null` during `release()` (e.g., `MemoryData` explicitly destroyed
between acquire and release), the address is never decremented in `activeReferences` and
never removed from `heldMemory`. This is a bounded leak that does not cause crashes.

**Recommendation:** Document as known limitation. Fix in follow-up by caching the resolved
addresses at `acquire()` time (e.g., in a `ThreadLocal<Map<MemoryData, Long>>`) so that
`release()` does not need to re-resolve.

### Issue 2: Deallocation Listeners Receive `null` (No Action Required)

After Phase 4, `NativeBufferMemoryProvider.deallocate()` passes `null` to deallocation
listeners instead of the `NativeBuffer` instance. The only production listener
(`CLMemoryProvider:246`) ignores the parameter. No action needed, but future listener
implementations should be aware.

---

## Final Recommendation

**This branch is safe to merge.** All four phases address real or architecturally-documented
vulnerabilities. The test coverage is adequate — each phase has narrow tests that would fail
if the changes were reverted (except Phase 1, where testing is infeasible due to JIT
nondeterminism, which is acceptable).

The `resolveRAM` leak in `KernelMemoryGuard.release()` is a minor issue that should be
tracked for follow-up but does not block the merge. It only manifests under explicit
`MemoryData.destroy()` during kernel execution (itself a programming error), and the leak
is bounded.

### Merge Checklist

- [x] All changes traceable to documented vulnerabilities
- [x] No speculative cleanups or unrelated changes in branch diff
- [x] Phase 1: Reachability fences correctly placed in all 3 backends
- [x] Phase 2: HeapStage semaphore tracking with 8 narrow tests
- [x] Phase 3: KernelMemoryGuard with 12 narrow tests + concurrency test
- [x] Phase 4: PhantomReference migration with 17 narrow tests
- [x] Deallocation listener null-parameter behavior verified safe
- [ ] (Follow-up) Fix resolveRAM leak in KernelMemoryGuard.release()
