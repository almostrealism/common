# CUDA Backend for the Hardware Framework

Plan for adding a native CUDA backend to `ar-hardware`, alongside the existing
Metal (`hardware/metal`), OpenCL (`hardware/cl`) and JNI/C (`hardware/jni`)
backends. The Metal backend is the structural template. It is the most recent
GPU backend, it has the most mature dispatch and batching machinery, and the
problems it has already solved (asynchronous completion, foreign-dependency
bridging, lifecycle, shared memory with JNI) are the same ones CUDA raises.

## Goal

Run the computation graphs the framework already compiles on an NVIDIA GPU,
through a first-class `ComputeContext`. That means:

- the same `Scope` → source → compiled program → `InstructionSet` →
  `HardwareOperator` pipeline the other backends use;
- no change to the Producer-level API. A model that runs on `mtl` or `cl` runs
  on `cuda` with only `AR_HARDWARE_DRIVER` changed;
- numerical parity with the `native` backend on the existing test suites;
- the JNI backend and the CUDA backend sharing zero-copy memory, the way JNI
  and Metal already do on Apple Silicon.

## Target machine (verified 2026-09-27)

| Property | Value | How verified |
|---|---|---|
| Host | `dgx-spark`, Linux 7.0.0-nvidia, **aarch64** | `uname -a` |
| GPU | NVIDIA **GB10**, Blackwell, compute capability **12.1** | `nvidia-smi --query-gpu` |
| Driver / CUDA | driver 580.178.04, CUDA **13.0** | `nvidia-smi` |
| Toolkit | `/usr/local/cuda` → 13.0: `nvcc`, `libnvrtc.so.13`, `libcudart.so.13` | `ls`, `ldconfig -p` |
| Driver API | `libcuda.so.1` in `/lib/aarch64-linux-gnu` | `ldconfig -p` |
| Memory model | Addressing mode **ATS**: CPU and GPU share one coherent LPDDR5x pool (~121 GB visible to the OS) | `nvidia-smi -q`, `free -g` |
| CPU | 20 cores | `nproc` |

GB10 is an **integrated, unified-memory** part, much closer to Apple Silicon
than to a discrete card. That is good news for this plan: Metal's shared-memory
design carries over almost directly. The plan still keeps the device-memory
path correct for discrete NVIDIA GPUs (see *Memory*). Otherwise the backend
would only work on this one class of machine.

### Environment prerequisites (blocking, must be fixed before Phase 1)

These were found on the machine and must be resolved before any build or test
can run:

1. **JDK 17 is not installed.** Only `java-8-openjdk-arm64` is present, and
   the root `pom.xml` compiles with `source`/`target` 17.
2. **Maven is not on `PATH`**, and there is no `~/.m2`. The MCP test runner and
   the build validator both depend on it.
3. **`clang` is not installed.** `NativeCompiler` defaults to `clang` from
   `PATH`, so the `native` backend (which is the parity reference for every
   CUDA test) will not compile kernels. Either install clang, or set
   `AR_HARDWARE_NATIVE_COMPILER=gcc`. `gcc` 13 is present, and `NativeCompiler`
   already recognizes a `gcc` suffix.
4. **The exfiltration guard blocks agents from running compiled binaries.** An
   agent cannot execute a stand-alone native probe
   (`.claude/hooks/block-exfiltration.sh` rejects any compiled binary in the
   work tree or a temp directory). Stand-alone C/CUDA probes must be run by the
   developer (`! ./probe`). This does not affect the JNI library, which the JVM
   loads, but it does shape how Phase 0 is verified.

A device-attribute probe (`probe.c`, driver API + NVRTC) was compiled during
planning but could not be run for the reason in item 4. Running it by hand
should confirm these values before Phase 1:
`CU_DEVICE_ATTRIBUTE_PAGEABLE_MEMORY_ACCESS`,
`..._PAGEABLE_MEMORY_ACCESS_USES_HOST_PAGE_TABLES`,
`..._CONCURRENT_MANAGED_ACCESS`, `..._DIRECT_MANAGED_MEM_ACCESS_FROM_HOST`,
`..._INTEGRATED`, `..._MULTIPROCESSOR_COUNT`,
`..._SINGLE_TO_DOUBLE_PRECISION_PERF_RATIO`, and the NVRTC version.

## How the Metal backend is built (the template)

All paths are under `base/hardware/src/main/java/org/almostrealism/hardware/`.

| Metal class | Role | CUDA counterpart |
|---|---|---|
| `metal/MetalDataContext` | `HardwareDataContext`. Lazy device start, one shared `ComputeContext` plus thread-scoped ones (`computeContext(...)`), lifecycle RW lock, `deviceMemory(...)` | `cuda/CudaDataContext` |
| `metal/MetalComputeContext` | `deliver(Scope)`: `ScopeEncoder` + `MetalPrintWriter` → `MetalOperatorMap`, instruction sets keyed by `scope.signature()`. `copy()` override uses a blit | `cuda/CudaComputeContext` |
| `metal/MetalOperatorMap` | `InstructionSet`: compiles the program once, hands out one `MetalOperator` per thread and key | `cuda/CudaOperatorMap` |
| `metal/MetalProgram` | Compiles source (`MTL.createFunction`), instruction-set monitoring output, compile timing metric | `cuda/CudaProgram` (NVRTC → CUBIN → `CUmodule`/`CUfunction`) |
| `metal/MetalOperator` | `HardwareOperator.accept`: `prepareArguments`, `KernelMemoryGuard`, binds buffers and offsets/sizes, picks threadgroup size, dispatches through the runner | `cuda/CudaOperator` |
| `metal/MetalCommandRunner` | Single-thread executor that owns one open command buffer (`MAX_OPEN = 256`), signals a shared event per dispatch, bridges foreign semaphores, drains completions and runs `onComplete` | `cuda/CudaStreamRunner` |
| `metal/MetalSemaphore` | `OperationSemaphore` over a (command buffer, event value) pair | `cuda/CudaSemaphore` over a `CUevent` |
| `metal/MetalMemoryProvider`, `MetalMemory`, `MetalMemoryRef` | `HardwareMemoryProvider`, `RAM` subclass, `NativeRef` for GC-driven release. A shared mode provides memory for the JNI delegate | `cuda/CudaMemoryProvider`, `CudaMemory`, `CudaMemoryRef` |
| `metal/MetalLanguageOperations`, `MetalPrintWriter` | Extend `c/CLanguageOperations` and `c/CPrintWriter`: `[[kernel]]`, `device` address space, `global_id`/`global_count` attributes, offsets/sizes as buffers | `cuda/CudaLanguageOperations`, `CudaPrintWriter` |
| `metal/MTL` + `src/main/cpp/MTL.cpp` + `compile.sh` → `resources/libMTL.dylib` | Hand-written JNI bridge, committed prebuilt library, extracted per PID to the temp dir and `System.load`ed | `cuda/CU` + `src/main/cpp/CUDA.cpp` → `resources/libARCUDA-linux-<arch>.so` |
| `metal/MTLDevice`, `MTLBuffer`, `MTLCommandQueue`, ... (`MTLObject`) | Thin Java handles over native pointers | `cuda/CUDevice`, `CUContextHandle`, `CUStream`, `CUEvent`, `CUModule`, `CUFunction` (a `CUObject` base) |
| `metal/MetalDeviceInfo` | Device description | `cuda/CudaDeviceInfo` |

Wiring outside the `metal` package that a new backend must join. Each of these
was found by searching for Metal references across the tree:

- `io.almostrealism.compute.ComputeRequirement` (in `base/code`): the enum
  value, `resolve()` (today `GPU` → `MTL` on macOS, otherwise `CL`), and
  `getMaximumPrecision()`.
- `DriverSelection.parse`: the driver token and the `*` wildcard expansion.
- `Hardware.createContext`, `Hardware.processRequirements` (the
  `kernelFriendly` flag and the choice of shared-memory provider for the JNI
  delegate), the accelerator preference in
  `Hardware.getDataContext(boolean, boolean, ...)` (Metal, then CL),
  `Hardware.supported(...)`, and the isolated-context construction in the
  `dataContext(Callable)` path (~line 1129).
- `jni/NativeComputeContext.deliver`, which chooses the `JNIMemoryAccessor` by
  `instanceof MetalMemoryProvider`.

### One deliberate departure from Metal: argument offsets and sizes

Metal renders `enableArgumentDetailReads = true`. Each kernel receives two
extra buffers, `offsetArr` and `sizeArr`, which Metal fills inline with
`setBytes` at encode time. CUDA has no `setBytes`. Using buffers would mean
allocating and uploading two small device buffers per dispatch.

The OpenCL path already avoids that. `CLanguageOperations` with
`enableArgumentDetailReads = false` falls back to
`DefaultLanguageOperations.renderArguments`, which emits three passes
(`ParamType.ARRAY`, `OFFSET`, `SIZE`). Each argument becomes
`float* x, int xOffset, int xSize`. `CLOperator` then sets them as scalar
kernel arguments. CUDA's `cuLaunchKernel` takes an array of parameter
pointers, which fits this layout exactly. **`CudaLanguageOperations` therefore
uses the OpenCL-style scalar layout.** It is free on the host, it needs no
per-dispatch allocation, and it stays well under the kernel parameter limit up
to the `CPrintWriter` warning threshold of 150 arguments. (At 8 + 4 + 4 bytes
per argument, 150 arguments is 2.4 KB. The classic limit is 4 KB, and 32 KB
on CUDA 12.1+ for Volta and later.) `MetalCommandRunner.MAX_ARGS = 512` has no
CUDA equivalent. `CudaOperator` should reject argument lists whose parameter
block exceeds the device limit, with a clear message.

## Design

### Package and native bridge

- New package `org.almostrealism.hardware.cuda` in `base/hardware`. **No new
  Maven module** (forbidden, and not needed: Metal lives in the same module).
- **Hand-written JNI bridge**, mirroring `MTL.java`/`MTL.cpp`:
  `cuda/CU.java` declares `native` methods, and `src/main/cpp/CUDA.cpp`
  implements them against the **CUDA Driver API** (`cuda.h`) and **NVRTC**
  (`nvrtc.h`). The CUDA runtime API (`cudart`) is not used. The driver API
  gives explicit control over contexts, modules and streams, and matches how
  the framework already owns compilation.
- A build script `src/main/cpp/compile-cuda.sh` produces
  `src/main/resources/libARCUDA-linux-aarch64.so`, linked against
  `-lcuda -lnvrtc`. It is committed prebuilt, like `libMTL.dylib`, and
  extracted per PID like `MTL`'s static initializer. The architecture suffix
  leaves room for an `x86_64` build without a naming change. On a host without
  the CUDA driver, `System.load` throws `UnsatisfiedLinkError`. That is a
  `LinkageError`, which `Hardware.processRequirements` already tolerates for
  wildcard drivers and turns into a `HardwareException` for a named one.
- **Why not JCuda:** it would need a new `pom.xml` dependency (agents may not
  add one), its CUDA 13 and aarch64 support lags, and the Metal precedent shows
  a hand-written bridge of roughly 40 entry points is easy to maintain. The
  developer may still prefer JCuda. This is **Decision D1** below.

Initial JNI surface (driver API names in parentheses):

- Device and context: `init` (`cuInit`), `deviceCount`, `device(ordinal)`,
  `deviceName`, `deviceAttribute(attr)`, `totalMem`, `primaryContextRetain`
  and `primaryContextRelease`, `setCurrent` (`cuCtxSetCurrent`, see
  *Threading*).
- Compile: `compileProgram(src, name, archOptions[])`, which runs NVRTC create,
  compile, CUBIN and log retrieval, then `cuModuleLoadData`, and returns the
  module plus the compile log. Also `getFunction(module, name)`,
  `funcAttribute` (max threads per block, registers), `unloadModule`.
- Memory: `memAlloc` (`cuMemAlloc`), `memAllocManaged`, `memFree`,
  `memcpyHtoD`/`DtoH` with a direct `FloatBuffer`/`DoubleBuffer` and offset,
  `memcpyDtoDAsync`, `memsetD32Async`, `hostPointer` (the managed pointer).
- Streams and events: `streamCreate` (non-blocking), `streamDestroy`,
  `eventCreate` (`CU_EVENT_DISABLE_TIMING`), `eventRecord`, `eventQuery`,
  `eventSynchronize`, `streamWaitEvent`, `eventDestroy`.
- Launch: `launchKernel(function, gridX, blockX, long[] pointers, int[]
  scalars, long globalCount, long globalOffset, stream)`. The bridge packs the
  `void** kernelParams` in the same order as the rendered signature. The
  interleaving (pointer, offset, size per argument, then the tail) is owned by
  one method on each side and tested directly (Phase 1 test T3).
- Errors: every call checks its `CUresult`/`nvrtcResult`. On failure it throws
  a Java `HardwareException` carrying `cuGetErrorName` and `cuGetErrorString`
  (and the NVRTC log for compile errors). Nothing returns a silent 0 pointer the
  way `MTL.createFunction` does today.

### Code generation (`CudaLanguageOperations`, `CudaPrintWriter`)

`CudaLanguageOperations extends CLanguageOperations`, constructed with
`isNative = false` and `enableArgumentDetailReads = false`.

- External scope prefix `extern "C" __global__ void`. `extern "C"` stops name
  mangling, so `cuModuleGetFunction(name)` finds the kernel by its scope name.
  Internal scope prefix `__device__ void`. CUDA supports recursion in
  `__device__` functions, which Metal does not. See the note on
  `FourierTransform` under *Risks*.
- `annotationForPhysicalScope`: `null` for global pointers. Plain `T*` is
  global memory in CUDA. `__shared__` is used if `PhysicalScope.LOCAL` is ever
  emitted.
- `isNumericBoolean()` → `false` (CUDA has `bool`).
- `kernelIndex(0)` → `global_id`, as in `CLanguageOperations`. Indices greater
  than 0 are rejected, as today.
- `renderParameters` for `EXTERNAL`: append `, long global_count, long
  global_offset`. For internal scopes, append `, long global_id, long
  global_count`, the way Metal threads them through helper functions (Metal's
  `renderParameters(methodName, parameters, out)` override does the same for
  call sites).
- **Bounds guard.** Metal's `dispatchThreads` supports a non-uniform grid, and
  OpenCL's NDRange is exact, so neither kernel checks bounds. A CUDA grid is a
  whole number of blocks, so the last block overshoots. `CudaPrintWriter`
  overrides `renderArgumentReads` (called at the top of every `EXTERNAL`
  scope in `CPrintWriter.beginScope`) to emit this first:

  ```c
  long global_id = (long) blockIdx.x * blockDim.x + threadIdx.x + global_offset;
  if (global_id >= global_offset + global_count) return;
  ```

  `HardwareOperator.getGlobalWorkOffset()` is honored. OpenCL passes it to
  `clEnqueueNDRangeKernel`, and Metal currently ignores it.
- Preamble (the counterpart of `MetalComputeContext.includes`): math is
  available by default in NVRTC. The preamble must define `M_PI_F` (used by
  `CLanguageOperations.pi()` at FP32, and not defined by CUDA headers). It must
  also resolve the `min`/`max`/`fmod`/`abs`/`pow` overloads that the generated
  code relies on, for `int`, `long`, `float` and `double` alike. The
  `macos-pipeline-fixes/MATH_FUNCTION_AMBIGUITY.md` plan records that Metal
  already hit ambiguity here. Phase 2 validates the preamble against the
  expression suites rather than guessing.
- Precision: `getMaximumPrecision()` for `CUDA` is **FP32** to start, matching
  Metal and CL-on-aarch64. The code generator already supports FP64 (the type
  name comes from `Precision.typeName()`), but consumer Blackwell FP64
  throughput is a small fraction of FP32 (to be confirmed with the probe's
  `SINGLE_TO_DOUBLE_PRECISION_PERF_RATIO`). FP64 and FP16 (`__half`, which
  needs `cuda_fp16.h` in the preamble) are later work, gated by
  `AR_HARDWARE_PRECISION` the same way Metal gates FP16.

### Compilation (`CudaProgram`)

- NVRTC options: `--gpu-architecture=sm_<major><minor>` from the device's
  compute capability, which produces **CUBIN** directly (`nvrtcGetCUBIN`). PTX
  plus the driver JIT is the fallback when the NVRTC in use does not know the
  device's architecture. Also `-default-device` and `--std=c++17`.
  `-use_fast_math` is **off** at first, to get parity. Metal is fast-math
  except `tanh`, so whether to match that is a tuning question for Phase 5.
- On failure: throw `HardwareException("Failed to compile " + func)` with the
  NVRTC log attached. Honor `HardwareOperator.enableFailedInstructionSetMonitoring`
  and the other monitoring flags by writing `cuda_instruction_set_N.cu`, as
  `MetalProgram.recordInstructionSet` does.
- A compile-time metric `cudaCompile`, the counterpart of `mtlCompile`.
- **Compile cost is the main performance risk.** NVRTC is much slower per
  kernel than `MTLDevice.newFunction`, and the framework compiles many
  instruction sets. Phase 5 adds an on-disk CUBIN cache keyed by (source hash,
  arch, NVRTC version, options). It is off by default until it is proven.

### Dispatch, ordering and completion (`CudaStreamRunner`, `CudaSemaphore`)

A CUDA stream is in-order. That is simpler than Metal, where
`MetalCommandRunner` has to manufacture ordering with a shared event, batch
into command buffers, and commit. The runner keeps Metal's *contract*
(`submit(requester, command, dependsOn, onComplete) → Semaphore`, never
blocking the submitting thread) but not its batching mechanics.

- **One stream per `CudaComputeContext`.** A dependency on a
  `CudaSemaphore` from the same runner needs no action, because stream order
  already guarantees it. This replaces the "encoded into the still-open
  buffer" shortcut in `MetalCommandRunner.submit`.
- **Dependency on another CUDA context** (a different stream): use
  `cuStreamWaitEvent(stream, dependency.event)`. It is enqueued, not blocking.
- **Foreign dependency** (a JNI `DefaultLatchSemaphore`, a CL semaphore, or a
  composite): Phase 1 does a host `waitFor()` on the runner's executor before
  launching. This is correct and simple, and it matches
  `enableHostSignaledBridges = false` on Metal. A later phase can bridge
  without blocking by using `cuStreamWaitValue32` on a host-visible flag that
  `foreign.onComplete` writes. That is the analogue of Metal's per-bridge
  `MTLEvent`, and it must preserve the same "no cycle through a shared bridge"
  invariant that `MetalCommandRunner.submit` documents.
- **Completion:** after each launch, record a pooled `CUevent` (timing
  disabled) and return a `CudaSemaphore(requester, runner, event, seq)`.
  `waitFor()` → `cuEventSynchronize`. `onComplete` callbacks (the
  `KernelMemoryGuard` release and the reachability fences in
  `MetalOperator.accept`) run on a runner-owned **completion thread**. That
  thread polls or synchronizes events in submission order, so callbacks fire
  in order. **Do not use `cuLaunchHostFunc` to call back into Java**: host
  functions run on a driver thread that may not call CUDA APIs, and would need
  JNI thread attachment. The event-plus-Java-thread design avoids both
  problems.
- **Executor:** keep Metal's single-thread executor per runner. CUDA context
  binding is per thread (see *Threading*), and serializing launches onto one
  thread per stream keeps `cuLaunchKernel` ordering exactly equal to
  submission order.
- **Launch geometry:** `CudaOperator.getWorkgroupSize()` chooses the block
  size from `CU_FUNC_ATTRIBUTE_MAX_THREADS_PER_BLOCK` (register-limited, per
  kernel), capped at 256 at first. Grid = `ceil(globalWorkSize / block)`, with
  a 64-bit-safe check against `MAX_GRID_DIM_X`. The exact-divisor search that
  `MetalOperator` does is unnecessary because of the bounds guard.
- **`copy()`:** override `ComputeContext.copy(source, destination, dependsOn)`
  like `MetalComputeContext` does. When both sides are `CudaMemory`, enqueue
  `cuMemcpyDtoDAsync` on the stream and return its semaphore. Otherwise defer
  to `super.copy`. This matters for the aggregate copy-in and copy-out path in
  `AcceleratedOperation`/`MemoryDataArgumentMap`, where ordering bugs have
  already cost weeks on Metal. See `bugs` memories on
  `feature/argument-prep-assignment`. The copy-in `dependsOn` and copy-out
  `nextSemaphore` fixes made there are backend-neutral and must keep holding.
- **CUDA Graphs** (capture an `OperationList` once, replay per step) are the
  CUDA analogue of Metal's command-buffer batching. They are deferred to
  Phase 5, and only after profiles show launch overhead matters.

### Memory (`CudaMemoryProvider`, `CudaMemory`, `CudaMemoryRef`)

Two allocation modes, chosen per `CudaDataContext`:

1. **Managed (default on integrated/ATS devices, including GB10):**
   `cuMemAllocManaged(..., CU_MEM_ATTACH_GLOBAL)`. The pointer is valid on
   both CPU and GPU. So `CudaMemory.getContentPointer()` returns a real host
   address, and **the JNI backend can use it zero-copy**, exactly as it uses
   Metal shared buffers today. In that case `Hardware.processRequirements`
   hands this provider to the `NativeDataContext` delegate, and host
   `setMem`/`getMem` becomes a `memcpy` through the pointer.
2. **Device (discrete GPUs, or forced by `AR_HARDWARE_CUDA_MEMORY=device`):**
   `cuMemAlloc`. The host reads and writes through `cuMemcpyHtoD`/`DtoH` with
   direct NIO buffers, which is the same shape as
   `MetalMemoryProvider.setMem`/`getMem` today. It is never offered to the JNI
   delegate.

The mode is decided from `CU_DEVICE_ATTRIBUTE_INTEGRATED`,
`CONCURRENT_MANAGED_ACCESS` and `PAGEABLE_MEMORY_ACCESS`, not from the
hostname or architecture.

Other requirements for this layer:

- `memoryMax` accounting, the `"Memory Max Reached"` failure, and the
  allocation-size distribution metrics (`cudaAllocationSizes`) all mirror
  `MetalMemoryProvider`. `AR_HARDWARE_MEMORY_SCALE` keeps its meaning.
- Release goes through `NativeRef`/`CudaMemoryRef` and the provider's
  reference queue, exactly like `MetalMemoryRef`. `cuMemFree` must run with
  the context current, and must not run while a kernel that references the
  buffer is still in flight. `KernelMemoryGuard`, released from the
  completion callback, already provides the second part.
- **Host access ordering.** On managed memory the CPU can touch a buffer while
  a kernel is writing it. The framework's semaphores already order host reads
  after kernel completion for Metal shared buffers. The CUDA backend relies on
  the same contract and adds no hidden synchronization. If Phase 3 finds a
  stale read, the fix belongs at the call site that skipped the semaphore, not
  in a blanket `cuCtxSynchronize` inside `getMem`.
- **Removing the `instanceof` wiring.** `NativeComputeContext` picks
  `MetalJNIMemoryAccessor` by `instanceof MetalMemoryProvider` (the accessor
  is a marker with no behavior). `Hardware.processRequirements` picks the
  shared provider by `instanceof MetalDataContext / CLDataContext`. Adding a
  third `instanceof` branch for CUDA violates the "honor the interface" rule.
  Phase 3 should instead add a capability to the existing abstraction, for
  example a `HardwareDataContext`/`MemoryProvider` method that says whether its
  memory has host-dereferenceable content pointers and supplies the shared
  provider. Metal, CL and CUDA then answer it, and both `instanceof` chains go
  away. This is **Decision D4**.

### Threading and contexts

- Use the device's **primary context** (`cuDevicePrimaryCtxRetain`). The
  runtime API and any future library integration (cuBLAS, NCCL) use the same
  one.
- A CUDA context is current **per thread**. Every JNI entry point that needs a
  context calls `cuCtxSetCurrent(ctx)` first. This is cheap, and it is the only
  robust option, because the framework calls in from JUnit timeout threads, the
  `Semaphore` callback pool, and `NativeExecution` workers. The bridge caches
  the "last set" value in a `thread_local` to skip redundant calls.
- `CudaDataContext` keeps Metal's structure unchanged: the lazy `start`,
  `ensureStarted()` under the lifecycle read lock, the shared context plus
  thread-scoped `computeContext(...)` contexts (each with its own stream and
  runner), `deviceMemory(...)`, and a `destroy()` that drains runners before
  freeing memory and releasing the primary context
  (`mainRam.onFullyReleased(this::releaseDevice)`).
- Multi-GPU is out of scope. Device 0 is used, and it can be overridden with
  `AR_HARDWARE_CUDA_DEVICE`. The design does not block multi-GPU later: one
  `CudaDataContext` per device.

### Driver selection and routing

- `ComputeRequirement.CUDA`, with `getMaximumPrecision()` = `FP32`.
- `DriverSelection.parse`: a new named token **`cuda`**, which is required when
  named. `AR_HARDWARE_DRIVER=cuda` gives CUDA only.
  `AR_HARDWARE_DRIVER=native,cuda` gives the shape CI uses for the other GPU
  backends.
- `Hardware.createContext`: the `CUDA` → `CudaDataContext("CUDA", ...)`
  branch. `processRequirements`: CUDA sets `kernelFriendly`, and is eligible as
  `sharedMemoryCtx` (through the D4 capability).
- `Hardware.getDataContext(..., accelerator=true, ...)`: prefer
  Metal, then CUDA, then CL. Only one of Metal and CUDA can exist on a host, so
  their relative order is moot. CUDA ahead of CL matters on a Linux box that
  has both.
- `Hardware.supported(ctx, CUDA)` and the isolated-context branch in
  `dataContext(Callable)` both need the new type.
- **Deliberately not in Phase 1:** changing what `*` and `gpu` resolve to on
  Linux. Today `*` on Linux is `[CL, JNI]` and `GPU.resolve()` is `CL` off
  macOS. The amd-halo OpenCL runner and every `ubuntu-latest` CI job depend on
  this. Making `*`/`gpu` choose CUDA when a CUDA driver is present is
  **Decision D2**, to be made once the backend passes the parity suites.

## Phases

Each phase ends with the relevant tests run through `ar-test-runner`, the
build validator clean, and memories stored. Nothing is committed by agents.
Changes are staged for the developer.

### Phase 0: Environment (developer, about 1 hour)

- Install JDK 17 (aarch64) and Maven. Install clang, or export
  `AR_HARDWARE_NATIVE_COMPILER=gcc` in the test runner's environment.
- Run `mvn clean install -DskipTests` once, to populate `~/.m2` and to prove
  the tree builds on aarch64 Linux.
- Run the device probe by hand and paste the output into this document.
- Run one existing test under `AR_HARDWARE_DRIVER=native` through the MCP test
  runner (for example `PackedCollectionMapTests#enumerateRepeatReduce`) to
  prove the parity reference works on this machine.

### Phase 1: Native bridge and a hand-written kernel (no code generation)

**Status (2026-09-27): complete.** `libARCUDA-linux-aarch64.so` built with
`compile-cuda.sh`. All six `CudaBridgeTest` methods pass on the GB10, each run
individually through `ar-test-runner` (none skipped): `deviceAttributes`,
`saxpyDeviceMemory`, `saxpyManagedMemory`, `parameterPacking`, `boundsGuard` and
`compileErrorIncludesLog`. The build validator passes (checkstyle, code_policy,
test_timeouts, duplicate_code, invalid_files). Phase 0 was completed by the developer:
JDK 17, Maven and clang were installed, and the MCP servers must be launched with
`/home/agent0/agent-venv` on `PATH`.

**Open before merge (2026-09-27):** the committed `libARCUDA-linux-aarch64.so`
predates the `CUDA.cpp` change that removed the per-thread cached current context
(`makeCurrent` now calls `cuCtxSetCurrent` on every entry). Until the library is
rebuilt with `compile-cuda.sh` on an aarch64 CUDA host and committed, the runtime
still uses the old cache and can issue work against a released primary context from
another thread. Sessions without a CUDA toolkit cannot rebuild it.

- `src/main/cpp/CUDA.cpp`, `compile-cuda.sh`, the committed
  `libARCUDA-linux-aarch64.so`, and the `cuda/CU.java` natives with the error
  translation described above.
- The `CUObject` handles (`CUDevice`, `CUStream`, `CUEvent`, `CUModule`,
  `CUFunction`) with an explicit `release()`, following `MTLObject`.
- Tests in `base/hardware/src/test/.../hardware/test/` (each extends
  `TestSuiteBase`, has a `@Test(timeout=...)`, and skips cleanly when CUDA is
  not available):
  - **T1** device enumeration and attributes;
  - **T2** NVRTC compile of a fixed `saxpy` source, launch, and read-back
    through both memory modes;
  - **T3** kernel-parameter packing: a kernel that writes each received
    pointer, offset, size, `global_count` and `global_offset` to an output
    buffer, compared against what Java sent. This pins down the ABI between
    `CudaOperator` and the rendered signature;
  - **T4** a compile error surfaces the NVRTC log in a `HardwareException`.

### Phase 2: `CudaDataContext`, `CudaComputeContext`, code generation, dispatch

- The full class set from the table above, with a synchronous-per-dispatch
  `CudaStreamRunner` first. That means `submit` returns an already-completed
  semaphore. This is the simplest correct version, and it gives a baseline.
- The `ComputeRequirement.CUDA`, `DriverSelection`, and `Hardware` wiring.
  Extend `DriverSelectionTest` for the `cuda` token.
- `CudaDataContextLifecycleTest`, modeled on `MetalDataContextLifecycleTest`.
- A **CUDA smoke set** run with `AR_HARDWARE_DRIVER=native,cuda` and compared
  with `native` alone, starting with the classes the Metal bugs have taught us
  are sensitive to aggregation and instruction-set reuse:
  `PackedCollectionMapTests`, `AggregatedComputationTests`,
  `AssignmentRunnerTest`, `DeltaFeaturesTests`, `ConvolutionModelTests`,
  `FFTConvolutionTest`, `TrainModelTest`, plus the expression and math suites
  that exercise the preamble. One test method per invocation, per the
  execution limits. The exact list is fixed when Phase 2 starts, with
  `consult`.

**Status (2026-09-27): complete.**

- **Implemented:**
  - `CudaDataContext`, `CudaComputeContext`, `CudaOperatorMap`, `CudaProgram`, `CudaOperator`
    and `CudaStreamRunner` (synchronous);
  - `CudaMemoryProvider`, `CudaMemory` and `CudaMemoryRef`;
  - `CudaLanguageOperations` and `CudaPrintWriter`;
  - `ComputeRequirement.CUDA`, the `cuda` driver token, and the `Hardware` wiring.
- **Profile check:** `ar-profile-analyzer` confirms that generated kernels have exactly the
  planned signature and bounds guard.
- **Refactors made to avoid duplicating Metal:**
  - The lazy-start, shared-context and deferred-release lifecycle of `MetalDataContext`
    moved into a new base, `ctx/AcceleratorDataContext`, which Metal and CUDA both extend.
  - `HardwareDataContext` gained `withMemoryProvider(...)` and `call(...)`.
  - Instruction-set dumping moved to `HardwareOperator.recordInstructionSet(prefix, ext,
    src)`, which the Metal and CUDA programs share.
  - The instruction-set cache key moved to `AbstractComputeContext.instructionSetKey`.
  - In `Hardware`, a preference list (`ACCELERATOR_PREFERENCE`) replaced the three copied
    accelerator loops, and `dataContext(Callable)` now goes through `createContext`.
- **Tests:**
  - All pass, each run individually: `CudaBridgeTest`, `CudaDataContextLifecycleTest`, the
    new `DriverSelectionTest` methods, and `MetalDataContextLifecycleTest` (whose reflection
    lookup of `lifecycleLock` now targets the base class).
  - The build validator is clean, including duplicate_code.
- **Smoke set, all passing:**
  - Under `cuda`: `CollectionMathTests` (2), `PadKernelSourceDiagTest`,
    `PackedCollectionMapTests#enumerateRepeatReduce` and `#enumerateRepeatMapReduce`,
    `AggregatedComputationTests#largeSum`, `AssignmentRunnerTest#providerToProviderCopy`,
    `DeltaFeaturesTests#embeddedProduct`, `ConvolutionModelTests#convMultiChannelSmall` and
    `#convBackwardsSmall`, `FFTConvolutionTest#testBasicConvolution`.
  - Under `native,cuda`: `PackedCollectionMapTests#enumerateRepeatReduce` and
    `#enumerateRepeatMapReduce`, `ConvolutionModelTests#convSingleChannelSmall` and
    `#convBackwardsMedium`, `TrainModelTest#convPool`, and
    `FFTConvolutionTest#testLargerConvolution`.

**Findings:**

1. **Recursive FFT under `cuda` alone.** `FFTConvolutionTest#testLargerConvolution` fails
   under `AR_HARDWARE_DRIVER=cuda` alone with `CUDA_ERROR_ILLEGAL_ADDRESS`.
   - Cause, verified with the profile analyzer: `FourierTransform` generates recursive
     `__device__` functions with four `float[2048]` locals per frame, about 11 levels deep,
     which overflows CUDA's default 1 KB per-thread stack. Metal cannot compile this kernel
     at all.
   - Under `native,cuda` the test passes: the recursive FFT is routed to JNI and the
     data-parallel operations run as CUDA kernels. The `cuda`-only case is a backend
     limitation shared with Metal. Raising `CU_LIMIT_STACK_SIZE` would need hundreds of KB
     per resident thread, so it is not pursued.
2. **Swallowed device faults.** A device fault inside a kernel dispatched from a
   `ComputeContext-N` executor thread is thrown on that thread and never reaches the caller.
   CUDA faults are sticky: every later call in the CUDA context fails, so the caller sees
   the error only at its next CUDA call. Phase 3 should make a context-fatal CUDA error fail
   loudly, and record it on the runner so that later submissions report the original fault.
3. **Driver choice for smoke runs.** `native,cuda` is the configuration to use for smoke runs,
   matching how Metal is exercised under `*` on macOS.

### Phase 3: Asynchronous execution and shared memory

- Asynchronous `CudaStreamRunner`: pooled events, a completion thread, ordered
  `onComplete`, cross-stream `cuStreamWaitEvent`, and a host-wait bridge for
  foreign semaphores.

  **Status: the runner is asynchronous** (`CudaStreamRunner`, `CudaSemaphore`). `submit`
  never waits for a dependency or for successful GPU work: it launches and records an event
  behind the work, and a runner-owned completion thread synchronizes events in launch order,
  then runs `onComplete` before settling the semaphore. Its one synchronous wait is on the
  launch-failure path, where `launch` drains the stream before the failure is rethrown, so work
  the failed command already enqueued cannot outlive the buffers it referenced. It departs from
  the sketch above in three ways:
  - Launches run on the submitting thread under the runner's monitor, not on a
    single-thread executor. Every `CU` entry point makes its context current, so per-thread
    binding does not require one thread, and the monitor already makes launch order equal
    submission order.
  - A foreign dependency holds that submission, and every later one, in a host-side queue.
    The queue is released from `Semaphore.CALLBACK_EXECUTOR` once the dependency settles,
    rather than being waited for on the submitting thread. Holding the later submissions
    preserves submission order, exactly as a Metal bridge's GPU wait holds every command
    buffer committed after it. Work already on the stream is never held, so the "no cycle
    through a shared bridge" invariant holds.
  - Events are created per launch and destroyed after completion; they are not pooled yet.

  Still open: a `CudaSemaphore` from another CUDA context is treated as foreign; using
  `cuStreamWaitEvent` for it is the remaining optimization. Event pooling should wait until
  profiles show event creation matters.
- Managed memory handed to the JNI delegate through the D4 capability. Remove
  the `instanceof` chains in `NativeComputeContext` and
  `Hardware.processRequirements`.
- Re-run the smoke set under `AR_HARDWARE_DRIVER=native,cuda` with shared
  memory on. The regressions to watch for are the ones Metal already had:
  copy-in reading before the producing kernel completes, copy-out reading the
  aggregate early, and a reused instruction set bound to the wrong aggregate.
  **For any wrong-output investigation, use `ar-profile-analyzer`
  (`get_source`, argument offsets) before adding log probes** (Rule 3b).

### Phase 4: Breadth (ML and audio suites)

- Run the representative `engine/ml` and `engine/audio` tests (attention,
  transformer blocks, `DiffusionSampler` steps, and the audio filters) on
  `cuda` one method at a time. Fix code-generation gaps in
  `CudaLanguageOperations`/`CudaPrintWriter`, not in the callers.
- Document the backend in `base/hardware/README.md` (Backend Selection section,
  the native libraries table, and `AR_HARDWARE_CUDA_*` variables) and in the
  `Hardware` class Javadoc list of drivers.

### Phase 5: Performance

- Profile with `ar-profile-analyzer`: compile time vs. launch overhead vs.
  kernel time.
- In order of expected payoff:
  1. the CUBIN disk cache;
  2. a non-blocking foreign bridge (`cuStreamWaitValue32`);
  3. CUDA Graph capture for repeated `OperationList`s;
  4. a block-size and occupancy heuristic;
  5. `-use_fast_math` parity with Metal.
- Revisit FP16 and FP64.

### Phase 6: CI (on a `ci/...` branch only)

- Register this machine as a self-hosted runner (label, for example,
  `[self-hosted, linux, cuda]`). Follow the precedent in
  `AMD_HALO_CL_RUNNER.md`, including its container feasibility check. For
  CUDA that means the NVIDIA Container Toolkit and `--gpus all`.
- Add `test-cuda` jobs mirroring `test-cl`/`test-mac`, and add their coverage
  artifacts to `analysis.needs` (Rule 10). Read `.github/CLAUDE.md` and run the
  Rule 6 POM audit first. This phase **must** be its own `ci/...` branch,
  because the CI file lock rejects workflow edits on any other branch.

## Decisions for the developer

**Status (2026-09-27): all six recommendations accepted by the developer.**
Work proceeds on branch `feature/cuda-backend`.

| # | Question | Recommendation (accepted) |
|---|---|---|
| D1 | JNI bridge: hand-written (like Metal) or JCuda? | **Hand-written, driver API + NVRTC.** No POM change, CUDA 13/aarch64 today, same maintenance model as `MTL.cpp`. |
| D2 | Should `*` and `gpu` resolve to CUDA on Linux when a CUDA driver is present? | **Not yet.** Decide after Phase 3 parity. When it happens, make `resolve()` availability-aware rather than hard-coding OS checks. |
| D3 | Commit the prebuilt `.so` to `src/main/resources` (like `libMTL.dylib`), or build it during the Maven build? | **Commit it**, with `compile-cuda.sh` as the source of truth. A Maven native build step would need POM changes and a CUDA toolkit on every CI machine. |
| D4 | Replace the `instanceof MetalMemoryProvider` / `MetalDataContext` / `CLDataContext` checks with a capability on the existing abstraction before adding CUDA to them? | **Yes, in Phase 3.** Adding a third `instanceof` branch is exactly what the interface rule forbids. |
| D5 | Scope: GB10 (integrated) only, or discrete NVIDIA GPUs from day one? | **Design for both, test on GB10.** The device-memory mode is small, and it keeps the backend from depending silently on ATS. |
| D6 | Names: `ComputeRequirement.CUDA`, driver token `cuda`, package `hardware.cuda`, context name `"CUDA"`? | Yes (Metal uses `MTL`/`mtl`, but `cuda` is the name everyone will type). |

## Risks

- **NVRTC compile latency** could dominate short tests, because the framework
  compiles many small instruction sets. It is mitigated by the Phase 5 cache.
  Measure it in Phase 2 so it is not a surprise.
- **Code-generation gaps.** The generated C has only been validated against a
  C compiler, MSL and OpenCL C. CUDA is C++, so overload resolution
  (`min(int, long)`, `abs(float)`, `pow(int, int)`) differs from all three.
  Expect a list of small fixes in Phase 2. The expression test suites are the
  detector.
- **Recursion.** `FourierTransform` generates a recursive kernel. It cannot
  run on Metal, and currently relies on routing to JNI (see the
  `feature/metal-sustained-dispatch` progress memory). CUDA permits
  recursion, but thread-local arrays such as `float even_24[1024]` passed
  down the recursion make per-thread stack usage large. It may need
  `cuCtxSetLimit(CU_LIMIT_STACK_SIZE)`, or it may be better routed to JNI.
  Test it explicitly rather than let it fail silently.
- **Hidden synchronization bugs** that Metal's batching masked, or that
  CUDA's in-order stream masks, will surface differently. The Phase 3 smoke
  set is chosen to flush these out early.
- **Test visibility.** The guard blocks agents from running native binaries,
  so all native verification in agent sessions must go through the JVM
  (`ar-test-runner`). Phase 1's Java tests are therefore the primary evidence
  that the bridge works, not a C harness.

## Out of scope

- cuBLAS, cuDNN or CUTLASS for matmul and convolution. The framework's
  premise is that it generates the kernels itself. A vendor-library fast path
  for `dense`/`matmul` could be a later, separate plan.
- Multi-GPU, NCCL, MIG.
- Changing Producer-level APIs or any existing backend's behavior (beyond the
  D4 refactor, which must be behavior-neutral for Metal and CL).
