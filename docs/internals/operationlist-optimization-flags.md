# OperationList Optimization Flags

This document provides a detailed reference for the three static optimization
flags on `OperationList` that control how operation lists are compiled and
executed:

| Flag | Default | Purpose |
|------|---------|---------|
| `enableAutomaticOptimization` | `false` | Automatically call `optimize()` before `get()` for non-uniform lists |
| `enableSegmenting` | `false` | Group consecutive same-count operations into sub-lists during optimization |
| `enableNonUniformCompilation` | `false` | Allow non-uniform lists to compile as a single kernel |

All three are `public static boolean` fields, meaning they are **global process-wide
settings**. Changing one affects every `OperationList` in the JVM.

---

## Background: Uniform vs Non-Uniform Operation Lists

An `OperationList` is **uniform** when every child operation has the same
*parallelism count* (kernel work-item count). For example, a list of three
vector operations all operating on 4096 elements is uniform (count = 4096).

A list is **non-uniform** when children have different counts. A common
example is the AudioScene pipeline, which interleaves scalar assignments
(count = 1, e.g. zeroing a summation cell) with vector operations (count =
4096, e.g. FIR filter convolution).

Uniformity matters because compiling a list into a single hardware kernel
requires all operations to agree on the kernel dispatch size. Non-uniform
lists cannot be compiled into a single kernel without special handling.

---

## `enableAutomaticOptimization`

### What It Does

When `true`, `OperationList.get()` automatically calls `optimize()` on any
non-uniform list before compiling/running it.

**Code path** (`OperationList.get()`, line 670):
```java
if (enableAutomaticOptimization && !isUniform()) {
    return optimize().get();
} else if (isComputation() && (enableNonUniformCompilation || isUniform())) {
    // Compile to single kernel
} else {
    // Fall through to sequential Runner execution
}
```

When **disabled** (the default), non-uniform lists skip `optimize()` entirely
during `get()`. The caller is responsible for calling `optimize()` explicitly
if needed.

### What `optimize()` Does

`optimize()` is the entry point to the Process optimization pipeline. It:

1. **Creates a `ParallelProcessContext`** capturing the current process's
   parallelism.
2. **Recursively optimizes each child** via `ParallelProcess.optimize(ctx, child)`,
   which in turn calls `child.optimize(ctx)` and then evaluates whether the
   child should be *isolated* (wrapped in an `IsolatedProcess` to break
   expression embedding).
3. **Delegates to a `ProcessOptimizationStrategy`** (typically
   `ParallelismTargetOptimization`) which restructures the process tree
   based on parallelism thresholds and scoring.

The critical side effect is **process isolation**. Some computations (e.g.
`LoopedWeightedSumComputation`) return `true` from `isIsolationTarget()`.
Without an `optimize()` call, these computations are embedded directly into
the parent scope's expression tree. This can cause:

- Massive expression trees that take minutes to compile
- Compilation timeouts
- Stack overflows during scope generation

### Why It's `false` by Default

Automatic optimization is disabled by default because:

1. **Not all callers need it.** Framework code that already calls
   `optimize()` explicitly (e.g. `CompiledModel`) does not benefit, and
   running `optimize()` twice wastes time.
2. **Performance cost.** The optimization pipeline traverses the entire
   process tree, creates contexts, evaluates isolation predicates, and
   may restructure the tree. For large operation lists with hundreds of
   children, this adds non-trivial compilation overhead.
3. **Behavioral change.** Enabling it changes the execution path for
   every non-uniform `OperationList.get()` call in the process. Code
   that previously ran sequentially (via `Runner`) may now compile to
   kernels, or code that compiled as a single kernel may be restructured
   into multiple kernels.

### Risks of Enabling

| Risk | Description | Mitigation |
|------|-------------|------------|
| **Double optimization** | Code that already calls `optimize()` explicitly will optimize twice | Audit call sites; `optimize()` is idempotent for most strategies but still wastes time |
| **Changed execution semantics** | Operations that were compiled as one kernel may be split into multiple kernels, or vice versa | Run full test suite after enabling |
| **Compilation time increase** | The optimization pass adds overhead to every `get()` call | Profile compilation time in critical paths |
| **Isolation side effects** | Previously-non-isolated computations may become isolated, changing memory allocation patterns | Watch for `HardwareException: Memory max reached` or unexpected kernel count changes |
| **Global scope** | Affects all `OperationList` instances, including framework internals, not just user code | Cannot be scoped to specific pipelines |

### Risks of Disabling (When Currently Enabled)

| Risk | Description |
|------|-------------|
| **Missing isolation** | Computations that require isolation (e.g. `LoopedWeightedSumComputation`) will be embedded into parent expressions, potentially causing compilation timeouts |
| **No segmentation** | Non-uniform lists will no longer be automatically segmented (see `enableSegmenting` below), resulting in more JNI transitions |
| **Existing tests may fail** | `OperationListSegmentationTest.automaticOptimizationEnabled()` asserts this flag is `true` |

---

## `enableSegmenting`

### What It Does

When `true`, the `optimize()` method on `OperationList` groups consecutive
operations with the same parallelism count into sub-lists before delegating
to the standard optimization pipeline.

**Code path** (`OperationList.optimize(ProcessContext)`, lines 843-871):
```java
if (!enableSegmenting || size() <= 1 || isUniform())
    return ComputableParallelProcess.super.optimize(context);

boolean match = IntStream.range(1, size())
    .anyMatch(i -> Countable.countLong(get(i-1)) == Countable.countLong(get(i)));
if (!match) return ComputableParallelProcess.super.optimize(context);

// Group consecutive same-count operations into sub-lists
OperationList op = new OperationList();
OperationList current = new OperationList();
long currentCount = -1;

for (int i = 0; i < size(); i++) {
    long count = Countable.countLong(get(i));
    if (currentCount == -1 || currentCount == count) {
        current.add(get(i));
    } else {
        op.add(current.size() == 1 ? current.get(0) : current);
        current = new OperationList();
        current.add(get(i));
    }
    currentCount = count;
}
// ... final segment added, then op.optimize(context) called recursively
```

### Segmentation Example

Given an operation list with this pattern:

```
[scalar(1), scalar(1), vector(4096), vector(4096), scalar(1)]
```

Segmentation produces:

```
Segment 1: [scalar(1), scalar(1)]         → compiles as 1 kernel
Segment 2: [vector(4096), vector(4096)]   → compiles as 1 kernel
Segment 3: [scalar(1)]                    → compiles as 1 kernel
```

This reduces the total number of JNI transitions from 5 individual kernel
dispatches to 3. In the AudioScene pipeline, this reduces transitions from
143 individual calls to significantly fewer grouped calls.

### Prerequisites

Segmentation requires specific conditions to activate:

1. **`enableSegmenting` must be `true`** — the flag itself.
2. **The list must have more than 1 element** — trivial lists are unchanged.
3. **The list must be non-uniform** — uniform lists are already optimal.
4. **At least two adjacent operations must have the same count** — if no
   adjacent pair matches, segmentation cannot improve anything.

If any of these conditions is not met, `optimize()` falls through to the
standard `ComputableParallelProcess.super.optimize(context)` path.

### Interaction with `enableAutomaticOptimization`

Segmentation only runs during `optimize()`. If `enableAutomaticOptimization`
is `false`, `optimize()` is never called automatically from `get()`. In
that case, the caller must call `optimize()` explicitly for segmentation to
take effect.

When `enableAutomaticOptimization` is `true`, any non-uniform list
automatically gets `optimize()` called, which in turn triggers segmentation
(if `enableSegmenting` is also `true`).

**The two flags are designed to work together:**

| `enableAutomaticOptimization` | `enableSegmenting` | Behavior |
|------|------|------|
| `false` | `false` | No automatic optimization, no segmentation. Caller must call `optimize()` manually and segmentation never runs. |
| `true` | `false` | Automatic optimization runs for non-uniform lists, but segmentation is skipped. Standard `ParallelProcess.optimize()` handles the tree. |
| `false` | `true` | No automatic optimization. If caller calls `optimize()` manually, segmentation runs. |
| `true` | `true` | Full automation: non-uniform lists are automatically segmented and optimized on every `get()` call. |

### Why It's `false` by Default

1. **Segmentation restructures the process tree.** The output is a different
   `OperationList` with different nesting. Code that inspects the structure
   of an `OperationList` after `optimize()` may see unexpected nesting.
2. **Sub-list compilation.** Each segment becomes its own `OperationList`
   which may or may not be compilable. If a segment's operations are not
   all `Computation` instances, the segment falls back to `Runner` execution.
3. **Interaction with recursion.** The segmented `OperationList` calls
   `op.optimize(context)` recursively, which re-enters the segmentation
   check. This is safe because each segment is now uniform (all same count),
   so the recursion terminates, but it adds optimization overhead.

### Risks of Enabling

| Risk | Description | Mitigation |
|------|-------------|------------|
| **Tree restructuring** | The process tree changes shape; profiling metadata and node keys may shift | Re-baseline profiling data |
| **Additional compilation** | Each segment compiles independently, potentially increasing total compilation time for cold starts | Cache compiled kernels |
| **Recursive overhead** | `op.optimize(context)` is called recursively on the segmented list | Safe due to uniformity guard, but adds stack frames |
| **Correctness risk** | Reordering is not performed (only grouping of consecutive same-count ops), so execution order is preserved | Verify with correctness tests like `OperationListSegmentationTest` |
| **Global scope** | Affects all `OperationList.optimize()` calls, not just the intended pipeline | Cannot be scoped |

### Risks of Disabling (When Currently Enabled)

| Risk | Description |
|------|-------------|
| **More JNI transitions** | Non-uniform lists will dispatch each operation as a separate kernel call, increasing overhead |
| **Performance regression** | The AudioScene pipeline relies on segmentation to reduce 143 JNI calls to fewer grouped calls |
| **Test failure** | `OperationListSegmentationTest.segmentingEnabled()` asserts this flag is `true` |

---

## `enableNonUniformCompilation`

### What It Does

When `true`, allows non-uniform `OperationList` instances to compile into a
single hardware kernel even when their children have different parallelism
counts.

**Code path** (`OperationList.get()`, line 672):
```java
} else if (isComputation() && (enableNonUniformCompilation || isUniform())) {
    AcceleratedOperation op = (AcceleratedOperation) compileRunnable(this);
    op.setFunctionName(functionName);
    op.load();
    return op;
}
```

Without this flag, only uniform lists (where `isUniform()` returns `true`)
can compile to a single kernel. With it, any list where `isComputation()`
is `true` can compile regardless of uniformity.

### Risks of Enabling

| Risk | Description |
|------|-------------|
| **Kernel dispatch mismatch** | A kernel compiled from operations with counts 1 and 4096 must dispatch with a single work-item count. The generated code may not correctly handle mixed-count operations in a single kernel. |
| **Silent incorrect results** | If the kernel dispatch count is wrong, operations may silently produce wrong values (e.g., only computing the first element of a 4096-element vector) |
| **Not widely tested** | This flag is rarely used; most code paths rely on uniformity or segmentation |

### Interaction with Other Flags

`enableNonUniformCompilation` is checked **after** `enableAutomaticOptimization`.
If automatic optimization is enabled, non-uniform lists are routed to
`optimize().get()` first, which typically segments them into uniform
sub-lists. The `enableNonUniformCompilation` check is only reached when
automatic optimization is disabled or the list is already uniform.

**Precedence in `get()`:**
1. If `enableAutomaticOptimization && !isUniform()` → optimize and recurse
2. If `isComputation() && (enableNonUniformCompilation || isUniform())` → compile
3. Otherwise → sequential `Runner` execution

---

## Decision Flow Diagram

```
OperationList.get()
│
├── Is list functionally empty?
│   └── YES → return no-op
│
├── enableAutomaticOptimization && !isUniform()?
│   └── YES → optimize().get()
│               │
│               ├── enableSegmenting && size > 1 && !isUniform()?
│               │   └── YES → segment by count → recurse optimize()
│               │
│               └── NO → ParallelProcess.super.optimize()
│                         (handles isolation, strategy delegation)
│
├── isComputation() && (enableNonUniformCompilation || isUniform())?
│   └── YES → compile to single AcceleratedOperation kernel
│
└── Otherwise
    └── Sequential Runner execution (each op dispatched individually)
```

---

## Historical Context

These flags were introduced as `false` by default for safety. They were
changed to `true` on the `feature/audio-loop-performance` branch as part of
Goal 3 (kernel fusion) of the AudioScene loop optimization. The change
reduces JNI call overhead by fusing consecutive same-count operations in the
AudioScene's 143-operation pipeline into fewer grouped kernel calls.

See [../journals/AUDIO_SCENE_LOOP.md](../journals/AUDIO_SCENE_LOOP.md) for
the decision journal entry.

---

## Testing

The `OperationListSegmentationTest` in the `utils` module verifies:

1. **`automaticOptimizationEnabled`** — asserts `enableAutomaticOptimization == true`
2. **`segmentingEnabled`** — asserts `enableSegmenting == true`
3. **`nonUniformListProducesCorrectResults`** — correctness test with mixed
   scalar/vector operations
4. **`segmentationGroupsConsecutiveSameCountOps`** — structural test verifying
   segmentation grouping behavior

When toggling these flags, these tests must be updated or the assertions
will fail.

The `LoopedSumDiagnosticTest.testWithOptimization()` tests
`enableAutomaticOptimization` by temporarily enabling it, verifying that
isolation is triggered for `LoopedWeightedSumComputation`.
