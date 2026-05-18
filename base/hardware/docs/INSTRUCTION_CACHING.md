# Instruction Set Caching

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
  - [Cache Hierarchy](#cache-hierarchy)
  - [Class Structure](#class-structure)
  - [Data Flow](#data-flow)
- [Signatures](#signatures)
  - [What a Signature Captures](#what-a-signature-captures)
  - [Signature Generation](#signature-generation)
  - [Signature Format](#signature-format)
- [Execution Keys](#execution-keys)
  - [ScopeSignatureExecutionKey](#scopesignatureexecutionkey)
  - [DefaultExecutionKey](#defaultexecutionkey)
  - [ProcessTreePositionKey](#processtreepositionkey-deprecated)
- [Core Components](#core-components)
  - [InstructionSetManager](#instructionsetmanager)
  - [ScopeInstructionsManager](#scopeinstructionsmanager)
  - [ComputableInstructionSetManager](#computableinstructionsetmanager)
  - [ComputationInstructionsManager](#computationinstructionsmanager)
  - [FrequencyCache](#frequencycache)
- [Argument Substitution](#argument-substitution)
  - [ProcessArgumentMap](#processargumentmap)
  - [Substitution Flow](#substitution-flow)
- [Lifecycle](#lifecycle)
  - [Compilation and Caching](#compilation-and-caching)
  - [Reuse Path](#reuse-path)
  - [Eviction and Destruction](#eviction-and-destruction)
- [Configuration](#configuration)
- [Relationship to Heap](#relationship-to-heap)
- [Thread Safety](#thread-safety)
- [Common Pitfalls](#common-pitfalls)

---

## Overview

The instruction set caching system avoids redundant compilation of hardware kernels by caching compiled `InstructionSet` instances and reusing them across operations that share the same structural signature.

Compiling a computation to native code (JNI, OpenCL, Metal) is expensive: it involves scope generation, simplification, code generation, and compilation. Many operations in a computation graph have identical structure but different data arguments. The caching system exploits this by:

1. **Computing a signature** for each operation based on its type, shapes, and input structure (not data values)
2. **Caching the compiled kernel** in a `FrequencyCache` keyed by signature
3. **Substituting arguments** when the same kernel is reused by a different operation instance

### Key Insight

Two operations are "structurally identical" if they perform the same computation on inputs of the same shapes. For example, all `Add` operations on 3x2 matrices with the same precision produce the same kernel code. The only difference is which memory buffers they read from and write to. The caching system compiles the kernel once and substitutes the memory pointers for each use.

---

## Architecture

### Cache Hierarchy

`DefaultComputer` maintains three levels of caching:

```
+-----------------------------------------------------------+
|                    DefaultComputer                         |
|-----------------------------------------------------------|
|                                                           |
|  operationsCache (Map)                                    |
|  +-- Instruction containers (unlimited)                   |
|      Used by HardwareFeatures.instruct() pattern          |
|                                                           |
|  processTreeCache (FrequencyCache, 500 entries, 0.4 bias) |
|  +-- Process tree instruction managers                    |
|      Used for ProcessTree-level caching                   |
|                                                           |
|  instructionsCache (FrequencyCache, 500 entries, 0.4 bias)|
|  +-- ScopeInstructionsManager instances                   |
|  +-- Keyed by computation signature strings               |
|  +-- Auto-destroys evicted managers                       |
|      THIS IS THE PRIMARY INSTRUCTION CACHE                |
|                                                           |
+-----------------------------------------------------------+
```

The `instructionsCache` is the primary cache for compiled kernels. It maps signature strings to `ScopeInstructionsManager` instances, each of which lazily compiles and caches an `InstructionSet`.

### Class Structure

```
io.almostrealism.uml.Signature (interface)
    |
    +-- ProducerComputationBase.signature()    -- MD5 of name + input signatures
    +-- ComputationScopeCompiler.signature()   -- Appends &distinct=N;

io.almostrealism.util.FrequencyCache<K, V>
    |
    +-- Used by DefaultComputer.instructionsCache

ExecutionKey (marker interface)
    |
    +-- ScopeSignatureExecutionKey    -- Key by signature string (primary)
    +-- DefaultExecutionKey           -- Key by function name + arg count (fallback)
    +-- ProcessTreePositionKey        -- Key by tree position (deprecated)

InstructionSetManager<K extends ExecutionKey> (interface)
    |
    +-- AbstractInstructionSetManager<K>     -- Holds ComputeContext
        |
        +-- ScopeInstructionsManager<K>      -- Standard: lazy compile + cache
            |
            +-- ComputableInstructionSetManager<K>  -- Adds output tracking
            +-- ComputationInstructionsManager       -- Multi-function fallback

ProcessArgumentMap
    |
    +-- Maps Process tree positions to scope arguments
    +-- Enables argument substitution for kernel reuse
```

### Data Flow

The complete flow from computation to cached execution:

```
Producer.get()
    |
    v
AcceleratedComputationEvaluable (created)
    |
    v
AcceleratedComputationOperation.getInstructionSetManager()
    |
    +-- Compute signature via ComputationScopeCompiler.signature()
    |
    +-- ScopeSettings.enableInstructionSetReuse && signature != null ?
    |       |
    |       +-- YES: DefaultComputer.getScopeInstructionsManager(signature, ...)
    |       |       |
    |       |       +-- instructionsCache.computeIfAbsent(signature, ...)
    |       |       |       |
    |       |       |       +-- CACHE HIT: Return existing ScopeInstructionsManager
    |       |       |       |
    |       |       |       +-- CACHE MISS: Create new ScopeInstructionsManager
    |       |       |                       (with lazy scope supplier)
    |       |       |
    |       |       +-- Return manager
    |       |
    |       +-- NO: Create ComputationInstructionsManager (no caching)
    |
    v
AcceleratedComputationOperation.load()
    |
    +-- Call manager.getOperator(key)
    |       |
    |       +-- First call: Compiles scope -> InstructionSet (expensive)
    |       +-- Subsequent: Returns cached operator (cheap)
    |
    +-- If arguments are null (reusing shared kernel):
    |       |
    |       +-- Setup arguments from manager's scope
    |       +-- Create ProcessArgumentMap with substitutions
    |       +-- Set evaluator to use substituted arguments
    |
    v
Execution operator (ready for kernel dispatch)
```

---

## Signatures

### What a Signature Captures

A computation signature encodes the **structural identity** of an operation -- everything that affects the generated kernel code:

| Included in Signature | Not Included |
|----------------------|--------------|
| Operation type name (e.g., "Add", "Multiply") | Actual data values |
| Input shapes and dimensions | Memory addresses/pointers |
| Precision (FP32/FP64) | Runtime arguments |
| Compute requirements (GPU, CPU) | Object identity/hashCode |
| Input operation signatures (recursive) | Thread or timing info |
| Number of distinct children | |

Two operations with the same signature produce **identical kernel code**. They differ only in which memory buffers they operate on.

### Signature Generation

Signatures are generated in two stages:

**Stage 1: `ProducerComputationBase.signature()`**

```java
// Collects signatures from all inputs (skip first = destination)
List<String> signatures = getInputs().stream().skip(1)
        .map(Signature::of).collect(Collectors.toList());

// If any input lacks a signature, the whole signature is null
if (signatures.stream().anyMatch(Objects::isNull)) return null;

// Combine: name + requirements + input signatures
String requirements = getComputeRequirements()...;
return Signature.md5(getName() + "/" + requirements + "|" +
        String.join(":", signatures));
```

The result is an MD5 hash string like `a3f2b7c4e8d1...`.

**Stage 2: `ComputationScopeCompiler.signature()`**

```java
String signature = getMetadata().getSignature();
if (signature == null) return null;

if (computation instanceof Process<?,?>) {
    int distinct = ((Process<?,?>) computation).children()
            .collect(Collectors.toSet()).size();
    return signature + "&distinct=" + distinct + ";";
}

return signature;
```

Appends `&distinct=N;` to distinguish operations with different numbers of unique children (e.g., `add(A, A)` vs `add(A, B)`).

### Signature Format

The final signature format is:

```
<md5hash>&distinct=<N>;
```

Examples:
- `a3f2b7c4e8d1...&distinct=2;` -- A binary operation with two distinct inputs
- `b5e9a1c3f7d0...&distinct=1;` -- A unary operation (or binary with same input)

If any input in the computation tree does not implement `Signature` or returns null, the entire chain returns null and instruction set reuse is disabled for that operation.

---

## Execution Keys

Execution keys identify specific operations within an `InstructionSetManager`. The key type determines the caching granularity.

### ScopeSignatureExecutionKey

The primary key type used when signature-based caching is enabled.

| Field | Type | Description |
|-------|------|-------------|
| `signature` | `String` | The scope signature string |

Created by `AcceleratedComputationOperation.getExecutionKey()` when `ScopeSettings.enableInstructionSetReuse` is true and a signature is available.

Two operations with the same signature share the same `ScopeInstructionsManager`, but each gets its own `ScopeSignatureExecutionKey` to track its output argument index and offset independently.

### DefaultExecutionKey

Fallback key type used when signature-based caching is disabled or unavailable.

| Field | Type | Description |
|-------|------|-------------|
| `functionName` | `String` | The compiled function name |
| `argsCount` | `int` | Number of arguments |

Used by `ComputationInstructionsManager` for multi-function scopes where operations are identified by name rather than signature.

### ProcessTreePositionKey (Deprecated)

Identifies operations by their position in a Process tree hierarchy.

| Field | Type | Description |
|-------|------|-------------|
| `position` | `int[]` | Path from root (e.g., `[0, 1]` = second child of first child) |

Primarily used by `ProcessArgumentMap` for mapping Process tree positions to scope arguments during argument substitution. The key type itself is deprecated for use as an `ExecutionKey`.

---

## Core Components

### InstructionSetManager

**Package:** `org.almostrealism.hardware.instructions`

The core interface for managing compiled hardware operations.

```java
public interface InstructionSetManager<K extends ExecutionKey> extends Destroyable {
    Execution getOperator(K key);
}
```

Responsibilities:
- Compile scopes to native code on first access
- Cache compiled `InstructionSet` for reuse
- Release native resources when destroyed

### ScopeInstructionsManager

**Package:** `org.almostrealism.hardware.instructions`

The standard implementation that handles lazy compilation and caching.

| Field | Type | Purpose |
|-------|------|---------|
| `scope` | `Supplier<Scope<?>>` | Lazy scope supplier (invoked once) |
| `operators` | `InstructionSet` | Cached compiled instructions |
| `argumentMap` | `ProcessArgumentMap` | Maps Process positions to arguments |
| `scopeName` | `String` | Name of the compiled scope |
| `inputs` | `List<Supplier<Evaluable<?>>>` | Scope input suppliers |
| `arguments` | `List<Argument<?>>` | Scope arguments |
| `outputArgIndices` | `Map<K, Integer>` | Output arg index per key |
| `outputOffsets` | `Map<K, Integer>` | Output offset per key |
| `accessListener` | `Consumer<ScopeInstructionsManager<K>>` | Notified on access |
| `destroyListeners` | `List<Runnable>` | Notified on destroy |

Key behaviors:
- `getOperator(K key)`: Synchronized; compiles scope via `ComputeContext.deliver()` on first call, returns cached operator on subsequent calls
- `getScope()`: Invokes the scope supplier and caches metadata (name, inputs, arguments); populates `ProcessArgumentMap` if a Process is associated
- `destroy()`: Destroys the `InstructionSet` (releases native code) and notifies all destroy listeners

### ComputableInstructionSetManager

**Package:** `org.almostrealism.hardware.instructions`

Extended interface that adds output argument tracking:

```java
public interface ComputableInstructionSetManager<K extends ExecutionKey>
        extends InstructionSetManager<K> {
    int getOutputArgumentIndex(K key);
    int getOutputOffset(K key);
}
```

Implemented by `ScopeInstructionsManager`. Used by `AcceleratedComputationEvaluable` to extract the result from the correct argument after kernel execution.

### ComputationInstructionsManager

**Package:** `org.almostrealism.hardware.instructions`

Specialized `ScopeInstructionsManager` for multi-function scopes using `DefaultExecutionKey`.

```java
public class ComputationInstructionsManager
        extends ScopeInstructionsManager<DefaultExecutionKey> {

    @Override
    public synchronized Execution getOperator(DefaultExecutionKey key) {
        return getInstructionSet().get(key.getFunctionName(), key.getArgsCount());
    }
}
```

Used when `ScopeSettings.enableInstructionSetReuse` is false or when no signature is available. Each operation gets its own `ComputationInstructionsManager` (no cross-operation sharing).

### FrequencyCache

**Package:** `io.almostrealism.util`

A hybrid LFU/LRU cache used by `DefaultComputer` to hold compiled instruction managers.

| Parameter | Value | Description |
|-----------|-------|-------------|
| `capacity` | 500 | Maximum number of distinct cached values |
| `frequencyBias` | 0.4 | Weight for frequency in eviction scoring |

**Eviction score formula:**

```
score = 0.4 * (frequency / totalAccesses)
      + 0.6 * (1 - age)
```

where `age = (clock - lastAccessTime) / clock`. Entries with the lowest score are evicted first. With `frequencyBias=0.4`, recency (60% weight) matters more than frequency (40% weight), favoring recently-used kernels over historically popular but currently dormant ones.

**Value deduplication:** Multiple keys can map to the same `CacheEntry` via a reverse map. This means:
- Frequency tracking is unified across all keys pointing to the same value
- Eviction of a value removes all keys sharing it
- No metadata duplication

**Eviction listener:** `DefaultComputer` registers `(key, mgr) -> mgr.destroy()` to release native resources when a manager is evicted.

**Access listener pattern:** `DefaultComputer` creates `ScopeInstructionsManager` instances with an access listener that calls `instructionsCache.computeIfAbsent(signature, () -> mgr)`. This ensures that even if a manager was previously evicted, using it (via a lingering reference from an operation that still holds the manager) restores it to the cache.

---

## Argument Substitution

### ProcessArgumentMap

When a cached `ScopeInstructionsManager` is reused by a different operation instance, the kernel code is identical but the data arguments differ. `ProcessArgumentMap` solves this by maintaining bidirectional mappings between scope arguments and Process tree positions, enabling substitution.

**Core data structures:**

| Field | Type | Purpose |
|-------|------|---------|
| `arguments` | `List<ArrayVariable<?>>` | All scope arguments |
| `argumentsByPosition` | `Map<ProcessTreePositionKey, ArrayVariable<?>>` | Position -> argument |
| `positionsForArguments` | `Map<ArrayVariable<?>, ProcessTreePositionKey>` | Argument -> position |
| `substitutions` | `Map<ProcessTreePositionKey, Producer>` | Position -> replacement |

### Substitution Flow

When `AcceleratedComputationOperation.load()` detects that it is reusing a shared instruction manager (arguments are null after loading):

```java
// 1. Get the shared manager
ScopeInstructionsManager manager =
    (ScopeInstructionsManager) getInstructionSetManager();

// 2. Set up this operation's arguments from the shared scope
setupArguments(manager.getScopeInputs(), manager.getScopeArguments());

// 3. Create a copy of the argument map (fresh substitution map)
ProcessArgumentMap map = new ProcessArgumentMap(manager.getArgumentMap());

// 4. Walk this operation's Process tree and record each
//    Process node's Producer at the corresponding tree position
map.putSubstitutions((Process<?,?>) getComputation());

// 5. Use the substitution map as the evaluator
setEvaluator(map);
```

At execution time, when the kernel needs argument N at tree position P:
1. The `ProcessArgumentMap` looks up position P in the substitutions map
2. Returns the new operation's `Producer` instead of the original operation's
3. The kernel runs with the new data but the same compiled code

---

## Lifecycle

### Compilation and Caching

```
[First operation with signature "abc123"]
    |
    +-- getInstructionSetManager()
    |       +-- signature = "abc123"
    |       +-- DefaultComputer.getScopeInstructionsManager("abc123", ...)
    |       |       +-- instructionsCache.computeIfAbsent("abc123", ...)
    |       |       +-- MISS: Create new ScopeInstructionsManager
    |       +-- Return manager
    |
    +-- load()
    |       +-- manager.getOperator(key)
    |       |       +-- operators == null: Compile scope -> InstructionSet
    |       |       +-- Return operator
    |       +-- Arguments available (first compile): Normal path
    |
    +-- evaluate() -> Runs compiled kernel
```

### Reuse Path

```
[Second operation with same signature "abc123"]
    |
    +-- getInstructionSetManager()
    |       +-- signature = "abc123"
    |       +-- DefaultComputer.getScopeInstructionsManager("abc123", ...)
    |       |       +-- instructionsCache.computeIfAbsent("abc123", ...)
    |       |       +-- HIT: Return existing ScopeInstructionsManager
    |       +-- Return manager (same instance)
    |
    +-- load()
    |       +-- manager.getOperator(key)
    |       |       +-- operators != null: Return cached operator (NO recompilation)
    |       +-- Arguments are null (reusing shared kernel)
    |       +-- Setup argument substitutions via ProcessArgumentMap
    |
    +-- evaluate() -> Runs SAME kernel with DIFFERENT arguments
```

### Eviction and Destruction

When the `FrequencyCache` exceeds capacity 500:

1. Compute eviction scores for all entries
2. Evict the entry with the lowest score
3. Eviction listener calls `manager.destroy()`
4. `ScopeInstructionsManager.destroy()`:
   - Destroys the `InstructionSet` (releases native code / .so / .dylib)
   - Runs all destroy listeners
5. Operations holding a reference to the evicted manager:
   - Still function because the access listener restores the manager to the cache on next use
   - But the `InstructionSet` is destroyed, so `getOperator()` will recompile

---

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `AR_INSTRUCTION_SET_REUSE` | `true` | Enable/disable signature-based instruction caching |
| `AR_REDUNDANT_COMPILATION` | `true` | Allow multiple evaluables with same signature to compile independently |
| `AR_HARDWARE_VERBOSE_COMPILE` | `false` | Log compilation events |

**In code:**

```java
// Disable instruction set reuse (forces per-operation compilation)
ScopeSettings.enableInstructionSetReuse = false;

// Disable redundant compilation (second evaluable with same
// signature skips compilation if instructions already exist)
AcceleratedComputationEvaluable.enableRedundantCompilation = false;
```

---

## Relationship to Heap

`Heap` and instruction caching are **independent** systems that operate at different levels:

| Aspect | Heap | Instruction Caching |
|--------|------|-------------------|
| What it caches | Memory allocations (Bytes) | Compiled kernels (InstructionSet) |
| Scope | Thread-local, per-stage | Global, per-signature |
| Lifetime | Scope exit (push/pop) | Until eviction from FrequencyCache |
| Resource type | Memory buffers | Native compiled code |

**Important:** `Heap.stage()` destroys `OperationAdapter` wrappers (via `HeapDependencies`) but does **not** invalidate the instruction cache. A destroyed `OperationAdapter` will trigger recompilation only if the `InstructionSet` within its `ScopeInstructionsManager` has also been independently destroyed (e.g., by cache eviction).

---

## Thread Safety

| Component | Thread Safety | Mechanism |
|-----------|--------------|-----------|
| `ScopeInstructionsManager.getOperator()` | Thread-safe | `synchronized` method |
| `ScopeInstructionsManager.getInstructionSet()` | Thread-safe | `synchronized` method |
| `FrequencyCache.prepareCapacity()` | Thread-safe | `synchronized` method |
| `FrequencyCache.get()/put()` | NOT thread-safe | Requires external sync |
| `DefaultComputer.getScopeInstructionsManager()` | Depends on caller | No internal sync |
| `ProcessArgumentMap` | NOT thread-safe | Created per-operation |

The instruction cache in `DefaultComputer` is accessed from compilation paths that may run on different threads. Compilation is serialized by `ScopeInstructionsManager.getOperator()` being synchronized, but cache operations (`FrequencyCache.get`/`put`) are not internally synchronized.

---

## Common Pitfalls

### Null Signatures Disable Caching

If any input in a computation tree does not implement `Signature` or returns null from `signature()`, the entire chain returns null and instruction caching is disabled for that operation. This is by design -- without a complete signature, structural identity cannot be guaranteed.

**Symptom:** Every evaluation triggers a fresh compilation.
**Diagnosis:** Check whether `signature()` returns null for the computation.

### Eviction Triggers Recompilation

When a `ScopeInstructionsManager` is evicted from the `FrequencyCache`, its `InstructionSet` is destroyed. If a lingering operation reference still points to the manager, the next `getOperator()` call will recompile from scratch.

**Symptom:** Periodic compilation spikes in long-running applications.
**Diagnosis:** Monitor `HardwareOperator.recordCompilation()` calls over time.

### Argument Substitution Requires Process

The argument substitution mechanism (`ProcessArgumentMap.putSubstitutions()`) requires the computation to implement `Process`. If the computation is not a `Process`, the reuse path falls back to full recompilation with a warning:

```
Unable to reuse instructions for <name> because <computation> is not a Process
```

### enableRedundantCompilation Interaction

When `enableRedundantCompilation` is true (default), `AcceleratedComputationEvaluable.confirmLoad()` will compile even if an instruction manager already exists. This is safe but wastes compilation effort. Setting it to false prevents redundant compilations but may cause issues if the instruction manager's `InstructionSet` has been destroyed.
