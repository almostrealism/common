---
status: proposed
created: 2026-11-14
closed:
tracker: [3f3f6d5f-c26d-46fe-bfbc-b46d047faeb8]
---

# Make precision-dependent JNI native operations safe against cross-precision rebinding

**Tracker task:** `3f3f6d5f-c26d-46fe-bfbc-b46d047faeb8` — *JNI copy/alloc symbols
are per class, not per precision: FP32 native memory can call FP64 code and overrun
buffers*
**Release:** Common 0.77

## Motivation

`NativeRead`, `NativeWrite`, `Malloc` and `Free`
(`base/hardware/src/main/java/org/almostrealism/nio/`) generate their JNI C code at
runtime through `BaseNative` (`base/hardware/src/main/java/org/almostrealism/c/BaseNative.java`).
The generated code for the copy operations differs by precision — `NativeRead` emits a
single `SetDoubleArrayRegion` over a native `double*` for FP64, and a float-to-double
conversion through a fixed-size stack buffer for FP32; `NativeWrite` is the mirror image —
but the exported symbol is not precision-aware:

- `BaseNative.initNativeFunctionName()` derives the symbol from `getClass().getName()`
  (`Java_org_almostrealism_nio_NativeRead_apply`), which carries no precision.
- `BaseNative.loadNative(Class, String)` caches loaded classes in a **JVM-wide static**
  `List<Class> libs` and returns early when the class is already present, so whichever
  precision compiles and loads the class first wins for the life of the JVM.

Consequently, once a JVM has compiled and bound the FP64 copy operations, a calloc-mode
`NativeMemoryProvider` created later at FP32 lazily constructs `new NativeRead(...)` /
`new NativeWrite(...)` whose `initNative()` finds the class already in `libs` and **skips
recompilation**, binding the FP32 Java objects to the already-loaded FP64 native code. The
Java-side bounds checks in `NativeArrayCopy.apply(...)` are computed from
`getNativeCompiler().getPrecision().bytes()` — internally consistent with the *intended*
precision — so they do not catch the mismatch. The native code then copies 8 bytes per
element into a buffer sized for 4, overruns it, corrupts the heap, and crashes with
SIGSEGV.

This was found on `project/task-20261029-080142` (PR #781) while fixing the
`SimulationRunnerTest` CI crash, reported as out of scope, and worked around there by
running tests only at `Hardware.getLocalHardware().getPrecision()`. The hazard predates that
branch and is a latent footgun: any future code that stands up a second-precision calloc
provider in a running JVM inherits it.

## Scope

In scope — a single change to `base/hardware` plus a new test in `engine/utils`:

1. **Close the cross-precision rebinding hazard for the precision-dependent native copy
   operations** (`NativeRead` / `NativeWrite`, via their abstract base `NativeArrayCopy`).
   The recommended mechanism is a **fail-fast guard** in `BaseNative` (see Approach); the
   per-precision-subclass alternative is described there and left as the open design
   question below.
2. **Leave the precision-independent / benign operations unguarded**: `Malloc`
   (`getFunctionDefinition()` emits `calloc((size_t) len, 1)` — byte-sized, identical for
   every precision) and `Free` (casts the pointer to `double*`/`float*` before `free()`,
   which ignores pointer type — benign). Guarding these would needlessly refuse a
   mismatched provider's ability to allocate and release memory.
3. **A new test in `engine/utils`** that stands up calloc-mode `NativeMemoryProvider`s at
   both FP64 and FP32 in one JVM and proves the documented outcome (a correct round-trip, or
   the documented fail-fast error — see Success criteria), without corrupting the heap.

Out of scope — not widening into these:

- Making mixed-precision calloc providers a *supported* configuration. Investigation (see
  Approach) shows this is not a production configuration today; the subclass approach that
  would enable it is recorded as the alternative, to be chosen by the reviewer only if that
  support is actually wanted now.
- Any change to the direct-buffer (NIO) path, the shared cross-backend bridge, or
  `NativeBuffer.read/write` — none of these construct `NativeRead`/`NativeWrite`.
- Adding a general `RegisterNatives` facility to the framework (the framework binds JNI by
  the auto-generated `Java_<declaringClass>_apply` symbol and has no `RegisterNatives`
  path).

No new Maven module, no `pom.xml` edit, no CI-file change is required or permitted by this
plan.

## Approach

### What the code does today

- `BaseNative` (module `base/hardware`, package `org.almostrealism.c`) owns `functionName`,
  the static `libs` cache, `initNativeFunctionName()`, `initNative()` and the synchronized
  `loadNative(Class, String)`.
- `NativeArrayCopy extends BaseNative` (package `org.almostrealism.nio`) owns the shared JNI
  signature, the `CONVERSION_CHUNK` constant, the bounds-checked `apply(...)` overloads, and
  calls `initNative()` from its constructor. `NativeRead` and `NativeWrite` each **declare
  their own** `native void apply(...)` (JNI binds a native method to the class that declares
  it, so the two directions already get distinct symbols — `NativeRead` vs `NativeWrite` —
  and must each be guarded independently, keyed on the concrete class).
- `NativeMemoryProvider` (package `org.almostrealism.nio`) holds a `Precision` and a
  `NativeCompiler` (self-constructed at the provider's precision when none is supplied). In
  calloc mode only (`direct == false`), `getMem`/`setMem(double[])` lazily construct
  `new NativeRead(compiler())` / `new NativeWrite(compiler())`. In direct mode (the default,
  `AR_HARDWARE_NATIVE_DIRECT_BUFFERS=true`) they use `NativeBuffer.read/write` and never
  touch `NativeRead`/`NativeWrite`.

### Is mixed-precision calloc a supported configuration?

Verified against source — it is not:

- Production constructions of `NativeMemoryProvider` all use the single hardware precision:
  `Hardware.java` (`new NativeMemoryProvider(getPrecision(), ...)`) and
  `NativeDataContext.java` (`new NativeMemoryProvider(getPrecision(), ...)`). Within one
  JVM, `getPrecision()` is a single value.
- The cross-backend shared bridge (`NativeMemoryProvider.sharedBridge(...)`, fixed at
  `Precision.FP32`) is always constructed with `direct == true`, so it never builds
  `NativeRead`/`NativeWrite`.
- No test builds a **calloc** (`direct == false`) provider at an explicit precision other
  than the hardware precision. `NativeMemoryCopyTest.calloc()` is calloc mode but uses
  `Hardware.getLocalHardware().getPrecision()`. `CrossProviderMemoryCopyTest` (including
  `nativeToNativeMixedPrecision`), `ScopedContextMemoryRetentionTest` and
  `NativeBufferScalarReadTest` all build their FP64/FP32 providers with `direct == true`.

So the task's own conditional — "the [unique-per-precision] option is preferred *if*
mixed-precision native providers are a supported configuration" — resolves to: they are not,
therefore fail-fast is the appropriate primary fix.

### Recommended fix — fail-fast on cross-precision rebinding

Add, in `BaseNative`:

- A protected hook `boolean isPrecisionDependent()` returning `false` by default.
  `NativeArrayCopy` overrides it to `true`, so the guard applies to `NativeRead` and
  `NativeWrite` but not to `Malloc`/`Free` (which keep the default and stay unguarded).
- A JVM-wide `static Map<Class, Precision>` recording the precision under which each
  precision-dependent class was first loaded, written and read under the same lock that
  `loadNative` already holds.
- In the load path: when `isPrecisionDependent()`, read the operation's precision from
  `getNativeCompiler().getPrecision()`; if the class was previously loaded under a
  *different* precision, throw a `HardwareException`
  (`org.almostrealism.hardware.HardwareException`, same module) whose message names the
  concrete class and both precisions; otherwise record the precision on first load. A second
  construction at the *same* precision continues to be a cache hit, unchanged.

Rationale: the hazard is per-JVM-per-class, which is exactly what a static per-class record
captures. The guard converts an unrecoverable SIGSEGV into a clear, catchable error at the
point of misuse, costs no behaviour change to the single-precision production and test paths
(verified above), and is a small, localized change to one base class plus a one-line hook
override.

Placement note (project OOP rule — a method belongs on the type whose state it reads): the
guard reads `functionName`/`libs`-level per-class load state owned by `BaseNative` and the
compiler precision reachable through `BaseNative.getNativeCompiler()`, so it belongs in
`BaseNative`, with `isPrecisionDependent()` overridden on `NativeArrayCopy` (the type that
*is* precision-dependent). It is not a provider concern and must not live in
`NativeMemoryProvider`.

### Alternative — per-precision leaf subclasses (only if mixed-precision calloc must be supported now)

Because JNI binds `Java_<declaringClass>_apply`, genuinely distinct symbols require genuinely
distinct *declaring* classes: precision-specific leaves (e.g. an FP32 and an FP64 leaf for
each of read and write) that each redeclare `native void apply(...)`, with
`NativeMemoryProvider` selecting the leaf by its own precision. This lets both precisions
coexist in one JVM and round-trip correctly, but adds classes and a selection point and only
pays off if mixed-precision calloc becomes a supported configuration. Renaming only the
generated C function is not an option — it would break the auto-binding the framework relies
on, and there is no `RegisterNatives` path. Recorded as the fallback, not recommended for
this task.

## Success criteria

A reviewer can confirm the task is done when:

- The chosen mechanism lands in `base/hardware` (recommended: the `BaseNative`
  `isPrecisionDependent()` hook + per-class precision record + `HardwareException` on
  cross-precision rebind; `NativeArrayCopy` overrides the hook), with `Malloc`/`Free` left
  unguarded.
- A new test in `engine/utils`
  (`engine/utils/src/test/java/org/almostrealism/hardware/test/`, beside
  `NativeMemoryCopyTest`, extending `TestSuiteBase`, every `@Test` carrying a `timeout`)
  stands up calloc-mode (`direct == false`) `NativeMemoryProvider`s at FP64 and FP32 in one
  JVM and asserts the documented outcome:
  - **Under the recommended fail-fast fix:** a full array round-trip (write then read back,
    exact match, across a length spanning several FP32 conversion chunks) succeeds through a
    calloc provider at one precision; constructing/using a calloc provider at the *other*
    precision then fails fast with the documented `HardwareException` (naming the class and
    both precisions) rather than crashing the JVM. Because the guard is JVM-wide, the test
    drives the two precisions relative to whichever is bound first, so it is robust to
    Surefire fork reuse and test-execution order.
  - **Under the subclass alternative, if chosen:** calloc providers at both precisions, in
    both orders, each round-trip their own data without corruption.
- `mcp__ar-test-runner__start_test_run` for the new test class (and
  `NativeMemoryCopyTest` as a regression check) passes.
- No existing test changes behaviour — confirmed by the investigation above that no existing
  test builds a calloc provider at a non-hardware precision, so the guard is never tripped by
  the current suite. The new test is additive.
- `mcp__ar-build-validator__start_validation` (checkstyle, code_policy, test_timeouts,
  duplicate_code) is clean, and new public members carry Javadoc.
- The change runs on CI on an ordinary (non-Metal) node; no `-workstream.yaml` is needed (see
  Dependencies).

## Dependencies

- None blocking. The task's `blocked_by` is empty, and the PR-#781 work it builds on is
  merged.
- **No special machine.** The hazard and its fix are entirely in the native CPU / JNI path
  (`direct == false` calloc providers); no Metal or GPU is involved, and the existing calloc
  test (`NativeMemoryCopyTest`) already runs on the ordinary native lane. Therefore **no
  `docs/plans/task-20261114-041734-workstream.yaml` is written** — implementation runs
  anywhere.

## Open questions

1. **Design choice (for the approver): fail-fast vs. per-precision subclasses.** This plan
   recommends fail-fast on the evidence that mixed-precision calloc is not a supported
   configuration today. If the platform intends to *support* mixed-precision calloc providers
   in one JVM in the near term, the subclass approach is the right investment instead and the
   test's success criterion becomes the "both orders round-trip without corruption" branch.
   The two branches imply materially different test shapes, so the approver should settle this
   before implementation starts.
2. **Exception type and message contract.** `HardwareException` is proposed for the
   fail-fast error. The approver should confirm that is the intended type and that naming the
   concrete class and both precisions in the message is the contract the new test may assert
   on (a stable, user-facing message, not an incidental string).

## Estimated complexity

**Small.** The recommended fix is a localized change to one base class
(`BaseNative`) plus a one-line hook override on `NativeArrayCopy`, and one new additive test
class in `engine/utils`. No module, `pom.xml`, or CI changes. The only non-trivial element is
the design decision captured in Open Question 1; the subclass alternative, if chosen, would
raise the estimate to **medium**.
