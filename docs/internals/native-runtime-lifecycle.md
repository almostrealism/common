# Native Runtime Lifecycle: Generated Kernels, Off-Heap Memory, and Reentrancy

The compilation-pipeline docs describe the *front half* of computation — how a Producer graph
becomes a Process tree ([computation-graph-to-process-tree.md](computation-graph-to-process-tree.md)),
how it is optimized ([process-optimization-pipeline.md](process-optimization-pipeline.md)), and how
it is compiled and dispatched ([backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md)).

This page documents the *back half*: what happens to a compiled kernel and the memory it reads
**after** compilation — where the artifact lives, who owns the memory it touches, when that memory
is released, and whether a compiled kernel is safe to run from more than one thread. It is written
as the questions an investigator actually asks, most often while triaging a native crash whose Java
stack ends in a `GeneratedOperationN.apply` JNI call.

Every claim below names the class (and, where useful, the method) it was verified against. No line
numbers are cited — they rot; search by the stable identifier instead.

---

## 1. Generated kernel artifacts

### Where the library is written

The JNI backend compiles each generated kernel to a shared library on disk. `NativeCompiler`
writes the C source and the compiled library into a single directory returned by
`getLibraryDirectory()`. That directory comes from `AR_HARDWARE_LIBS` when set; otherwise
`NativeCompiler.factory(...)` defaults it to `SystemUtils.getExtensionsPath()`. Do **not** set
`AR_HARDWARE_LIBS` by hand — the default is auto-detected and a hand-set path causes permission
errors on shared or sandboxed systems.

The filename is formed by `getOutputFile(name, true)`, which substitutes the class name into the
`AR_HARDWARE_LIB_FORMAT` template (`NativeCompiler.LIB_NAME_REPLACE`). When that variable is unset,
`NativeCompiler.factory(...)` picks the default purely by CPU architecture — `lib%NAME%.dylib` when
`SystemUtils.isAarch64()` is true, `lib%NAME%.so` otherwise — not by operating system. So aarch64
Linux uses the `.dylib` suffix and x86_64 macOS uses `.so`; the suffix tracks the architecture, not
the platform. The `name` is the fully-qualified class name of the reserved target, e.g.
`org.almostrealism.generated.GeneratedOperation9`.

### What a `GeneratedOperationN` is

`NativeCompiler.reserveLibraryTarget()` loads a pre-generated class named `GeneratedOperationN` (N
from a JVM-wide counter, `reserveTargetIndex()`) by reflection and returns it as a
`BaseGeneratedOperation`. These classes are **reservation slots** — empty Java stubs declaring the
`native` `apply` method — not stored kernels. The compiled artifact behind each slot is generated
fresh on each run; the slot only supplies a stable class name for JNI linkage. When the pool of
pre-generated slots is exhausted, `reserveLibraryTarget()` throws `OperatorPoolExhaustedException`.

### Is a library reused across JVM runs?

No. There is no cross-run reuse, and — this is the part investigators get wrong — **there is also no
delete-on-start**. `NativeCompiler` has no code that clears the library directory at startup;
`destroy()` is a no-op. Instead, the files are *overwritten before load*:

- The reservation counter (`reserveTargetIndex()`) restarts at `0` every JVM, so a fresh run hands
  out the same `GeneratedOperation0`, `GeneratedOperation1`, … names in the same order.
- `NativeCompiler.compile(name, code, lib)` opens the deterministic source path with a
  `FileOutputStream` (truncating) and writes the freshly generated C, then the toolchain writes the
  library to the deterministic library path, and only then does `compileAndLoad` call
  `System.load(...)`.

So a library file from a *previous, already-finished* run persists on disk but can never be *loaded*
by a later run: the later run regenerates and overwrites it at the same path before loading. **A
"stale dylib from an older build" cannot be the cause of a kernel-side crash, and clearing the
library directory is a no-op as a diagnostic.** (The one thing a run consumes from the previous run
is nothing at all — the `.c`/library pair is rewritten from the current graph.)

This overwrite-before-load guarantee is **sequential-run only**. `reserveTargetIndex()` is a
JVM-local counter (`static synchronized`, restarting at `0` each JVM) and there is no inter-process
lock — no `FileLock`, no lockfile — on the library directory. Two JVMs that share one library
directory therefore hand out the *same* `GeneratedOperationN` names in the same order and write to the
same `.c` and library paths concurrently: one run can truncate or overwrite the source or library
another run is mid-compile or mid-`System.load` on, and each can load the other's bytes.

Crucially, **the default directory is shared, not per-process.** When `AR_HARDWARE_LIBS` is unset the
directory is `SystemUtils.getExtensionsPath()` (`getCachesPath()/Extensions`) — a fixed per-user
cache path, the same for every JVM that user launches on the machine. So concurrent JVMs run by the
same user race on this directory *by default*; the race is not confined to a hand-set shared
`AR_HARDWARE_LIBS`. Running more than one JVM that uses the native backend concurrently on one machine
is unsupported for this reason. When triaging a crash, this cross-run race is the one case where the
on-disk artifact *can* be wrong for the loading run — but only when another JVM was writing the same
directory at the same time; a strictly sequential single-JVM history is still immune (the
overwrite-before-load argument above holds).

### Metal and OpenCL program lifetime

The Metal and OpenCL backends do not go through `NativeCompiler`; their programs are built in-memory
per JVM (compiled `MTLComputePipelineState` / `cl_program` objects), not written to `AR_HARDWARE_LIBS`.
In-JVM reuse of already-compiled instruction sets — for every backend — is a separate concern handled
by the instruction-set caching layer; see
[base/hardware/docs/INSTRUCTION_CACHING.md](../../base/hardware/docs/INSTRUCTION_CACHING.md). That
cache lives and dies with the JVM as well.

---

## 2. Off-heap memory budget

### The formula and what it bounds

`Hardware` derives a memory ceiling from `AR_HARDWARE_MEMORY_SCALE` (field `MEMORY_SCALE`, default
`4`). The base reservation it computes in its constructor is

```
maxReservation = 2^MEMORY_SCALE * 64 * 1000 * 1000        // element-scale units
```

This element-scale figure (exposed by `getMemoryScale()` / `HardwareDataContext.getMaxReservation()`)
is **not** the byte ceiling. Each data context converts it to a byte ceiling by multiplying by the
active precision's byte width before handing it to its memory provider:

- `MetalDataContext.start()` → `getMaxReservation() * getPrecision().bytes()`
- `NativeDataContext.getMemoryProvider()` → `getMaxReservation() * getPrecision().bytes()`
- `CLDataContext` → `maxReservation * getPrecision().bytes()`

So the effective per-provider ceiling in **bytes** is

```
precision.bytes() * 2^MEMORY_SCALE * 64MB    (≈ 4 GB at the FP32 default of 4)
```

This is why `Hardware`'s class-level Javadoc states the ceiling with the `precision.bytes()` factor
while the `MEMORY_SCALE` field describes the pre-scaled element figure: they describe two different
quantities on the same computation, not a contradiction.

### Which providers enforce it, and the failure mode

All three memory providers guard the byte ceiling:

| Provider | Method | Exception message |
|---|---|---|
| `MetalMemoryProvider` | `buffer` | `HardwareException: "Memory Max Reached"` |
| `CLMemoryProvider` | `buffer` | `HardwareException: "Memory Max Reached"` |
| `NativeMemoryProvider` | `allocate` | `HardwareException: "Memory max reached"` |

Each performs the same **pre-allocation check** —
`if (memoryUsed + requested > memoryMax) throw new HardwareException(...)`, then increments
`memoryUsed` — before the backend allocation call. Treat it as a guard against a single over-budget
request, not a hard serialized ceiling: only `NativeMemoryProvider.allocate` is `synchronized`, so
its check-and-increment is atomic. `CLMemoryProvider.buffer` and `MetalMemoryProvider.buffer` run the
check and the `memoryUsed` increment without synchronization, so two concurrent allocations can each
pass the check and push the total past `memoryMax`.

Distinguish two separate failure modes, because they surface differently:

- **The tracked ceiling rejects the request.** When the pre-allocation check trips, every provider
  throws a `HardwareException` rather than fabricating a pointer — it never returns a zero-valued
  pointer that would propagate into a kernel. This is the failure the `AR_HARDWARE_MEMORY_SCALE`
  triage below is about.
- **A raw backend allocation fails after passing the check.** The tracked ceiling is not the OS
  limit. `NativeMemoryProvider`'s calloc path allocates through JNI `Malloc.apply`, which is
  implemented with `calloc` and returns `0` when the OS allocation fails; `allocate` wraps that
  return in a `NativeMemory` **without checking it against zero**. So a request that passes the
  `memoryMax` check can still yield a zero content pointer when the OS itself is out of memory. That
  zero is caught downstream at dispatch — `NativeInstructionSet.apply` validates every content
  pointer against zero before the JNI call and throws a `NullPointerException` naming the argument
  (see §5) — but it is a genuine zero pointer produced by the allocator, not something the tracked
  ceiling would have thrown on.

When you see `HardwareException: Memory max
reached`, raise `AR_HARDWARE_MEMORY_SCALE` (exponential — increase by one step at a time) or reduce
the working set; see the [hardware README](../../base/hardware/README.md) Memory Configuration
section.

---

## 3. GC-driven release

Off-heap memory is released **per object**, driven by the JVM garbage collector — not by a
bytes-used budget. `HardwareMemoryProvider` registers each allocation's backing `RAM` with a
`java.lang.ref.PhantomReference` (`NativeRef`) on a `ReferenceQueue`. A *submit* thread blocks on
`referenceQueue.remove()` until the GC enqueues a collected reference. What happens next depends on
the static `HardwareMemoryProvider.queueDeallocation` switch, which is **`false` by default**: with
the default, the submit thread calls `deallocateNow(ref)` directly and performs the native free
itself; only when `queueDeallocation` is enabled does it hand the reference to a size-ordered
`PriorityBlockingQueue` that a second *process* thread drains, freeing the largest allocations first.
Either way, the backing `RAM` of a collection whose holder becomes unreachable may have its native
block freed at any subsequent GC cycle.

The phantom-queue *free* applies where the provider owns the native bytes: the JNI-calloc path of
`NativeMemoryProvider`, `CLMemoryProvider` (OpenCL), and `MetalMemoryProvider` (Metal). The one
exception is `NativeMemoryProvider`'s **NIO direct-buffer** mode (`isDirect()`), where the bytes are
a JVM `DirectByteBuffer` and are freed by the JVM's own direct-buffer cleaner when that buffer is
collected. There the provider's phantom reference is a `NativeBufferRef` whose post-GC work is only
to unmap shared memory and notify deallocation listeners — it does not free the direct-buffer bytes,
because it does not own them.

`MemoryDataAdapter.enableFinalizer` is `false` by default; the finalizer, when enabled, only reports
leaked allocations — it is not part of the release path. Deterministic release is the caller's job
via `Destroyable`/try-with-resources (see the `MemoryData` lifecycle contract and the hardware
README's [GC-Integrated Native Memory](../../base/hardware/README.md) section — link, not repeated
here).

### The race `KernelMemoryGuard` closes, and the ones it does not

The dangerous interaction is: a kernel has been dispatched and is reading a native block, and the GC
concurrently decides the block's Java holder is unreachable and frees it — a use-after-free.
`KernelMemoryGuard` closes this race for *bracketed dispatches*. Each backend operator
(`NativeExecution`, `CLOperator`, `MetalOperator`) calls `KernelMemoryGuard.acquireFor(data)` before
dispatch and `releaseFor(reservation)` after completion. Acquisition ref-counts each argument's
native address and holds a strong reference to the resolved `RAM`, so the block cannot be collected
while the kernel runs; `HardwareMemoryProvider` consults `canDeallocate(address)` and holds the free
back while the count is non-zero. The reservation records *addresses*, not arguments, precisely
because an argument may be destroyed mid-flight and can then name no address at all. As a second,
independent layer, the same operators call `Reference.reachabilityFence(data)`/`(args)` after
dispatch to stop the JIT from treating the holders as dead before the native call returns.

The hold-back is **not indefinite.** `HardwareMemoryProvider.sweepDeferred()` runs on the
deallocation-process thread (every `DEFERRED_SWEEP_INTERVAL_MS`, regardless of the
`queueDeallocation` switch) and force-frees any deferred block whose wait has exceeded
`deferredReleaseTimeoutMs` (`30_000` ms by default) **even if its guard count is still non-zero**,
logging `warnIfActivelyReferenced` with the allocation trace. This is a backstop against a dispatch
that died without calling `releaseFor` — a never-returned count would otherwise pin the block for the
life of the process. In normal operation a kernel finishes in milliseconds, so the timeout never
fires; a run that logs it is reporting a leaked or stuck dispatch, not doing routine work. The triage
consequence: if a dispatch genuinely hangs or leaks its reservation past 30 s, the guard will free
its memory out from under it, so a use-after-free is **not** ruled out merely by confirming the
dispatch was bracketed — check whether the timeout fired.

`KernelMemoryGuard` does **not**:

- **Protect an argument it cannot resolve to a `RAM`.** `acquire` warns and skips such an argument;
  a kernel using it is unguarded, because every downstream check is keyed by the address resolution
  would have produced.
- **Turn `canDeallocate` into a hard barrier on every path.** `warnIfActivelyReferenced(...)` is
  diagnostic only — it never throws or blocks; a caller that frees anyway is not stopped.
- **Guard a dispatch that was never bracketed.** It is defense-in-depth around the standard
  operators, not a global invariant on all native memory.

---

## 4. Reentrancy of generated kernels

This guarantee is **specific to the JNI generated-kernel path** (`NativeInstructionSet` /
`GeneratedOperationN`): **one compiled JNI kernel instance is safe to invoke concurrently.** This is
not merely permitted — the framework relies on it. It is not a blanket claim about every backend
operator; the OpenCL and Metal operators are structured differently (see the note below).

- **The Java side holds no per-call mutable state.** `NativeInstructionSet.apply(long, long,
  MemoryData...)` builds fresh local `pointers`/`offsets`/`sizes` arrays on every call. The only
  instance fields (`context`, `metadata`, `parallelism` on `BaseGeneratedOperation`) are set once at
  setup and read, not mutated, during dispatch.
- **The generated C side has no shared mutable state.** Every generated `apply` receives all of its
  state through arguments (the pointer array, offsets, sizes, count, global id, kernel size). The
  only file-scope value `NativeCompiler` prepends is `M_PI_F` (a π value; a writable file-scope
  declaration that the generated code only reads, never assigns), so there is no scratch buffer or
  static state captured between calls.
- **The framework already dispatches one instance from many threads.** When `getParallelism() > 1`,
  `NativeExecution.coordinate(...)` submits the *same* instruction-set instance to a thread pool and
  each worker invokes `apply` concurrently, over disjoint index ranges (`globalId + i`).

The caveat is the ordinary one for shared memory: concurrency is safe as long as the *argument
memory* the concurrent invocations touch does not alias in a conflicting way (two writers to the
same element). That is a property of the arguments, not of the kernel instance. This is why
`NativeInstructionSet`'s Thread Safety contract can answer the reentrancy question positively for the
kernels the platform actually generates, rather than deferring it.

**The OpenCL and Metal operators do not share this same-instance concurrency property**, because
they hold and mutate per-instance state rather than routing everything through call-local arrays:

- `CLOperator` caches the last-set arguments in an `argCache` field and skips redundant
  `clSetKernelArg` calls, so a call both reads and mutates instance state and mutates the underlying
  `cl_kernel`. Its `accept(...)` is `synchronized`, and instances are deliberately **thread-local**
  (`CLInstructionsManager`), so a single instance is never shared across threads in the first place.
- `MetalOperator.accept(...)` is `synchronized`, serializing concurrent calls on one instance; it
  inlines the per-dispatch offset/size arrays into the command so that batched commands do not share
  mutable argument buffers.

So the reusable, lock-free reentrancy guarantee is a property of the JNI generated kernel, not of
every backend's operator instance.

---

## 5. Value semantics of generated code

Kernel arguments are pointers to memory owned **outside** the kernel and passed in across the JNI
boundary. The expression/scope layer that generates kernel bodies produces **numeric** assignments
only: it has no construct that assigns a null or zero *pointer*. Assigning `0.0` writes a numeric
value into a value slot; it is not a pointer-erase, and there is no IR node for "set this pointer to
null."

The consequence for crash triage is decisive. A `0x0` dereference inside a generated kernel cannot
have been produced by the kernel zeroing one of its own pointers — that operation does not exist.
It must be one of:

1. **A pointer argument was already `0` at the JNI boundary** — usually a bug in the Java caller
   (memory released or unmapped before dispatch), but also possibly a raw OS-level allocation failure
   that the tracked ceiling did not catch (§2: `Malloc.apply` returns `0` and `NativeMemoryProvider`
   wraps it unchecked). `NativeInstructionSet.apply(long commandQueue, RAM[]...)`
   guards against exactly this: it checks every extracted content pointer against zero *before*
   dispatch and throws a `NullPointerException` naming the kernel and the argument index, converting
   a silent `SIGSEGV` deep in native code into a diagnosable Java exception.
2. **An arithmetic result of `0`** — e.g. an out-of-range offset or a base subtracted from itself —
   used as (or added to) an address. This is a value computed inside the kernel, not a pointer that
   was nulled.

### Reading the generated source

The shape of a generated assignment is a plain indexed numeric store — illustratively:

```c
// illustrative form of a generated GeneratedOperationN apply body
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation0_apply
        (JNIEnv *env, jobject obj, jlong commandQueue,
         jlongArray arg, jintArray offset, jintArray size,
         jint count, jint global_id, jlong kernelSize) {
    // pointers, offsets, sizes are read from the JNI arrays (owned by the caller)
    double *out = /* arg[0] + offset[0] */;
    const double *in = /* arg[1] + offset[1] */;
    out[global_id] = in[global_id] * 2.0;   // numeric store into caller-owned memory
}
```

There is no `= NULL` and no pointer produced inside the body — `out` and `in` are the caller's
buffers. To read the *actual* generated source for a specific operation rather than this illustrative
form, capture it and inspect it with the profile analyzer:

1. Set `HardwareOperator.enableInstructionSetMonitoring = true` (or
   `enableLargeInstructionSetMonitoring` for only large kernels) before the run; `NativeCompiler`
   then writes each kernel to `results/jni_instruction_set_N.c`.
2. Run the operation under an `OperationProfile` so a profile XML is written to the module's
   `results/` directory.
3. Use `ar-profile-analyzer` — `search_operations` to find the node, then `get_source` for the
   generated source and its per-argument offset/size bindings. **Do not** read the dumped `.c` or the
   profile XML with `cat`/`grep`; that loses the operation-node → source mapping the analyzer
   provides.

---

## 6. Crash triage playbook

For a native crash whose Java stack ends in `GeneratedOperationN.apply` / `NativeExecution`:

1. **Rule out "stale dylib from a prior build" (§1).** Libraries are overwritten before load;
   clearing `AR_HARDWARE_LIBS` changes nothing. Do not spend an iteration on it — *unless* a second
   JVM was using the same library directory concurrently (the default directory is shared per user, so
   this is not exotic), which is the one way the on-disk artifact can be wrong for the loading run
   (§1).
2. **Rule out "the kernel nulled a pointer internally" (§5).** Codegen cannot assign a pointer.
   A `0x0` inside the kernel came from the JNI boundary or from arithmetic — start at the Java
   caller.
3. **Separate the two allocation-failure modes (§2).** The *tracked* ceiling throws
   `HardwareException: Memory max reached` rather than fabricating a pointer, so if you did not see
   that exception, exhaustion of the tracked ceiling is not your cause. That is not the same as "no
   allocator can produce a zero pointer": `NativeMemoryProvider`'s calloc path can pass the tracked
   check and still get `0` back from `Malloc.apply` when the OS is genuinely out of memory. That zero
   is caught by the pre-dispatch pointer check in §5 (a named `NullPointerException`, not a
   `SIGSEGV`), so a crash *inside* the kernel is still not this — but a `NullPointerException` naming
   an argument at the boundary can be a real OS-level allocation failure, not only a caller that
   freed too early.
4. **Suspect use-after-free by GC first (§3).** A pointer that is numerically intact but points at
   an unmapped page is the classic signature. Check whether the dispatch was bracketed by
   `KernelMemoryGuard`, whether any argument failed to resolve to a `RAM` (an unguarded argument is an
   exposed surface), and whether the deferred-release timeout fired — a dispatch that held its
   reservation past `deferredReleaseTimeoutMs` (30 s) is force-freed even while bracketed, so bracketing
   alone does not clear it. The guard's warning (`warnIfActivelyReferenced`) with allocation traces
   (`AR_HARDWARE_ALLOCATION_TRACE_FRAMES`) points at where the freed block was allocated.
5. **Then inspect the arguments at the boundary (§5).** `NativeInstructionSet`'s pre-dispatch
   pointer checks will already have named a zero argument if one was present; a crash that got past
   them means the pointers were non-zero at dispatch but the memory beneath one was released or the
   offsets/sizes addressed out of range.
6. **Only then read the generated source (§5).** Capture the kernel with instruction-set monitoring
   and read it with `ar-profile-analyzer get_source`; confirm the argument offset/size bindings match
   what the caller passed.

---

## Related classes

| Class | Role |
|---|---|
| `NativeCompiler` (`base/hardware/.../hardware/jni/`) | Writes/compiles/loads generated JNI libraries; owns the library directory and lifecycle |
| `BaseGeneratedOperation` (`base/hardware/.../generated/`) | Base for the `GeneratedOperationN` reservation slots |
| `NativeInstructionSet` (`base/hardware/.../hardware/jni/`) | JNI bridge; pre-dispatch pointer validation and reentrancy contract |
| `NativeExecution` (`base/hardware/.../hardware/jni/`) | JNI dispatch coordination, memory guard bracketing, reachability fences |
| `Hardware` (`base/hardware/.../hardware/`) | `MEMORY_SCALE` ceiling and its conversion to per-provider byte limits |
| `MetalMemoryProvider` / `CLMemoryProvider` / `NativeMemoryProvider` | Enforce the byte ceiling; throw `HardwareException` on exhaustion |
| `HardwareMemoryProvider` (`base/hardware/.../hardware/mem/`) | Phantom-reference/`ReferenceQueue` GC-driven native release |
| `KernelMemoryGuard` (`base/hardware/.../hardware/mem/`) | Ref-counts in-flight kernel memory to defer release |
| `MemoryData` (`base/hardware/.../hardware/`) | Lifetime contract for hardware-accessible memory |

## See also

- [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md) — how the artifact this
  page tracks is produced and dispatched
- [base/hardware/README.md](../../base/hardware/README.md) — memory usage patterns and configuration
- [base/hardware/docs/INSTRUCTION_CACHING.md](../../base/hardware/docs/INSTRUCTION_CACHING.md) —
  in-JVM instruction-set reuse
