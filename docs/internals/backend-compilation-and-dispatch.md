# Backend Compilation and Dispatch

## Overview

This document explains how optimized process trees compile to native code and execute
on hardware backends (JNI/C, OpenCL, Metal). This is the final stage of the compilation
pipeline — it takes a `Scope` (the computation AST) and produces an executable kernel.

For how process trees are constructed, see
[computation-graph-to-process-tree.md](computation-graph-to-process-tree.md).
For how trees are optimized before reaching this stage, see
[process-optimization-pipeline.md](process-optimization-pipeline.md).

## Compilation Flow

```
Scope (computation AST)
  │
  ├── ComputationScopeCompiler
  │     Prepares scope: arguments, simplification, metadata
  │
  ├── ScopeInstructionsManager
  │     Manages lifecycle, caching, lazy compilation
  │
  ├── ComputeContext.deliver(scope)
  │     Backend-specific compilation
  │     ├── NativeComputeContext  →  C source → clang → .so → JNI
  │     ├── CLComputeContext      →  OpenCL source → cl_program → cl_kernel
  │     └── MetalComputeContext   →  Metal source → MTLLibrary → MTLFunction
  │
  └── InstructionSet → Execution
        Cached compiled kernel, ready to dispatch
```

## ComputationScopeCompiler — Scope Preparation

`ComputationScopeCompiler` (`base/hardware/src/.../instructions/ComputationScopeCompiler.java`)
prepares a `Computation` for compilation by generating and enriching its `Scope`.

### Compilation Lifecycle

```java
// 1. Create compiler from a Computation
ComputationScopeCompiler<T> compiler = new ComputationScopeCompiler<>(computation, nameProvider);

// 2. Prepare arguments (for Process tree wiring)
compiler.prepareArguments(argumentMap);

// 3. Prepare scope inputs
compiler.prepareScope(inputManager, kernelStructureContext);

// 4. Compile — generates the Scope AST
Scope<T> scope = compiler.compile();

// 5. Post-compile enrichment — shape validation, metadata
compiler.postCompile();

// 6. Check status
if (compiler.isCompiled()) {
    // Scope is ready for backend compilation
}
```

### What Compile Does

1. **Scope generation** — Calls `Computation.getScope(KernelStructureContext)` to produce
   the raw AST
2. **Argument binding** — Sets up `ArgumentMap` connecting producer inputs to scope variables
3. **Simplification** — Optimizes the expression tree (constant folding, identity elimination —
   see [expression-evaluation.md](expression-evaluation.md))
4. **Metadata enrichment** — Adds shape information, operation signature, traversal policy
5. **Kernel structure support** — Manages `KernelSeriesCache` and
   `KernelTraversalOperationGenerator` for complex kernel patterns

### Operation Signatures

Each compiled operation gets a unique **signature** that identifies it for caching:

```
"Add_f64_3_2&distinct=2;"
  │    │   │ │          │
  │    │   │ │          └── Metadata suffix
  │    │   │ └── Shape dimensions
  │    │   └── Precision (f64 = double, f32 = float)
  │    └── Operation type
  └── Full signature used as cache key
```

Signatures ensure that identical operations compile once and reuse the cached kernel.

## InstructionSetManager — Compilation Caching

The `InstructionSetManager` hierarchy manages compiled kernels with lazy compilation
and caching.

### Class Hierarchy

```
InstructionSetManager<K extends ExecutionKey>
└── AbstractInstructionSetManager<K>
    └── ScopeInstructionsManager<K>
        (implements ComputableInstructionSetManager<K>)
```

### ExecutionKey — Cache Identity

`ExecutionKey` (`base/hardware/src/.../instructions/ExecutionKey.java`) is a marker
interface for cache keys. Implementations must provide proper `equals()` and `hashCode()`.

Two standard implementations:

| Key Type | Identifies By | Use Case |
|----------|--------------|----------|
| `DefaultExecutionKey` | Function name + arg count | Multi-function scopes |
| `ScopeSignatureExecutionKey` | Full operation signature | Standard single-function scopes |

### ScopeInstructionsManager — Lazy Compilation

`ScopeInstructionsManager` (`base/hardware/src/.../instructions/ScopeInstructionsManager.java`)
is the standard implementation. It defers compilation until the first execution request:

```java
// Constructor — no compilation yet
ScopeInstructionsManager<K> manager = new ScopeInstructionsManager<>(
    computeContext,
    () -> compiler.compile(),   // Lazy scope supplier
    null                         // Optional access listener
);

// First getOperator() call triggers compilation
synchronized Execution getOperator(K key) {
    if (operators == null || operators.isDestroyed()) {
        // Compilation happens HERE — scope → native code
        operators = getComputeContext().deliver(getScope());
        HardwareOperator.recordCompilation(!getComputeContext().isCPU());
    }
    return operators.get(scopeName, arguments.size());
}
```

**Thread safety:** The `getOperator()` method is `synchronized`, ensuring that
concurrent callers share the same compiled kernel rather than compiling redundantly.

### ComputableInstructionSetManager — Output Tracking

`ComputableInstructionSetManager` extends `InstructionSetManager` with output metadata,
tracking where each operation writes its result:

```java
int getOutputArgumentIndex(K key);   // Which argument receives output
int getOutputOffset(K key);          // Byte offset within that argument
```

This is essential for process tree wiring — the output of one operation becomes the
input of the next.

## Hardware — Backend Selection

`Hardware` (`base/hardware/src/.../hardware/Hardware.java`) is the singleton entry point
for the hardware acceleration system. It manages backend detection, initialization, and
selection.

### Backend Auto-Selection

The `AR_HARDWARE_DRIVER` environment variable controls which backends are loaded:

| Value | Effect |
|-------|--------|
| `native` | JNI only (C compilation via clang) |
| `cl` | OpenCL only |
| `mtl` | Metal only (macOS) |
| `cpu` | CPU-optimized backend |
| `gpu` | GPU-optimized backend |
| `*` or unset | Auto-detect best available |

**Auto-detection by platform:**
- **ARM64 (Apple Silicon):** JNI → Metal → OpenCL
- **x86/x64 (Linux/Windows):** OpenCL → JNI
- **x86/x64 (macOS):** OpenCL (no JNI)

### Key Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `AR_HARDWARE_LIBS` | Directory for compiled native libraries | **Required** |
| `AR_HARDWARE_PRECISION` | `FP32` (float) or `FP64` (double) | `FP64` |
| `AR_HARDWARE_MEMORY_SCALE` | Max memory: 2^scale × 64MB | 4 (1GB) |
| `AR_HARDWARE_MEMORY_LOCATION` | OpenCL memory strategy | device |
| `AR_HARDWARE_NATIVE_COMPILER` | Path to C compiler | clang |

## ComputeContext — The Compilation Bridge

`ComputeContext` (`base/code/src/.../code/ComputeContext.java`) is the interface between
the framework and a specific hardware backend. Each backend provides its own implementation.

### Core Contract

```java
public interface ComputeContext {
    InstructionSet deliver(Scope scope);     // Compile scope to native code
    void runLater(Runnable runnable);         // Deferred execution on backend
    boolean isExecutorThread();              // Thread identification
    boolean isCPU();                          // Hardware type detection
}
```

The `deliver()` method is where compilation happens — it takes a `Scope` and returns
an `InstructionSet` containing the compiled kernel.

### AbstractComputeContext — Thread Pool Management

`AbstractComputeContext` (`base/hardware/src/.../ctx/AbstractComputeContext.java`) provides
the base implementation with:

- **Thread pool** — Fixed-size executor for async compilation and execution
  - Size: `KernelPreferences.getEvaluationParallelism()` (defaults to CPU core count)
  - All threads in "ComputeContext" ThreadGroup
  - Identified via `isExecutorThread()` for backend-specific behavior

- **Compilation timing** — Optional recording via `CompilationTimingListener`:
  ```java
  AbstractComputeContext.compilationTimingListener = (scope, source, nanos) -> {
      System.out.println("Compiled " + scope + " in " + (nanos / 1_000_000) + "ms");
  };
  ```

### HardwareDataContext — Memory Management

`HardwareDataContext` (`base/hardware/src/.../ctx/HardwareDataContext.java`) manages
per-backend memory allocation:

- **Thread-local memory providers** — Different threads can use different allocation
  strategies (GPU memory, host memory, heap memory)
- **Shared memory scopes** — Cross-backend memory sharing for transfers
- **Default provider** — GPU device memory with fallback to host/heap

## Backend-Specific Compilation

### JNI/C Backend (NativeCompiler)

`NativeCompiler` (`base/hardware/src/.../hardware/jni/NativeCompiler.java`) compiles
scopes to native C code, then to shared libraries loaded via JNI:

```
Scope
  │
  ├── 1. Generate C source with JNI headers
  │      → /tmp/ar_libs/libName.c
  │
  ├── 2. Invoke clang
  │      clang -shared -o libName.so libName.c
  │
  ├── 3. Wait for compilation
  │      Process.waitFor()
  │
  ├── 4. Load shared library
  │      System.load(libName.so)
  │
  └── 5. Native method available via JNI
```

**Configuration:**
- `AR_HARDWARE_LIBS` — Output directory for compiled libraries (required)
- `AR_HARDWARE_NATIVE_COMPILER` — Compiler path (default: clang)
- `AR_HARDWARE_NATIVE_LINKER` — Linker path (Clang only)

### OpenCL Backend (CLOperator)

`CLOperator` (`base/hardware/src/.../hardware/cl/CLOperator.java`) wraps OpenCL
kernels:

```
Scope
  │
  ├── 1. Generate OpenCL source
  │      __kernel void operation(args...) { ... }
  │
  ├── 2. Create cl_program
  │      clCreateProgramWithSource()
  │
  ├── 3. Build program
  │      clBuildProgram()
  │
  ├── 4. Create cl_kernel
  │      clCreateKernel()
  │
  └── 5. Ready for enqueueing
```

**Kernel argument layout:** Each argument is a triple:
- `cl_mem` pointer — The memory buffer
- `int` offset — Byte offset into the buffer
- `int` size — Number of elements

**Queue selection:** Small work sizes use the main command queue; large work sizes
use a dedicated kernel queue for better GPU utilization.

**Argument caching:** `CLOperator` caches kernel argument bindings to avoid redundant
`clSetKernelArg` calls when the same buffers are used across invocations.

### Metal Backend (MetalOperator)

`MetalOperator` (`base/hardware/src/.../hardware/metal/MetalOperator.java`) wraps Metal
compute pipelines:

```
Scope
  │
  ├── 1. Generate Metal Shading Language source
  │      kernel void operation(args...) { ... }
  │
  ├── 2. Create MTLLibrary
  │      device.makeLibrary(source:)
  │
  ├── 3. Create MTLComputePipelineState
  │      device.makeComputePipelineState(function:)
  │
  └── 4. Ready for dispatch
```

**Threadgroup sizing:** Automatic calculation based on pipeline's
`maxTotalThreadsPerThreadgroup`. SIMD-width-aware workgroup dimension selection.

**Dispatch modes:**
- `dispatchThreadgroups` — Grid of threadgroups (standard)
- `dispatchThreads` — Direct thread count (when hardware supports it)

## InstructionSet and Execution — The Runtime Interface

### InstructionSet

`InstructionSet` (`base/code/src/.../code/InstructionSet.java`) represents a compiled
kernel or set of kernels:

```java
public interface InstructionSet extends Destroyable {
    Execution get();                                // Default function
    Execution get(String function);                 // Named function
    Execution get(String function, int argCount);   // With arg count
    boolean isDestroyed();
}
```

### Execution

`Execution` (`base/code/src/.../code/Execution.java`) is the lowest-level runtime
interface — it dispatches a compiled kernel with arguments:

```java
public interface Execution {
    Semaphore accept(Object[] args);                         // Execute
    Semaphore accept(Object[] args, Semaphore dependsOn);    // With dependency
    boolean isDestroyed();
}
```

**Dependency chaining:** The `Semaphore dependsOn` parameter enables pipelining —
a kernel can be enqueued before its dependency completes, with the backend handling
synchronization.

## Instruction Reuse and Caching

The framework avoids redundant compilation through multiple caching layers:

1. **Operation signatures** — Identical operations (same type, precision, shape) share
   the same compiled kernel via `ScopeSignatureExecutionKey`

2. **ScopeInstructionsManager** — Lazy compilation with thread-safe caching. Once a
   scope is compiled, the `InstructionSet` is reused for all subsequent calls

3. **ScopeSettings.enableInstructionSetReuse** — Global toggle for instruction set
   reuse across identical operations

4. **InstructionSet lifecycle** — Compiled kernels persist until explicitly destroyed
   or the process exits. The `Destroyable` interface ensures cleanup

**CRITICAL INSIGHT:** Instruction caching means that the first invocation of a new
operation type is slow (compilation), but subsequent invocations are fast (cached
dispatch). This is why warm-up runs matter for benchmarking.

## Fallback Behavior

When a preferred backend is unavailable:

1. **Hardware auto-detection** tries backends in priority order (platform-dependent)
2. If no GPU backend is available, falls back to JNI/CPU
3. If compilation fails on one backend, the error propagates — there is no automatic
   retry on a different backend
4. `ComputeContext.isCPU()` allows code to adapt behavior based on the active backend

## Debugging Compilation

### Compilation Logging

```bash
export AR_HARDWARE_COMPILER_LOGGING=true
```

Enables verbose logging of the C/OpenCL/Metal compilation process.

### Compilation Timing

```java
AbstractComputeContext.compilationTimingListener = (scope, source, nanos) -> {
    log("Compiled " + scope + " in " + (nanos / 1_000_000) + "ms");
};
```

### Run Logging

```bash
export AR_HARDWARE_RUN_LOGGING=true
```

Logs each kernel dispatch with timing information.

### Common Issues

**`NoClassDefFoundError: PackedCollection`** — `AR_HARDWARE_LIBS` is not set. This
environment variable is required for native library loading.

**Compilation timeout** — The C compiler (clang) is invoked as a subprocess. On systems
with slow I/O, compilation can take longer than expected. Check that `AR_HARDWARE_LIBS`
points to a fast filesystem.

**`HardwareException: Memory max reached`** — The operation exceeds the configured
memory limit. Increase `AR_HARDWARE_MEMORY_SCALE` or optimize the process tree to
reduce memory usage.

## Related Files

- `ComputationScopeCompiler.java` (`base/hardware/src/.../instructions/`) — Scope preparation
- `ScopeInstructionsManager.java` (`base/hardware/src/.../instructions/`) — Compilation caching
- `ComputableInstructionSetManager.java` (`base/hardware/src/.../instructions/`) — Output tracking
- `InstructionSetManager.java` (`base/hardware/src/.../instructions/`) — Cache interface
- `ExecutionKey.java` (`base/hardware/src/.../instructions/`) — Cache key marker
- `Hardware.java` (`base/hardware/src/.../hardware/`) — Backend initialization
- `AbstractComputeContext.java` (`base/hardware/src/.../ctx/`) — Thread pool, timing
- `HardwareDataContext.java` (`base/hardware/src/.../ctx/`) — Memory management
- `CLOperator.java` (`base/hardware/src/.../hardware/cl/`) — OpenCL dispatch
- `MetalOperator.java` (`base/hardware/src/.../hardware/metal/`) — Metal dispatch
- `NativeCompiler.java` (`base/hardware/src/.../hardware/jni/`) — C/JNI compilation

## See Also

- [computation-graph-to-process-tree.md](computation-graph-to-process-tree.md) — How process trees are built
- [process-optimization-pipeline.md](process-optimization-pipeline.md) — How trees are optimized before compilation
- [expression-evaluation.md](expression-evaluation.md) — How expression trees work within scopes
- [packed-collection-examples.md](packed-collection-examples.md) — GPU memory handling patterns
- [operationlist-optimization-flags.md](operationlist-optimization-flags.md) — Compilation flags
