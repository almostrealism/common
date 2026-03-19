# Native Memory GC Lifecycle: Preventing Use-After-Free in Kernel Execution

## Problem Statement

The JVM garbage collector can trigger deallocation of native memory (GPU buffers, JNI-allocated
regions) while native kernel programs are still actively reading from or writing to that memory.
This produces crashes (SIGSEGV), silent data corruption, or `HardwareException` errors.

The root cause is a **lifecycle mismatch**: the GC determines memory liveness based on Java-side
reference reachability, but native kernels hold raw pointers that the GC cannot see. When all
Java references to a `MemoryData` object become unreachable, the GC collects the backing `RAM`
object, which triggers `HardwareMemoryProvider`'s deallocation thread to free the underlying
native memory — even though a kernel is still executing against that memory.

---

## Architecture Summary

### Native Memory Allocation Stack

```
PackedCollection / MemoryDataAdapter  (Java-visible handle)
        │
        ▼
    MemoryData interface               (offset, length, delegation chain)
        │
        ▼
    Memory / RAM                       (native pointer wrapper)
        │
        ▼
    MemoryProvider.allocate()          (backend-specific allocation)
        │
        ├── NativeMemoryProvider → Malloc JNI → C malloc()
        ├── MetalMemoryProvider  → MTLDevice.makeBuffer()
        ├── CLMemoryProvider     → clCreateBuffer()
        └── NativeBufferMemoryProvider → ByteBuffer.allocateDirect()
```

### GC-Triggered Deallocation Pipeline

```
1. RAM object goes unreachable (no strong Java references)
2. GC collects RAM → NativeRef (WeakReference) enqueued to ReferenceQueue
3. "Deallocation Submit Thread" removes NativeRef from queue
4. Either deallocates immediately or queues to PriorityBlockingQueue
5. "Deallocation Process Thread" calls provider.deallocate(ref)
6. Native memory freed (free(), MTLBuffer.release(), clReleaseMemObject())
```

**Key classes:**
- `HardwareMemoryProvider` (`base/hardware/.../mem/HardwareMemoryProvider.java`) — two-thread deallocation, allocation tracking
- `NativeRef` (`base/hardware/.../mem/NativeRef.java`) — WeakReference subclass, caches native address/size
- `MemoryReference` (`base/hardware/.../mem/MemoryReference.java`) — WeakReference base
- `MemoryDataAdapter` (`base/hardware/.../mem/MemoryDataAdapter.java`) — `destroy()` and `finalize()` methods

### Kernel Execution Backends

| Backend | Dispatch | Synchronization | Key Class |
|---------|----------|-----------------|-----------|
| **JNI/C** | Thread pool (`ExecutorService`) | `DefaultLatchSemaphore` (CountDownLatch) | `NativeExecution` |
| **OpenCL** | `clEnqueueNDRangeKernel` | `cl_event` via `processEvent()` | `CLOperator` |
| **Metal** | `MetalCommandRunner.submit()` | `cmdBuf.waitUntilCompleted()` | `MetalOperator` |

All backends currently pass raw native pointers to kernels. None hold strong Java references
to the `MemoryData` arguments during execution.

---

## Root Cause Analysis

### Vulnerability 1: Heap.stage() Destroys Memory Before Kernel Completion

**Files:**
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/Heap.java`

**The pattern:**
```java
// Heap.stage(Runnable) — lines ~956-961
public static void stage(Runnable r) {
    Heap defaultHeap = getDefault();
    if (defaultHeap == null) { r.run(); return; }
    try {
        defaultHeap.push();
        r.run();          // Kernel dispatch happens here (may be async)
    } finally {
        defaultHeap.pop(); // DESTROYS all memory allocated in this stage
    }
}
```

When kernel dispatch is asynchronous (via `Hardware.isAsync()` or thread pool), the `finally`
block calls `pop()` → `HeapStage.destroy()` → `HeapDependencies.destroy()` which calls
`MemoryData.destroy()` on all tracked memory. The native kernel is still running.

**`HeapDependencies.destroy()`** (lines ~860-878):
```java
if (createdMemory != null) {
    createdMemory.forEach(MemoryData::destroy);  // Frees native memory
    createdMemory = null;
}
```

### Vulnerability 2: AcceleratedProcessDetails Async Listener Dispatch

**Files:**
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/AcceleratedProcessDetails.java`
- `base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java`

**The pattern:**
```java
// AcceleratedProcessDetails.checkReady() — lines ~298-310
if (Hardware.getLocalHardware().isAsync()) {
    executor.execute(this::notifyListeners);  // Async dispatch
} else {
    notifyListeners();                         // Sync dispatch
}
```

When async, the listener (which calls `operator.accept(input, null)`) runs on a thread pool
thread. The calling thread may have already exited the `Heap.stage()` scope, destroying the
memory that the listener's kernel will read.

### Vulnerability 3: NativeExecution Thread Pool and Local References

**Files:**
- `base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java`

**The pattern** (lines ~214-259):
```java
public Semaphore accept(Object[] args, Semaphore dependsOn) {
    MemoryData data[] = prepareArguments(argCount, args);  // Local variable

    for (int i = 0; i < p; i++) {
        int id = i;
        executor.submit(() -> {
            inst.apply(getGlobalWorkOffset() + id, getGlobalWorkSize(), data);
        });
    }

    latch.waitFor();  // Blocks, but data[] is a local that could be optimized away
    return latch;
}
```

The `data[]` array is captured by the lambda but is a local variable. The JIT compiler could
determine that `data` is not used after the loop and allow GC to collect the referenced objects
before `latch.waitFor()` returns, especially under aggressive escape analysis.

### Vulnerability 4: WeakReference in NativeRef (Not PhantomReference)

**Files:**
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryReference.java`

Despite the javadoc describing it as a "phantom reference pattern," `MemoryReference` extends
`WeakReference`, not `PhantomReference`. WeakReferences are cleared **before** finalization,
meaning the GC can enqueue the `NativeRef` to the `ReferenceQueue` and trigger deallocation
even if the object's finalizer hasn't run yet. This creates a race with `MemoryDataAdapter.finalize()`.

### Vulnerability 5: ProcessDetailsFactory Creates Temporary Memory Without Kernel Tracking

**Files:**
- `base/hardware/src/main/java/org/almostrealism/hardware/ProcessDetailsFactory.java`

**The pattern** (lines ~478-517):
```java
MemoryData result = (MemoryData) kernelArgEvaluables[i].createDestination(size);
Heap.addCreatedMemory(result);  // Registered with heap stage
asyncEvaluables[i] = kernelArgEvaluables[i].into(result).async(this::execute);
```

Temporary buffers created as kernel argument destinations are tracked only by the Heap's
`createdMemory` list. When the heap stage is popped, these buffers are destroyed regardless
of whether they are still in use by an in-flight kernel.

---

## Secondary Investigation: Why Java References Drop

The references drop because of a fundamental design assumption: **the system was designed
for synchronous kernel execution**. When all kernel execution was synchronous (blocking the
calling thread until completion), the calling thread's stack frames held strong references
to all `MemoryData` arguments for the entire duration. The introduction of:

1. `Hardware.isAsync()` — async listener dispatch
2. Thread pool execution in `NativeExecution`
3. `Heap.stage()` scoped allocation with automatic cleanup
4. `AcceleratedProcessDetails` async argument readiness

...created paths where the calling thread's stack is unwound (references dropped) before
native execution completes.

The protobuf connection: `StateDictionary` loads model weights from `.pb` files into
`PackedCollection` objects via `CollectionEncoder.decode()`. These collections are backed
by native memory. During inference, multiple kernels are dispatched against these weight
tensors. If the `StateDictionary` or its constituent `PackedCollection` references go out
of scope (e.g., a method-local variable), the GC can collect them while kernels are still
running against the underlying native memory.

---

## Proposed Fix Strategy

### Strategy A: Kernel Memory Guard (Primary — Reference-Counting Registry)

Create a `KernelMemoryGuard` that tracks the relationship between active kernel executions
and their associated native memory, preventing deallocation until all kernels complete.

#### Design

```java
/**
 * Tracks active kernel executions and the native memory they reference,
 * preventing GC-triggered deallocation until all kernels using that
 * memory have completed.
 */
public class KernelMemoryGuard {
    // Map from native memory address to active kernel count
    private final ConcurrentHashMap<Long, AtomicInteger> activeReferences;

    // Strong references held to prevent GC
    private final ConcurrentHashMap<Long, Set<RAM>> heldMemory;

    /** Called before kernel dispatch with all argument memory */
    public void acquire(MemoryData... args);

    /** Called after kernel completion (in finally block) */
    public void release(MemoryData... args);

    /** Called by HardwareMemoryProvider before deallocation */
    public boolean canDeallocate(long address);
}
```

#### Files to Create

| File | Description |
|------|-------------|
| `base/hardware/src/main/java/org/almostrealism/hardware/mem/KernelMemoryGuard.java` | Registry implementation |

#### Files to Modify

| File | Change |
|------|--------|
| `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLOperator.java` | Wrap `accept()` with `guard.acquire()`/`guard.release()` |
| `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalOperator.java` | Wrap `accept()` with `guard.acquire()`/`guard.release()` |
| `base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java` | Wrap executor tasks with `guard.acquire()`/`guard.release()` |
| `base/hardware/src/main/java/org/almostrealism/hardware/mem/HardwareMemoryProvider.java` | Check `guard.canDeallocate()` before freeing in `deallocateNow()` |
| `base/hardware/src/main/java/org/almostrealism/hardware/Hardware.java` | Host the singleton `KernelMemoryGuard` instance |

#### Implementation Details

**CLOperator.accept()** — OpenCL backend (currently synchronous dispatch + wait):
```java
@Override
public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
    // ... existing kernel creation ...
    MemoryData data[] = prepareArguments(argCount, args);

    KernelMemoryGuard guard = Hardware.getLocalHardware().getKernelMemoryGuard();
    guard.acquire(data);
    try {
        recordDuration(null, () -> {
            // ... existing kernel arg setup and dispatch ...
            context.processEvent(event, profile);
        });
    } finally {
        guard.release(data);
    }

    return null;
}
```

**NativeExecution.accept()** — JNI backend (thread pool dispatch):
```java
public Semaphore accept(Object[] args, Semaphore dependsOn) {
    MemoryData data[] = prepareArguments(argCount, args);

    KernelMemoryGuard guard = Hardware.getLocalHardware().getKernelMemoryGuard();
    guard.acquire(data);

    DefaultLatchSemaphore latch = new DefaultLatchSemaphore(dependsOn, p);

    recordDuration(latch, () -> {
        for (int i = 0; i < p; i++) {
            int id = i;
            executor.submit(() -> {
                try {
                    inst.apply(getGlobalWorkOffset() + id, getGlobalWorkSize(), data);
                } finally {
                    if (id == p - 1) {
                        guard.release(data);  // Release on last task completion
                    }
                    latch.countDown();
                }
            });
        }
        latch.waitFor();
    });

    return latch;
}
```

**HardwareMemoryProvider.deallocateNow()** — Guard check:
```java
private void deallocateNow(NativeRef<T> ref) {
    KernelMemoryGuard guard = Hardware.getLocalHardware().getKernelMemoryGuard();
    if (guard != null && !guard.canDeallocate(ref.getAddress())) {
        // Re-queue for later deallocation
        getDeallocationQueue().put(ref);
        return;
    }

    deallocate(ref);
    if (!destroying) {
        allocated.remove(ref.getAddress());
    }
}
```

### Strategy B: Heap Stage Semaphore Tracking (Complementary)

Prevent `Heap.pop()` from destroying a stage until all kernels dispatched within that stage
have completed.

#### Files to Modify

| File | Change |
|------|--------|
| `base/hardware/src/main/java/org/almostrealism/hardware/mem/Heap.java` | Add `Semaphore` tracking to `HeapStage`, wait in `pop()` |
| `base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java` | Register kernel semaphore with active heap stage |

#### Implementation Details

**Heap.HeapStage** — Track kernel semaphores:
```java
class HeapStage {
    private List<Semaphore> pendingKernels = new ArrayList<>();

    void addPendingKernel(Semaphore sem) {
        if (sem != null) pendingKernels.add(sem);
    }

    void destroy() {
        // Wait for all kernels dispatched in this stage
        for (Semaphore sem : pendingKernels) {
            sem.waitFor();
        }
        pendingKernels.clear();

        // ... existing destroy logic ...
    }
}
```

**AcceleratedOperation.apply()** — Register semaphore:
```java
process.whenReady(() -> {
    MemoryData input[] = process.getArguments(MemoryData[]::new);
    Execution operator = setupOperator(process);

    Semaphore nextSemaphore = operator.accept(input, null);

    // Track kernel completion in active heap stage
    Heap.addPendingKernel(nextSemaphore);

    if (nextSemaphore != null) {
        nextSemaphore.waitFor();
    }
});
```

### Strategy C: Extend Java-Side Reference Lifetimes (Simplest)

Ensure `MemoryData` references are held in strong variables that span the full kernel
execution lifetime, preventing GC from collecting the backing `RAM` objects.

#### Files to Modify

| File | Change |
|------|--------|
| `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLOperator.java` | Store `data[]` as instance field during execution |
| `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalOperator.java` | Store `data[]` as instance field during execution |
| `base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java` | Use `Reference.reachabilityFence()` after `latch.waitFor()` |

#### Implementation Details

**NativeExecution.accept()** — Use reachability fence:
```java
public Semaphore accept(Object[] args, Semaphore dependsOn) {
    MemoryData data[] = prepareArguments(argCount, args);

    // ... dispatch to thread pool ...

    latch.waitFor();

    // Prevent GC from collecting data[] before this point
    Reference.reachabilityFence(data);
    Reference.reachabilityFence(args);

    return latch;
}
```

---

## Recommended Implementation Order

### Phase 1: Immediate Safety (Strategy C)

Add `Reference.reachabilityFence()` calls in all three backends. This is the simplest change,
requires no new classes, and prevents the JIT from optimizing away references prematurely.

**Estimated scope:** 3 files, ~10 lines each.

**Files:**
1. `base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java`
2. `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLOperator.java`
3. `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalOperator.java`

### Phase 2: Heap Stage Protection (Strategy B)

Add semaphore tracking to `Heap.HeapStage` to prevent premature `pop()` when async kernels
are in flight. This addresses the `Heap.stage()` vulnerability specifically.

**Estimated scope:** 2 files, ~30 lines each.

**Files:**
1. `base/hardware/src/main/java/org/almostrealism/hardware/mem/Heap.java`
2. `base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java`

### Phase 3: KernelMemoryGuard Registry (Strategy A)

Implement the full reference-counting guard as a defense-in-depth layer. This handles
edge cases where memory is freed via explicit `destroy()` calls (not GC) while kernels
are running, and protects against scenarios where references drop outside the Heap pattern.

**Estimated scope:** 1 new file, 5 modified files, ~200 total lines.

**Files:**
1. **New:** `base/hardware/src/main/java/org/almostrealism/hardware/mem/KernelMemoryGuard.java`
2. `base/hardware/src/main/java/org/almostrealism/hardware/Hardware.java`
3. `base/hardware/src/main/java/org/almostrealism/hardware/mem/HardwareMemoryProvider.java`
4. `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLOperator.java`
5. `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalOperator.java`
6. `base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java`

### Phase 4: WeakReference → PhantomReference Migration

Change `MemoryReference` from extending `WeakReference` to extending `PhantomReference`.
PhantomReferences are only enqueued after finalization, providing an additional safety window.

**Estimated scope:** 1 file, ~5 lines.

**File:**
1. `base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryReference.java`

**Risk:** Must verify no code depends on `MemoryReference.get()` returning non-null before
the object is collected (PhantomReference.get() always returns null).

---

## Testing Strategy

### Unit Tests

1. **KernelMemoryGuardTest** — Verify acquire/release ref-counting, verify `canDeallocate()`
   returns false while kernels are active
2. **HeapStageKernelTrackingTest** — Verify `pop()` blocks until tracked semaphores complete
3. **ReachabilityFenceTest** — Verify `MemoryData` is not collected during synchronous
   kernel execution (may require targeted `System.gc()` calls and weak reference probes)

### Integration Tests

1. **AsyncKernelMemoryLifecycleTest** — Run kernels in async mode within `Heap.stage()`,
   verify memory is not freed before kernel completion
2. **StateDictionaryInferenceTest** — Load model weights, dispatch inference kernels,
   drop `StateDictionary` reference, verify kernels complete without crash
3. **ProtobufLoadAndComputeTest** — Load embeddings from protobuf, compute similarity
   scores, verify no SIGSEGV under GC pressure

### Stress Tests

1. Run with `-XX:+UseG1GC -XX:MaxGCPauseMillis=10` to increase GC frequency
2. Run with `-XX:+UnlockExperimentalVMOptions -XX:+G1TraceReclaimDeadHumongousObjectsAtYoungGC` for visibility
3. Use `AR_HARDWARE_MEMORY_SCALE=3` (~2GB with FP32) to force more allocation pressure

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| KernelMemoryGuard adds contention | Medium | Low | Use ConcurrentHashMap, avoid locks |
| Memory not released if kernel hangs | Low | High | Add timeout to guard with logging |
| HeapStage.destroy() deadlock | Low | High | Add timeout to semaphore waits |
| PhantomReference migration breaks code | Low | Medium | Audit all MemoryReference.get() calls |
| Reachability fence JIT-specific behavior | Low | Low | Only a first-line defense, backed by guard |

---

## File Reference Summary

### Core Memory Management
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/HardwareMemoryProvider.java` — GC deallocation pipeline
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/NativeRef.java` — WeakReference to native memory
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryReference.java` — WeakReference base class
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryDataAdapter.java` — destroy(), finalize()
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/RAM.java` — Native pointer wrapper
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/Heap.java` — Arena allocator, stage/pop lifecycle

### Kernel Execution
- `base/hardware/src/main/java/org/almostrealism/hardware/HardwareOperator.java` — Base operator, argument preparation
- `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLOperator.java` — OpenCL dispatch
- `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalOperator.java` — Metal dispatch
- `base/hardware/src/main/java/org/almostrealism/hardware/jni/NativeExecution.java` — JNI thread pool dispatch

### Async Coordination
- `base/hardware/src/main/java/org/almostrealism/hardware/mem/AcceleratedProcessDetails.java` — Async listener dispatch
- `base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java` — Kernel evaluation flow
- `base/hardware/src/main/java/org/almostrealism/hardware/ProcessDetailsFactory.java` — Temporary buffer creation

### Native Providers
- `base/hardware/src/main/java/org/almostrealism/c/NativeMemoryProvider.java` — JNI malloc/free
- `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalMemoryProvider.java` — Metal GPU memory
- `base/hardware/src/main/java/org/almostrealism/hardware/cl/CLMemoryProvider.java` — OpenCL GPU memory

### Protobuf / Embedding Storage
- `engine/ml/src/main/java/org/almostrealism/ml/StateDictionary.java` — Model weight management
- `engine/ml/src/main/java/org/almostrealism/persistence/CollectionEncoder.java` — Protobuf ↔ PackedCollection
- `engine/ml/src/main/proto/collections.proto` — Tensor serialization format
