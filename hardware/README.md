# Hardware Module

The **hardware** module is the foundational layer for hardware-accelerated computation in Almost Realism. It provides abstractions for memory management, operation compilation, and multi-backend execution (CPU, GPU, OpenCL, Metal) with zero-code configuration.

## Table of Contents

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
  - [Key Components](#key-components)
  - [Backend Packages](#backend-packages)
- [Core Concepts](#core-concepts)
- [Memory Management Patterns](#memory-management-patterns)
- [Environment Configuration](#environment-configuration)
- [Common Usage Patterns](#common-usage-patterns)
- [Performance Optimization](#performance-optimization)
- [Advanced Topics](#advanced-topics)
- [Troubleshooting](#troubleshooting)

## Overview

The hardware module enables:

- **Hardware Acceleration**: Execute computations on CPU, GPU, or specialized accelerators
- **Multi-Backend Support**: OpenCL, Metal, and JNI backends with automatic selection
- **Memory Abstraction**: Unified memory interface across heap, off-heap, and device memory
- **Kernel Caching**: Multi-level caching to minimize compilation overhead
- **Zero-Code Configuration**: Control behavior via environment variables
- **Type-Safe Operations**: Strongly-typed producers and computations

## Quick Start

### 1. Environment Setup

Before using any Almost Realism functionality, set these **required** environment variables:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
```

- `AR_HARDWARE_LIBS`: Directory for generated native libraries
- `AR_HARDWARE_DRIVER`: Execution backend (`native`, `cl`, `mtl`, `gpu`, `cpu`, `*`)

### 2. Basic Usage

```java
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.relation.Producer;

public class Example implements HardwareFeatures {
    public void run() {
        // Create data
        PackedCollection<?> a = new PackedCollection<>(1000);
        PackedCollection<?> b = new PackedCollection<>(1000);

        // Build computation
        Producer<?> result = multiply(p(a), p(b));

        // Execute (auto-compiles to hardware kernel)
        PackedCollection<?> output = result.get().evaluate();
    }
}
```

### 3. Test Execution

Always set environment variables when running tests:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test
```

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                      Hardware Module                           │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌─────────────┐        ┌──────────────┐                      │
│  │  Hardware   │───────▶│DefaultComputer│                     │
│  │  (Config)   │        │  (Caching)    │                     │
│  └─────────────┘        └──────────────┘                      │
│         │                       │                              │
│         │                       │                              │
│    ┌────▼────────────────────────▼─────┐                      │
│    │     DataContext (per backend)     │                      │
│    ├────────────────────────────────────┤                     │
│    │  • CLDataContext (OpenCL)          │                     │
│    │  • MetalDataContext (Metal)        │                     │
│    │  • NativeDataContext (JNI)         │                     │
│    │                                    │                     │
│    │  Provides:                         │                     │
│    │  • MemoryProvider (allocation)     │                     │
│    │  • ComputeContext (compilation)    │                     │
│    └────────────────────────────────────┘                     │
│                                                                │
│  ┌──────────────────────────────────────────┐                 │
│  │         Memory Abstraction                │                 │
│  ├──────────────────────────────────────────┤                 │
│  │  MemoryData ◄─── MemoryBank              │                 │
│  │      ▲              (Collection)          │                 │
│  │      │                                    │                 │
│  │      └──── PackedCollection               │                 │
│  └──────────────────────────────────────────┘                 │
│                                                                │
│  ┌──────────────────────────────────────────┐                 │
│  │      Computation Framework                │                 │
│  ├──────────────────────────────────────────┤                 │
│  │  Producer ──▶ Computation ──▶ Evaluable   │                 │
│  │      │             │             │         │                 │
│  │      │             │             ▼         │                 │
│  │      │             │      Hardware Kernel  │                 │
│  │      │             │                       │                 │
│  │      └──▶ OperationList (composition)      │                 │
│  └──────────────────────────────────────────┘                 │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Purpose | Documentation |
|-----------|---------|---------------|
| **Hardware** | Configuration and initialization | [Hardware.java](src/main/java/org/almostrealism/hardware/Hardware.java) |
| **DefaultComputer** | Compilation and caching coordination | [DefaultComputer.java](src/main/java/org/almostrealism/hardware/DefaultComputer.java) |
| **HardwareFeatures** | Feature interface for building operations | [HardwareFeatures.java](src/main/java/org/almostrealism/hardware/HardwareFeatures.java) |
| **MemoryData** | Hardware-accessible data abstraction | [MemoryData.java](src/main/java/org/almostrealism/hardware/MemoryData.java) |
| **MemoryBank** | Collection of MemoryData in single allocation | [MemoryBank.java](src/main/java/org/almostrealism/hardware/MemoryBank.java) |
| **OperationList** | Composable operation sequences | [OperationList.java](src/main/java/org/almostrealism/hardware/OperationList.java) |
| **PassThroughProducer** | Dynamic input placeholders | [PassThroughProducer.java](src/main/java/org/almostrealism/hardware/PassThroughProducer.java) |

### Backend Packages

The hardware module includes comprehensive implementations for multiple acceleration backends:

| Backend Package | Purpose | Key Classes |
|----------------|---------|-------------|
| **[cl](src/main/java/org/almostrealism/hardware/cl/)** | OpenCL GPU/CPU acceleration | CLDataContext, CLMemoryProvider, CLOperator |
| **[metal](src/main/java/org/almostrealism/hardware/metal/)** | Apple Metal GPU acceleration | MetalDataContext, MetalMemoryProvider, MTLDevice |
| **[jni](src/main/java/org/almostrealism/hardware/jni/)** | Native C execution via JNI | NativeCompiler, NativeExecution, NativeDataContext |
| **[mem](src/main/java/org/almostrealism/hardware/mem/)** | Memory management abstractions | MemoryProvider, Heap, RAM, Bytes |
| **[ctx](src/main/java/org/almostrealism/hardware/ctx/)** | Context management | AbstractDataContext, AbstractComputeContext |
| **[instructions](src/main/java/org/almostrealism/hardware/instructions/)** | Kernel compilation and caching | InstructionsManager, InstructionSetCompiler |

> **📚 API Documentation:** Comprehensive JavaDoc is available for all 128+ classes in the hardware module. Generate it with `mvn javadoc:aggregate` and view at `target/site/apidocs/index.html`.

## Core Concepts

### MemoryData: Hardware-Accessible Data

`MemoryData` is the fundamental interface for all data that can be transferred to/from hardware accelerators:

```java
PackedCollection<?> data = new PackedCollection<>(1000);
data.setMem(0, 3.14);  // Write to memory
double value = data.toDouble(0);  // Read from memory

// Transfer to GPU (automatic)
Producer<?> p = p(data);  // Wraps as Producer
p.get().evaluate();  // Data transferred to GPU, computation executed
```

**Key Features:**
- Unified interface for heap, off-heap, and device memory
- Zero-copy views via delegation
- Automatic hardware transfer
- Traversal policies for complex layouts

### Bulk Memory Copy Operations

`MemoryData` supports efficient bulk copy operations between memory regions:

```java
// Copy entire collection to another (same size)
PackedCollection<?> source = new PackedCollection<>(1000);
PackedCollection<?> target = new PackedCollection<>(1000);
target.setMem(0, source);  // Copy all of source to target at offset 0

// Copy with offsets and length
target.setMem(targetOffset, source, srcOffset, length);

// Copy a range starting at target offset 0
target.setMem(source, srcOffset, length);
```

**Using `MemoryDataCopy` for Explicit Control:**

For low-level control over memory copy operations:

```java
import org.almostrealism.hardware.mem.MemoryDataCopy;

// Create and execute a copy operation
MemoryDataCopy copy = new MemoryDataCopy("my copy", source, target);
copy.get().run();

// With length limit
MemoryDataCopy copy = new MemoryDataCopy("partial",
    () -> source, () -> target, length);
copy.get().run();
```

**Using `CodeFeatures.copy()` (Producer Pattern - Recommended):**

For hardware-accelerated copy between producers:

```java
import org.almostrealism.CodeFeatures;

public class MyProcessor implements CodeFeatures {
    public void copyData() {
        Supplier<Runnable> copyOp = copy("my copy", sourceProducer, targetProducer, length);
        copyOp.get().run();  // Execute the copy
    }
}
```

**Using `into()` Pattern for Evaluated Results:**

```java
// Evaluate producer directly into existing collection
producer.get().into(destination).evaluate();

// Example: normalize and store in-place
normalize(cp(vector)).into(vector).evaluate();
```

> **Performance Note:** `setMem(MemoryData)` is significantly more efficient than element-by-element loops. Use bulk operations whenever possible.

### OperationList: Composing Operations

`OperationList` combines multiple operations into a single executable unit:

```java
OperationList ops = new OperationList("Training Step");
ops.add(forwardPass);
ops.add(backwardPass);
ops.add(updateWeights);

// Can compile to single kernel if all ops are Computations
Runnable training = ops.get();
training.run();  // Executes entire sequence
```

**Dual Execution Strategy:**
- **Compiled**: All operations merged into single kernel (fast)
- **Sequential**: Operations executed one-by-one (flexible)

#### ⚠️ CRITICAL: Call optimize() Before get()

When using OperationList with computations that require isolation (like LoopedWeightedSumComputation), you **MUST** call `optimize()` before `get()`:

```java
// CORRECT: Call optimize() before get()
OperationList op = model.getForward().push(input);
op = (OperationList) op.optimize();  // Required for isolation!
Runnable compiled = op.get();
compiled.run();

// INCORRECT: May cause timeouts and massive expression trees!
OperationList op = model.getForward().push(input);
Runnable compiled = op.get();  // Missing optimize() call!
```

`OperationList.enableAutomaticOptimization` is `false` by default. Either:
1. Call `optimize()` explicitly
2. Set `OperationList.enableAutomaticOptimization = true`
3. Use `CompiledModel` which calls `optimize()` internally

See [relation/README.md](../relation/README.md) for Process optimization details.

### PassThroughProducer: Dynamic Inputs

`PassThroughProducer` creates placeholder inputs that allow kernel reuse:

```java
// Create filter with dynamic input
Producer<?> input = v(shape(1000), 0);  // Argument 0: dynamic
MultiOrderFilter filter = highPass(input, c(1000.0), 44100);

// Reuse filter with different data
filter.get().evaluate(data1);
filter.get().evaluate(data2);  // Same kernel, different data
```

**Benefits:**
- Kernel compiled once, reused many times
- No recompilation overhead
- Supports variable-size inputs

### Instruction Caching

The `instruct()` pattern caches compiled operations for reuse:

```java
// First call: compiles and caches
Producer<?> result1 = instruct("scale_2x",
    args -> multiply(args[0], c(2.0)),
    data1
);

// Subsequent calls: reuse cached kernel
Producer<?> result2 = instruct("scale_2x",
    args -> multiply(args[0], c(2.0)),
    data2  // Only recomputation: argument substitution
);
```

**Performance Impact:**
- First call: ~100ms (compilation)
- Subsequent calls: ~0.01ms (substitution only)
- **10,000x speedup** for repeated operations

## Memory Management Patterns

The hardware module implements sophisticated memory management strategies to minimize allocation overhead, enable zero-copy operations, and automatically handle cross-provider transfers.

### Zero-Copy Delegation

`MemoryDataAdapter` enables zero-copy views into existing memory through delegation:

```java
// Original memory block
PackedCollection<?> original = new PackedCollection<>(10000);

// Zero-copy view of elements 100-200 (no data copied)
MemoryData view = new Bytes(100, original, 100);

// Modifications to view affect original
view.setMem(0, 42.0);  // Also modifies original at offset 100
```

**Use Cases:**
- Reshaping collections without copying
- Extracting subarrays for operations
- Implementing sliding windows over data streams

**Safety Constraints:**
- Delegation depth limited to 25 levels
- Circular references prevented automatically
- Bounds checking enforced

### Thread-Local Arena Allocation (Heap)

`Heap` provides thread-local arena allocation for temporary memory:

```java
// Create heap for temporary allocations
Heap heap = new Heap(10000);
heap.use(() -> {
    // Temporary allocations come from heap (fast)
    Bytes temp1 = Heap.getDefault().allocate(100);
    Bytes temp2 = Heap.getDefault().allocate(50);

    // Use temporaries...

    // Auto-destroyed when scope exits
});
```

**Benefits:**
- Single large allocation instead of many small ones
- Automatic cleanup via staged allocation
- Thread-local default heap for implicit usage

**Staged Allocation:**

```java
Heap.stage(() -> {
    // Allocations in stage 1
    Bytes temp1 = Heap.getDefault().allocate(100);

    Heap.stage(() -> {
        // Nested stage 2
        Bytes temp2 = Heap.getDefault().allocate(50);
        // temp2 destroyed on exit
    });

    // temp1 still valid here
});
// All stage allocations destroyed
```

### GC-Integrated Native Memory

`HardwareMemoryProvider` integrates native memory with Java's garbage collector:

```java
// When Java object is GC'd, native memory is automatically freed
RAM memory = provider.allocate(1000);
// NativeRef (phantom reference) created automatically

memory = null;  // Only reference lost
System.gc();    // Eventually: native memory freed automatically
```

**How It Works:**
1. Allocation creates `NativeRef` (phantom reference) tracking address/size
2. Reference registered with `ReferenceQueue`
3. Background threads monitor queue for GC'd objects
4. Native memory freed when reference appears in queue

**Leak Detection:**

```java
// If memory not destroyed, allocation stack trace preserved
RAM leaked = findLeakedMemory();
for (StackTraceElement frame : leaked.getAllocationStackTrace()) {
    System.err.println("  at " + frame);
}
// Output shows exact allocation location
```

**Configuration:**

```bash
# Enable/disable allocation tracking
export AR_HARDWARE_ALLOCATION_TRACE_FRAMES=16  # Capture 16 frames (default)
export AR_HARDWARE_ALLOCATION_TRACE_FRAMES=0   # Disable (production)

# Enable/disable warnings
export AR_HARDWARE_MEMORY_WARNINGS=true
```

### Memory Versioning

`MemoryDataAdapter` caches memory versions for different providers:

```java
MemoryData data = new Bytes(1000);

// Allocate on CPU provider
data.reallocate(cpuProvider);  // Allocates + copies

// Switch to GPU provider
data.reallocate(gpuProvider);  // Allocates GPU, keeps CPU version

// Switch back to CPU
data.reallocate(cpuProvider);  // Reuses cached CPU version (fast!)
```

**Benefits:**
- Eliminates redundant transfers when switching providers
- Cached versions reused automatically
- Transparent to calling code

### Argument Aggregation

`MemoryDataArgumentMap` automatically aggregates small memory arguments:

```java
// Without aggregation: 3 separate kernel arguments
kernel.execute(cpuMem1, cpuMem2, cpuMem3);  // 3 CPU→GPU transfers

// With aggregation (automatic):
// All 3 arguments packed into single buffer
// kernel.execute(aggregatedBuffer);  // 1 CPU→GPU transfer
```

**Aggregation Rules:**
- Enabled by default (`AR_HARDWARE_ARGUMENT_AGGREGATION=true`)
- Only aggregates memory from non-kernel providers
- Respects size threshold (default: 1MB)
- Root delegates grouped to minimize copies

**Configuration:**

```bash
# Enable/disable argument aggregation
export AR_HARDWARE_ARGUMENT_AGGREGATION=true   # Default

# Include off-heap memory in aggregation
export AR_HARDWARE_OFF_HEAP_AGGREGATION=false  # Default

# Max size for aggregation (bytes)
export AR_HARDWARE_AGGREGATE_MAX=1048576       # 1MB default
```

### Memory Replacement for Kernels

`MemoryReplacementManager` handles three-phase memory substitution:

```java
MemoryReplacementManager mgr = new MemoryReplacementManager(
    gpuProvider,
    (size, atomic) -> new Bytes(size, atomic)
);

// Process arguments: identifies CPU memory to aggregate
Object[] kernelArgs = mgr.processArguments(originalArgs);

// Phase 1: Prepare - Copy to temp aggregate
mgr.getPrepare().get().run();

// Phase 2: Execute kernel with aggregated args
kernel.execute(kernelArgs);

// Phase 3: Postprocess - Copy results back
mgr.getPostprocess().get().run();
```

**Root Delegate Grouping:**

```java
MemoryData root = new Bytes(10000);
MemoryData view1 = root.range(0, 100);    // Offset 0
MemoryData view2 = root.range(500, 200);  // Offset 500

// Both views share root → Single temp allocation
// Temp covers min offset (0) to max offset (700)
// Only 700 bytes allocated instead of 300
```

### Best Practices

**Use Delegation for Views:**
```java
// GOOD: Zero-copy view
MemoryData subset = original.range(start, length);

// AVOID: Copying data
MemoryData subset = new Bytes(length);
subset.setMem(0, original, start, length);
```

**Use Heap for Temporaries:**
```java
// GOOD: Arena allocation
Heap.stage(() -> {
    Bytes temp = Heap.getDefault().allocate(100);
    // Use temp...
});

// AVOID: Individual allocations
Bytes temp = new Bytes(100);
// ... use temp ...
temp.destroy();
```

**Leverage Argument Aggregation:**
```java
// Aggregation happens automatically when:
// 1. Multiple small arguments (<1MB each)
// 2. From non-kernel provider (e.g., CPU → GPU)
// 3. AR_HARDWARE_ARGUMENT_AGGREGATION=true

// No code changes needed - just configure environment
export AR_HARDWARE_ARGUMENT_AGGREGATION=true
```

**Explicit Cleanup for Long-Lived Objects:**
```java
// GC integration handles most cases, but for long-lived objects:
MemoryData data = new Bytes(largeSize);
try {
    // Use data...
} finally {
    data.destroy();  // Explicit cleanup
}
```

## Environment Configuration

### Required Variables

```bash
# Directory for generated libraries (REQUIRED)
export AR_HARDWARE_LIBS=/tmp/ar_libs/

# Execution backend (REQUIRED)
export AR_HARDWARE_DRIVER=native
```

### Backend Selection

```bash
# CPU Backends
export AR_HARDWARE_DRIVER=native  # JNI (fast CPU execution)
export AR_HARDWARE_DRIVER=cpu     # Abstract CPU (auto-selects)

# GPU Backends
export AR_HARDWARE_DRIVER=cl      # OpenCL (cross-platform GPU)
export AR_HARDWARE_DRIVER=mtl     # Metal (Apple Silicon GPU)
export AR_HARDWARE_DRIVER=gpu     # Abstract GPU (auto-selects)

# Multi-Backend
export AR_HARDWARE_DRIVER=cl,native  # OpenCL + JNI fallback

# Auto-Select (recommended for development)
export AR_HARDWARE_DRIVER=*
```

### Precision Configuration

```bash
# Use 32-bit floats (faster GPU execution)
export AR_HARDWARE_PRECISION=FP32

# Use 64-bit doubles (default, higher precision)
export AR_HARDWARE_PRECISION=FP64
```

### Memory Configuration

```bash
# Maximum memory allocation (2^SCALE × 64MB)
export AR_HARDWARE_MEMORY_SCALE=4   # 1GB (default)
export AR_HARDWARE_MEMORY_SCALE=6   # 4GB
export AR_HARDWARE_MEMORY_SCALE=8   # 16GB

# Memory location (OpenCL only)
export AR_HARDWARE_MEMORY_LOCATION=device   # GPU memory (fastest)
export AR_HARDWARE_MEMORY_LOCATION=host     # System RAM
export AR_HARDWARE_MEMORY_LOCATION=delegate # Native buffer

# Enable shared memory (Apple Silicon unified memory)
export AR_HARDWARE_NIO_MEMORY=true
```

### Development vs Production

**Development (Fast Compilation, Easy Debugging):**
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
export AR_HARDWARE_PRECISION=FP64
```

**Production GPU (Maximum Performance):**
```bash
export AR_HARDWARE_LIBS=/var/ar_libs/
export AR_HARDWARE_DRIVER=gpu
export AR_HARDWARE_PRECISION=FP32
export AR_HARDWARE_MEMORY_SCALE=6
export AR_HARDWARE_MEMORY_LOCATION=device
```

## Common Usage Patterns

### Pattern 1: Feature Interface Mixin

```java
public class MyProcessor implements HardwareFeatures {
    public void process() {
        // All HardwareFeatures methods available
        Producer<?> a = cp(data1);
        Producer<?> b = cp(data2);
        Producer<?> result = multiply(a, b);

        result.get().evaluate();
    }
}
```

### Pattern 2: Cached Operations

```java
public class FilterBank implements HardwareFeatures {
    private static final String LOWPASS_KEY = "lowpass_1000hz";

    public Producer<?> lowpass(Producer<?> input) {
        // Kernel cached under LOWPASS_KEY
        return instruct(LOWPASS_KEY,
            args -> lowPass(args[0], c(1000.0), 44100),
            input
        );
    }
}
```

### Pattern 3: Pre-Allocated Output

```java
// Avoid repeated allocation in loops
PackedCollection<?> output = new PackedCollection<>(1000);

for (int i = 0; i < iterations; i++) {
    computation.get()
        .into(output.traverseEach())
        .evaluate(input);

    // Process output...
}
```

### Pattern 4: GPU/CPU Switching

```java
OperationList gpuOps = new OperationList("GPU Phase");
gpuOps.setComputeRequirements(List.of(ComputeRequirement.GPU));
gpuOps.add(heavyComputation);

OperationList cpuOps = new OperationList("CPU Phase");
cpuOps.setComputeRequirements(List.of(ComputeRequirement.CPU));
cpuOps.add(lightComputation);

// Execute with proper backends
gpuOps.get().run();  // On GPU
cpuOps.get().run();  // On CPU
```

### Pattern 5: MemoryBank for Batch Operations

```java
// Single allocation for 1000 vectors
MemoryBank<Vector> vectors = Vector.bank(1000);

// Populate
for (int i = 0; i < 1000; i++) {
    vectors.get(i).setX(i);
}

// Transfer entire bank to GPU as single operation
Producer<?> p = cp(vectors);
computation.get().evaluate();
```

## Performance Optimization

### 1. Use Instruction Caching

```java
// ✗ BAD: Recompiles every time
for (int i = 0; i < 1000; i++) {
    multiply(v1, v2).get().evaluate();
}

// ✓ GOOD: Compile once, reuse 1000 times
Evaluable<?> cached = multiply(v1, v2).get();
for (int i = 0; i < 1000; i++) {
    cached.evaluate();
}

// ✓ BEST: Use instruct() for automatic caching
Producer<?> result = instruct("multiply",
    args -> multiply(args[0], args[1]),
    v1, v2
);
for (int i = 0; i < 1000; i++) {
    result.get().evaluate();  // Automatic kernel reuse
}
```

### 2. Use PassThroughProducer for Kernel Reuse

```java
// ✗ BAD: Bakes data into kernel
Producer<?> static = multiply(cp(data), c(2.0));
static.get().evaluate();  // Can't reuse with different data

// ✓ GOOD: Dynamic input allows reuse
Producer<?> dynamic = multiply(v(shape(1000), 0), c(2.0));
dynamic.get().evaluate(data1);
dynamic.get().evaluate(data2);  // Same kernel, different data
```

### 3. Minimize Memory Transfers

```java
// ✗ BAD: Multiple transfers
PackedCollection<?> a = new PackedCollection<>(1000);
operation1.get().into(a.traverseEach()).evaluate();
PackedCollection<?> b = new PackedCollection<>(1000);
operation2.get().into(b.traverseEach()).evaluate();

// ✓ GOOD: Compose on GPU
OperationList composed = new OperationList();
composed.add(operation1);
composed.add(operation2);
composed.get().run();  // Single transfer of final result
```

### 4. Choose Appropriate Backend

```java
// Sequential operations → CPU
if (count == 1) {
    op.setComputeRequirements(List.of(ComputeRequirement.CPU));
}

// Parallel operations → GPU
if (count > 1000) {
    op.setComputeRequirements(List.of(ComputeRequirement.GPU));
}
```

### 5. Use MemoryBank for Batch Data

```java
// ✗ BAD: Individual allocations
for (int i = 0; i < 1000; i++) {
    PackedCollection<?> v = new PackedCollection<>(3);
    // 1000 separate GPU buffers
}

// ✓ GOOD: Single bank allocation
MemoryBank<PackedCollection<?>> bank =
    new MemoryBankAdapter<>(1000, 3, ...);
// 1 GPU buffer, 1000 elements
```

## Advanced Topics

### Custom ComputeRequirements

```java
// Stack-based requirements
DefaultComputer computer = Hardware.getLocalHardware().getComputer();

computer.pushRequirements(List.of(ComputeRequirement.GPU));
try {
    // All operations in this block prefer GPU
    operation.get().run();
} finally {
    computer.popRequirements();
}
```

### Shared Memory (Apple Silicon)

```bash
# Enable unified memory between Metal and JNI
export AR_HARDWARE_DRIVER=*
export AR_HARDWARE_NIO_MEMORY=true

# JNI now shares memory with Metal GPU
# Zero-copy data sharing between CPU and GPU operations
```

### Profiling

```java
OperationProfile profile = new DefaultProfile();
Hardware.getLocalHardware().assignProfile(profile);

// Run operations...
computation.get().run();

// Analyze timing
System.out.println("Total: " + profile.getTotalTime());
System.out.println("Compilation: " + profile.getCompilationTime());
System.out.println("Execution: " + profile.getExecutionTime());

Hardware.getLocalHardware().clearProfile();
```

### Depth Limits

```bash
# Control maximum OperationList nesting
export AR_HARDWARE_MAX_DEPTH=1000
```

Or programmatically:
```java
Hardware.getLocalHardware().setMaximumOperationDepth(1000);

// Deep lists can be flattened
OperationList deep = ...;
if (deep.getDepth() > 500) {
    deep = deep.flatten();
}
```

## Troubleshooting

### Error: NoClassDefFoundError

**Cause:** Missing `AR_HARDWARE_LIBS` environment variable.

**Solution:**
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
```

### Slow First Execution

**Cause:** Kernel compilation overhead on first run.

**Expected:** First execution takes 100-1000ms, subsequent executions take 1-10ms.

**Solution:** Use instruction caching patterns to amortize compilation cost.

### OperationList Not Compiling

**Cause:** Mixed Computation and non-Computation operations.

**Check:**
```java
boolean canCompile = ops.isComputation();
System.out.println("Compilable: " + canCompile);
```

**Solution:** Separate compiled and non-compiled operations.

### GPU Out of Memory

**Cause:** Insufficient GPU memory allocation.

**Solution:**
```bash
# Increase memory scale
export AR_HARDWARE_MEMORY_SCALE=6  # 4GB

# Or use host memory
export AR_HARDWARE_MEMORY_LOCATION=host
```

## Module Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-hardware</artifactId>
    <version>${project.version}</version>
</dependency>
```

**Depends on:**
- `ar-code` - Computation and scope abstractions
- `ar-io` - Console and logging utilities
- `ar-relation` - Producer and evaluation framework

**Used by:**
- `ar-algebra` - PackedCollection implementations
- `ar-time` - Temporal operations
- `ar-graph` - Neural network layers
- `ar-ml` - Machine learning models

## Contributing

When adding new hardware-accelerated operations:

1. Implement `HardwareFeatures` for convenient access
2. Use `Computation` interface for GPU-compilable operations
3. Provide JavaDoc with usage examples
4. Add performance tests measuring compilation and execution overhead
5. Document any new environment variables

## License

Copyright 2025 Michael Murray

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
