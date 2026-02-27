# Heap: Thread-Local Arena Allocator

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
  - [Memory Layout](#memory-layout)
  - [Class Structure](#class-structure)
- [API Reference](#api-reference)
  - [Constructors](#constructors)
  - [Instance Methods](#instance-methods)
  - [Static Methods](#static-methods)
  - [HeapStage](#heapstage)
  - [HeapDependencies](#heapdependencies)
- [Usage Patterns](#usage-patterns)
  - [Basic Arena Allocation](#basic-arena-allocation)
  - [Scoped Stages](#scoped-stages)
  - [Nested Stages](#nested-stages)
  - [Deferred Execution](#deferred-execution)
- [Framework Integration](#framework-integration)
  - [PackedCollection.factory()](#packedcollectionfactory)
  - [DefaultComputer.compileRunnable()](#defaultcomputercompilerunnable)
  - [ProcessDetailsFactory](#processdetailsfactory)
  - [getDefaultDelegate() Pattern](#getdefaultdelegate-pattern)
  - [Heap Guards](#heap-guards)
- [Dependency Tracking](#dependency-tracking)
  - [Dependent Operations](#dependent-operations)
  - [Compiled Operations](#compiled-operations)
  - [Created Memory](#created-memory)
  - [Relationship to Instruction Caching](#relationship-to-instruction-caching)
- [Lifecycle Management](#lifecycle-management)
  - [Stage Lifecycle](#stage-lifecycle)
  - [Heap Lifecycle](#heap-lifecycle)
  - [Destroy Order](#destroy-order)
- [Thread Safety](#thread-safety)
- [Common Pitfalls](#common-pitfalls)

---

## Overview

`Heap` is a thread-local arena allocator that reduces allocation overhead for short-lived memory. Instead of making many individual calls to the underlying `MemoryProvider`, a `Heap` pre-allocates a single large memory block and serves requests by advancing a bump pointer within that block. This turns O(n) provider calls into a single allocation plus O(1) pointer bumps.

The two core responsibilities are:

1. **Arena allocation**: Fast suballocation of `Bytes` instances from pre-allocated memory blocks
2. **Dependency lifecycle management**: Automatic cleanup of compiled operations, dependent operations, and temporary memory when a scope exits

### When to Use Heap

- **Temporary allocations during computation evaluation**: Wrap evaluation in `heap.use(() -> ...)` so all intermediate `PackedCollection` instances allocate from the heap
- **Scoped compilation**: Wrap compilation and execution in `Heap.stage(() -> ...)` so compiled operations are freed when the scope exits
- **Batch processing loops**: Create a heap once, process many items with `Heap.stage()` per item, and destroy the heap when done

### When Not to Use Heap

- **Long-lived data**: Heap allocations are destroyed when the stage exits; data that must outlive the scope should use standard allocation
- **Components with PassThroughProducer**: `AudioSumProvider` and `AudioProcessingUtils` throw `RuntimeException` if a heap is active during construction

---

## Architecture

### Memory Layout

```
+------------------------- Heap --------------------------+
|                                                          |
|  +------------ Root Stage (rootSize) ----------------+  |
|  |  [alloc1][alloc2][alloc3]...        [free space]   |  |
|  |  ^                           ^end                  |  |
|  |  HeapDependencies: ops, compiled, memory           |  |
|  +----------------------------------------------------+  |
|                                                          |
|  +------------ Pushed Stage (stageSize) --------------+  |
|  |  [alloc4][alloc5]...                [free space]   |  |
|  |  ^                     ^end                        |  |
|  |  HeapDependencies: ops, compiled, memory           |  |
|  +----------------------------------------------------+  |
|                                                          |
|  (additional pushed stages as needed)                    |
+----------------------------------------------------------+
```

Each stage is an independent bump allocator with its own backing `Bytes` block, bump pointer (`end`), and `HeapDependencies` tracker. The root stage is created at construction time. Additional stages are pushed and popped via `push()`/`pop()` (or, more commonly, via the `stage()` static method).

### Class Structure

```
Heap
 +-- HeapStage (public inner class, implements Destroyable)
 |    +-- entries: List<Bytes>          (allocated views)
 |    +-- data: Bytes                   (backing block)
 |    +-- end: int                      (bump pointer)
 |    +-- dependencies: HeapDependencies
 |
 +-- HeapDependencies (private inner class, implements Destroyable)
      +-- dependentOperations: List<Supplier>
      +-- compiledDependencies: List<OperationAdapter>
      +-- createdMemory: List<MemoryData>
```

**Key relationships:**
- `Heap` owns one `root` HeapStage and a `Stack<HeapStage>` of pushed stages
- Each `HeapStage` owns one `HeapDependencies` instance
- `Heap` uses a `ThreadLocal<Heap>` for the thread-local default

---

## API Reference

### Constructors

#### `Heap(int size)`

Creates a heap with root size `size` and stage size `size / 4`.

```java
Heap heap = new Heap(100_000);  // 100K root, 25K per stage
```

#### `Heap(int rootSize, int stageSize)`

Creates a heap with explicit root and stage sizes.

```java
Heap heap = new Heap(100_000, 10_000);  // 100K root, 10K per stage
```

#### `Heap(MemoryProvider memory, int rootSize, int stageSize)`

Creates a heap using a specific memory provider. When `memory` is null, stages use the default `Bytes` constructor (which delegates to `Hardware.getLocalHardware().getMemoryProvider()`). When non-null, stages allocate via `Bytes.of(memory.allocate(size), size)`.

```java
MemoryProvider provider = Hardware.getLocalHardware().getMemoryProvider(100_000);
Heap heap = new Heap(provider, 100_000, 25_000);
```

### Instance Methods

#### `HeapStage getStage()`

Returns the currently active stage. If pushed stages exist, returns the top of the stack. Otherwise returns the root stage.

#### `Bytes allocate(int count)`

Allocates `count` memory units from the active stage. Returns a zero-copy `Bytes` view into the stage's backing block.

**Throws:** `IllegalArgumentException` if insufficient space remains.

```java
Bytes temp = heap.allocate(1000);
```

#### `<T> Callable<T> wrap(Callable<T> r)`

Returns a callable that activates this heap as the thread-local default during execution and restores the previous default afterwards.

```java
Callable<PackedCollection> wrapped = heap.wrap(() -> {
    return someProducer.get().evaluate();
});
// Later, when called:
PackedCollection result = wrapped.call();  // heap is active during execution
```

#### `Heap use(Runnable r)`

Executes a runnable with this heap as the thread-local default. Restores the previous default in a `finally` block. Returns `this` for chaining.

```java
Heap heap = new Heap(10_000);
heap.use(() -> {
    // Heap.getDefault() == heap here
    PackedCollection temp = PackedCollection.factory().apply(100);
});
```

#### `<T> T use(Supplier<T> r)`

Executes a supplier with this heap as the thread-local default and returns the result.

```java
PackedCollection result = heap.use(() -> {
    return someProducer.get().evaluate();
});
```

#### `void destroy()`

Destroys the heap and all its stages. Pops and destroys all pushed stages (top to bottom), then destroys the root stage. Synchronized.

### Static Methods

#### `Heap getDefault()`

Returns the thread-local default heap for the current thread, or `null` if none is active. This is the primary check used by framework components to decide between heap-backed and standard allocation.

#### `void stage(Runnable r)`

Executes a runnable within a nested heap stage. This is the primary API for scoped allocation.

**Behavior:**
- If `getDefault()` is `null`: executes the runnable directly (no staging)
- If `getDefault()` is non-null: pushes a new stage, executes the runnable, pops and destroys the stage in a `finally` block

```java
Heap.stage(() -> {
    // All allocations and compilations within this scope
    // are tracked and freed when the scope exits
    PackedCollection temp = PackedCollection.factory().apply(500);
    Runnable compiled = someComputation.get();
    compiled.run();
});
// temp and compiled are freed here (if heap was active)
```

#### `<T> Supplier<T> addOperation(Supplier<T> operation)`

Registers a dependent operation with the active stage. No-op if no default heap. Returns the operation for chaining.

#### `<T extends OperationAdapter> T addCompiled(T operation)`

Registers a compiled operation with the active stage. No-op if no default heap. Returns the operation for chaining.

#### `<T extends MemoryData> T addCreatedMemory(T memory)`

Registers created memory with the active stage. No-op if no default heap. Returns the memory for chaining.

### HeapStage

`HeapStage` is a public inner class implementing `Destroyable`. It functions as a bump allocator over a pre-allocated `Bytes` block.

| Method | Description |
|--------|-------------|
| `HeapStage(int size)` | Creates a stage with a backing block of the given size |
| `synchronized Bytes allocate(int count)` | Bump-pointer allocation returning a zero-copy `Bytes` view |
| `Bytes get(int index)` | Returns the allocation at the given index |
| `Bytes getBytes()` | Returns the backing memory block |
| `Stream<Bytes> stream()` | Streams all allocations in order |
| `void destroy()` | Clears entries, resets pointer, destroys backing block and dependencies |

**Allocation mechanism:**
1. Check `end + count <= data.getMemLength()`
2. Create `new Bytes(count, data, end)` (zero-copy view)
3. Advance `end += count`
4. Add to entries list

### HeapDependencies

`HeapDependencies` is a private inner class implementing `Destroyable`. It tracks three categories of resources.

| Field | Type | Registered By | Destroy Action |
|-------|------|---------------|----------------|
| `dependentOperations` | `List<Supplier>` | `Heap.addOperation()` | `destroy()` if `Destroyable` |
| `compiledDependencies` | `List<OperationAdapter>` | `Heap.addCompiled()` | `OperationAdapter.destroy()` |
| `createdMemory` | `List<MemoryData>` | `Heap.addCreatedMemory()` | `MemoryData.destroy()` |

**Destruction order:** operations, then compiled, then memory. Each list is set to `null` after processing.

---

## Usage Patterns

### Basic Arena Allocation

```java
// Create a heap for temporary work
Heap heap = new Heap(50_000);

heap.use(() -> {
    // All allocations come from the heap's pre-allocated block
    Bytes workspace1 = Heap.getDefault().allocate(1000);
    Bytes workspace2 = Heap.getDefault().allocate(500);

    // PackedCollection.factory() also uses the heap when active
    PackedCollection temp = PackedCollection.factory().apply(200);
});

heap.destroy();
```

### Scoped Stages

```java
Heap heap = new Heap(100_000);
heap.use(() -> {
    // Root-level work
    Bytes longLived = Heap.getDefault().allocate(5000);

    for (int i = 0; i < 100; i++) {
        Heap.stage(() -> {
            // Per-iteration temporaries
            PackedCollection temp = PackedCollection.factory().apply(100);
            Runnable op = someComputation.get();
            op.run();
            // temp and op freed here
        });
    }

    // longLived still valid here
});
heap.destroy();
```

### Nested Stages

```java
Heap.stage(() -> {
    Bytes outer = Heap.getDefault().allocate(100);

    Heap.stage(() -> {
        Bytes inner = Heap.getDefault().allocate(50);
        // inner freed when this stage exits
    });

    // outer still valid; inner is now invalid
});
// outer freed when this stage exits
```

### Deferred Execution

```java
Heap heap = new Heap(10_000);
Callable<PackedCollection> deferred = heap.wrap(() -> {
    return someProducer.get().evaluate();
});

// Later, in a different context:
PackedCollection result = deferred.call();
// heap was active during the call
```

---

## Framework Integration

### PackedCollection.factory()

`PackedCollection.factory()` checks `Heap.getDefault()`:

```java
// From PackedCollection:
public static IntFunction<PackedCollection> factory() {
    Heap heap = Heap.getDefault();
    return heap == null ? PackedCollection::new : factory(heap::allocate);
}
```

When a heap is active, the factory returns `PackedCollection` instances backed by `Bytes` views into the heap's current stage. This means any code that creates `PackedCollection` via `factory()` automatically benefits from arena allocation when a heap is active.

### DefaultComputer.compileRunnable()

Every `Computation<Void>` compiled via `DefaultComputer.compileRunnable()` is registered with the heap:

```java
// From DefaultComputer:
public Runnable compileRunnable(Computation<Void> c) {
    return Heap.addCompiled(new AcceleratedComputationOperation<>(getContext(c), c, true));
}
```

This ensures that when a heap stage is destroyed, the compiled operation's `destroy()` method is called, releasing the `ScopeInstructionsManager` and associated native kernel resources.

### ProcessDetailsFactory

When preparing kernel arguments that need sized destination buffers, `ProcessDetailsFactory` registers the temporary memory:

```java
// From ProcessDetailsFactory:
MemoryData result = (MemoryData) kernelArgEvaluables[i].createDestination(size);
Heap.addCreatedMemory(result);
```

This ensures temporary argument buffers are freed when the enclosing stage exits.

### getDefaultDelegate() Pattern

Several `MemoryDataAdapter` subclasses override `getDefaultDelegate()` to return `Heap.getDefault()`, enabling automatic heap-backed allocation when `init()` is called:

| Class | Location |
|-------|----------|
| `Vector` | `algebra/src/.../Vector.java` |
| `Pair` | `algebra/src/.../Pair.java` |
| `TransformMatrix` | `geometry/src/.../TransformMatrix.java` |
| `RGBData192` | `color/src/.../RGBData192.java` |
| `PolymorphicAudioData` | `audio/src/.../PolymorphicAudioData.java` |

When `MemoryDataAdapter.init()` is called and `getDefaultDelegate()` returns a non-null heap:

```java
// From MemoryDataAdapter.init():
protected void init() {
    if (getDelegate() == null) {
        Heap heap = getDefaultDelegate();
        if (heap == null) {
            mem = Hardware.getLocalHardware().getMemoryProvider(getMemLength()).allocate(getMemLength());
        } else {
            Bytes data = heap.allocate(getMemLength());
            setDelegate(data.getDelegate(), data.getDelegateOffset(), data.getDelegateOrdering());
            setMem(new double[getMemLength()]);
        }
    }
}
```

This means creating a `Vector`, `Pair`, etc. while a heap is active automatically allocates from the heap.

### Heap Guards

Components that compile kernels with `PassThroughProducer` dynamic inputs must not be instantiated with a heap active, because the heap would interfere with argument setup:

```java
// From AudioSumProvider constructor:
if (Heap.getDefault() != null) {
    throw new RuntimeException();
}
```

The same guard exists in `AudioProcessingUtils`. These components should be created before activating a heap.

---

## Dependency Tracking

### Dependent Operations

Registered via `Heap.addOperation(Supplier<T>)`. These are generic operation references that may need cleanup. On stage destruction, each supplier's `destroy()` method is called if it implements `Destroyable`.

### Compiled Operations

Registered via `Heap.addCompiled(OperationAdapter)`. This is the most performance-critical category. When a computation is compiled (e.g., via `DefaultComputer.compileRunnable()`), the resulting `AcceleratedComputationOperation` is registered here. On stage destruction, `OperationAdapter.destroy()` is called, which releases the `ScopeInstructionsManager` and its native `InstructionSet`.

### Created Memory

Registered via `Heap.addCreatedMemory(MemoryData)`. Temporary buffers created during kernel argument preparation by `ProcessDetailsFactory`. On stage destruction, `MemoryData.destroy()` is called to deallocate the native memory.

### Relationship to Instruction Caching

Heap dependency tracking and instruction set caching are **independent systems**:

- **Heap**: Tracks compiled operation instances for lifecycle management. When a stage is destroyed, the operation wrapper is destroyed.
- **Instruction cache**: `DefaultComputer` maintains a `FrequencyCache<String, ScopeInstructionsManager>` (capacity 500, eviction factor 0.4) keyed by computation signature. The same compiled kernel can be reused by future operations with the same signature.

When a heap stage destroys a compiled operation, the underlying `ScopeInstructionsManager` may still exist in the instruction cache if its signature has not been evicted. A future compilation with the same signature will retrieve the cached manager and reuse the compiled kernel, performing only argument substitution (via `ProcessArgumentMap.putSubstitutions()`) instead of full recompilation.

This means `Heap.stage()` does **not** invalidate the instruction cache. It only frees the operation wrapper and its per-invocation resources.

---

## Lifecycle Management

### Stage Lifecycle

1. **Creation**: `push()` creates a `HeapStage` with a backing block of size `stageSize`
2. **Allocation**: `allocate(count)` advances the bump pointer and returns a `Bytes` view
3. **Dependency registration**: `addCompiled()`, `addOperation()`, `addCreatedMemory()` add to the stage's `HeapDependencies`
4. **Destruction**: `pop()` calls `HeapStage.destroy()`, which:
   - Clears entries and resets the bump pointer
   - Destroys the backing `Bytes` block
   - Destroys all dependencies (operations, compiled ops, memory)

### Heap Lifecycle

1. **Construction**: Allocates root stage with given size
2. **Activation**: `use(Runnable)` or `use(Supplier)` sets as thread-local default
3. **Operation**: Allocations and dependency registrations target the active stage
4. **Stage cycling**: `stage(Runnable)` creates temporary nested stages
5. **Destruction**: `destroy()` pops all stages and destroys root

### Destroy Order

When a `HeapDependencies` instance is destroyed:

1. **Dependent operations** first (they may reference compiled ops or memory)
2. **Compiled operations** second (kernel teardown may access argument memory)
3. **Created memory** last (safe to free after all references are released)

Each list is set to `null` after processing to prevent double-destruction.

---

## Thread Safety

- **Thread-local isolation**: The `defaultHeap` `ThreadLocal` ensures each thread has its own default heap. Different threads cannot interfere with each other.
- **Synchronized allocate()**: `HeapStage.allocate()` is `synchronized` to guard against re-entrant allocation from the same thread (e.g., during operation compilation callbacks).
- **Synchronized destroy()**: `Heap.destroy()` is `synchronized` to prevent concurrent destruction.
- **Not designed for cross-thread sharing**: A single `Heap` instance should be used by one thread at a time. Do not share a heap between threads.

---

## Common Pitfalls

### Holding References Past Stage Exit

```java
Bytes[] retained = new Bytes[1];
Heap.stage(() -> {
    retained[0] = Heap.getDefault().allocate(100);
});
// retained[0] is INVALID here - the backing memory was destroyed
retained[0].setMem(0, 42.0);  // UNDEFINED BEHAVIOR
```

### Exceeding Stage Capacity

```java
Heap heap = new Heap(1000);
heap.use(() -> {
    heap.allocate(800);
    heap.allocate(300);  // IllegalArgumentException: No room remaining
});
```

Size the heap appropriately for your workload, or use stages to reclaim space.

### Creating Guarded Components With Heap Active

```java
Heap heap = new Heap(10_000);
heap.use(() -> {
    AudioSumProvider sum = new AudioSumProvider();  // RuntimeException!
});
```

Create `AudioSumProvider` and `AudioProcessingUtils` before activating a heap.

### Forgetting to Destroy

```java
Heap heap = new Heap(100_000);
heap.use(() -> { /* ... */ });
// Missing heap.destroy() - root stage memory leaked!
```

Always call `destroy()` when the heap is no longer needed, or structure code so the heap is scoped to a method.
