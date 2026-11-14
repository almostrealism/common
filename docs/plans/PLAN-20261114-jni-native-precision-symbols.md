---
status: proposed
created: 2026-11-14
closed:
tracker: [3f3f6d5f-c26d-46fe-bfbc-b46d047faeb8]
---

# Make precision-dependent JNI native operations safe across precisions in one JVM

Plans tracker task **3f3f6d5f-c26d-46fe-bfbc-b46d047faeb8** — *"JNI copy/alloc symbols
are per class, not per precision: FP32 native memory can call FP64 code and overrun
buffers"* — in release **Common 0.77**.

## Motivation

The runtime-compiled JNI operations in `base/hardware/.../org/almostrealism/nio/`
derive their exported C symbol from the Java class, not from the numeric precision the
code was generated for. `BaseNative.initNativeFunctionName()` builds the symbol as
`Java_<fully-qualified-class>_apply`, and `BaseNative.loadNative(...)` caches *per class*
in the static `libs` list: once a class's native library is loaded, a later instance of
the same class **skips compilation entirely** and binds to the already-loaded symbol.

`NativeRead` and `NativeWrite` (both via `NativeArrayCopy`) emit **different C** per
precision: FP64 copies `jdouble`s in one `Set/GetDoubleArrayRegion`; FP32 converts through
a stack buffer of `float`s. But the symbol and the `libs` key are identical for both. So in
a single JVM the first precision to construct a `NativeRead`/`NativeWrite` wins, and a
later calloc `NativeMemoryProvider` at the other precision binds the wrong implementation.
The concrete failure the task describes: an FP32 provider whose block was sized
`len * 4` bytes invokes the bound FP64 `NativeWrite`, which copies `len * 8` bytes into it —
a heap overrun that corrupts memory and crashes with SIGSEGV. The bounds checks in
`NativeArrayCopy.apply(...)` do not catch it, because they are computed from
`getNativeCompiler().getPrecision().bytes()` and are internally consistent with the *intended*
precision; only the bound native code is wrong.

The hazard is latent today but real. `Hardware.getNativeBufferMemoryProvider()` already
documents it in prose and deliberately steers around it: in calloc mode it **reuses the
native data context's own provider** rather than building a second one at another precision,
"because the runtime-compiled JNI operations bind once per class in the JVM, so two calloc
providers at different precisions would read each other's memory at the wrong element width."
The default direct-buffer mode avoids the ops altogether. The defect is therefore a sharp
edge the framework navigates by construction-site discipline, not something it is immune to.
A clear, enforced contract (or genuinely precision-safe natives) removes the footgun.

## Scope

In scope — the precision-dependent copy operations and the base-class mechanism they share:

- `base/hardware/.../c/BaseNative.java` — the symbol-naming and per-class `libs` cache that
  make the collision possible.
- `base/hardware/.../nio/NativeArrayCopy.java`, `NativeRead.java`, `NativeWrite.java` — the
  only operations whose generated code differs by precision *and* whose difference is harmful.
- A new test in `engine/utils/src/test/java/org/almostrealism/hardware/test/` that creates
  FP64 and FP32 **calloc** (`direct=false`) `NativeMemoryProvider`s in one JVM and asserts the
  documented post-fix behaviour (see Success criteria), sitting beside the existing
  `NativeMemoryCopyTest`.

Explicitly out of scope (documented, not changed, unless the reviewer chooses the alternative
approach below):

- `Malloc` — its generated code is `calloc((size_t) len, 1)`, which is **byte-sized and
  precision-independent**. Two precisions share the symbol but run identical code; no harm.
- `Free` — its generated code casts to `double*`/`float*` before `free()`, but `free()`
  ignores the pointer type, so binding the other precision's `Free` is **benign**. (The task
  asks to "check `Free`"; this is the finding — it is structurally identical but harmless.)
- The direct-buffer operations (`NativeBufferPointer`, `NativeBufferView`, `SharedMemoryMap`,
  `SharedMemorySync`, `SharedMemoryUnmap`) — byte/pointer operations with no precision-dependent
  numeric path; the shared FP32 bridge and an FP64 context already construct these at two
  precisions in direct mode without harm.
- `Hardware.getNativeBufferMemoryProvider()`'s provider-sharing logic — it remains correct and
  is not required to change. (It *could* be relaxed if the natives are made precision-safe, but
  that is a follow-up, not this task.)

No `pom.xml` changes, no new Maven modules, no CI-workflow changes.

## Approach

Two directions satisfy the task's "Done when". The task prefers precision-specific symbols
*"if mixed-precision native providers are a supported configuration."* The investigation found
they are **not** a supported configuration today: `getNativeBufferMemoryProvider()` prevents two
calloc providers at different precisions from ever coexisting in production, and the default
direct-buffer path never touches these ops. So the recommended primary is the fail-fast
direction, which encodes the contract the codebase already relies on and is minimal and general.

### Recommended (primary): fail fast on a second precision for a precision-dependent op

Make `BaseNative` refuse to bind a *precision-dependent* native operation at a precision
different from the one already bound for that class in this JVM, with a clear
`HardwareException` naming the class and both precisions.

- Add a `protected boolean isPrecisionDependent()` to `BaseNative`, defaulting to `false`.
  Override it to return `true` in `NativeArrayCopy` (covering `NativeRead` and `NativeWrite`).
  Leave `Malloc` and `Free` at the default (`false`) — they are precision-independent or benign
  as documented above — so that a mismatched-precision provider can still `allocate` and
  `deallocate`; only the genuinely unsafe copy binds are rejected.
- In `BaseNative.initNative()` (or `loadNative`), when `isPrecisionDependent()`, consult a new
  static `Map<Class, Precision>` guard keyed by `getClass()`: record the precision
  (`getNativeCompiler().getPrecision()`) on first bind; on a later bind of the same class at a
  different precision, throw the documented `HardwareException` instead of silently returning the
  already-loaded (wrong-precision) symbol. This sits exactly where the existing per-class `libs`
  cache already short-circuits, so it adds no new lifecycle surface.

The error surfaces the first time the mismatched-precision provider performs a copy (the point
where it lazily constructs its `NativeRead`/`NativeWrite`), turning a silent SIGSEGV into a
diagnosable exception. Nothing in production trips it, because production never creates two
calloc providers at different precisions (verified above).

### Alternative: give each precision its own declaring class (precision-safe symbols)

If the reviewer wants mixed-precision calloc providers to be a genuinely *supported* and
round-trippable configuration, bind each precision to a distinct symbol instead of rejecting it.
Because JNI's automatic binding resolves `native apply` to `Java_<declaring-class>_apply`,
distinct symbols require distinct declaring classes (renaming the C function alone would break
automatic binding; only `RegisterNatives` avoids that, and the framework has no such plumbing).
Concretely: split `NativeRead` and `NativeWrite` into per-precision leaf classes (FP32/FP64),
each declaring its own `native apply`, with the shared signature/bounds/overloads staying in
`NativeArrayCopy`; `NativeMemoryProvider` constructs the leaf matching its own precision via its
`compiler()` (whose precision already equals the provider's). The per-class `libs` cache then
holds both leaves, both libraries load, and both symbols coexist — so both precisions round-trip
correctly in either order. This is more code in a foundational module (four leaf classes plus
construction routing) in support of a configuration nothing currently uses, which is why it is
the alternative rather than the primary.

Both directions leave `BaseNative`'s general shape intact and touch only `base/hardware`; neither
requires changes above the hardware layer.

## Success criteria

A reviewer can confirm the task is done when:

- **Primary (fail-fast):** a new test creates a calloc `NativeMemoryProvider` at the local
  hardware precision and round-trips data through it unchanged; then creates a calloc provider at
  the *other* precision in the same JVM and asserts that its first read/write throws the
  documented `HardwareException` (naming the class and the conflicting precisions), while the
  first provider continues to round-trip correctly. `allocate`/`deallocate` on the
  second provider still succeed (Malloc/Free are unguarded).
- **Alternative (precision-safe symbols):** the same test instead round-trips data through *both*
  the FP64 and FP32 calloc providers, created in both orders, with every value preserved and no
  corruption.
- The new test lives in `engine/utils` beside `NativeMemoryCopyTest`, extends `TestSuiteBase`,
  carries an explicit `@Test(timeout = ...)`, and runs on CI (the `engine/utils` test lanes run
  on both GPU machines, where local precision is FP32, and non-GPU machines, where it is FP64, so
  the "other precision" is exercised from both starting points across the matrix).
- No existing test changes behaviour: `NativeMemoryCopyTest` only ever uses the local precision
  and never trips the guard; `CrossProviderMemoryCopyTest.nativeToNativeMixedPrecision` uses
  `direct=true` and never constructs `NativeRead`/`NativeWrite`, so the guard does not apply.
- `base/hardware` and `engine/utils` build clean (`mvn … install -DskipTests`) and the build
  validator (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`) passes.

Note on verification: the *current* (pre-fix) crash is a SIGSEGV that would kill the test JVM, so
the test asserts post-fix behaviour rather than trying to catch the crash. The fix must land with
the test.

## Dependencies

- None. The blocking-task set is empty. The work is self-contained in `base/hardware` plus a test
  in `engine/utils`. It requires only the JNI/native CPU backend, which compiles C at runtime with
  the system toolchain and runs on every CI machine — no Metal, no GPU. No `*-workstream.yaml`
  settings file is needed (the work runs anywhere).

## Open questions

1. **Which direction?** Fail-fast (primary, recommended) versus precision-safe symbols
   (alternative). The deciding fact is whether mixed-precision *calloc* providers should become a
   supported, round-trippable configuration. Today they are deliberately avoided
   (`Hardware.getNativeBufferMemoryProvider()`), so fail-fast matches the real contract with the
   least code. The reviewer should pick before implementation; the test shape differs between the
   two (asserts the error vs. asserts both round-trips). One consequence to weigh: genuine "both
   orders round-trip in one JVM" is only achievable under the alternative, because per-class binding
   is permanent for the life of a JVM.
2. **Should `Free` and `Malloc` be split/guarded too, for uniformity?** The recommendation is no —
   they are precision-independent or benign, and guarding `Free` would prevent a mismatched-precision
   provider from freeing its own blocks, breaking test teardown. Documented here so the choice is
   explicit rather than an apparent omission.
3. **Follow-up (not this task):** if the alternative is chosen and the natives become precision-safe,
   `getNativeBufferMemoryProvider()`'s calloc provider-sharing could be relaxed from a correctness
   requirement to a locality optimization. That is a separate change and should be filed as its own
   task if desired, not folded in here.

## Estimated complexity

**Small–medium.** The primary fix is a small, localized change to `BaseNative` plus a one-line
override in `NativeArrayCopy`; the bulk of the effort is the new test and confirming no existing
test regresses across the CI precision matrix. The alternative is medium (four new leaf classes and
construction routing).
