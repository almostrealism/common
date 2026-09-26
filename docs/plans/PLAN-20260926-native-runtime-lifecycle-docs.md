# PLAN: Document the Native Runtime Lifecycle (Generated Kernels, Off-Heap Memory, Reentrancy)

**Category:** 1 — Documentation
**Date:** 2026-09-26
**Estimated Complexity:** Medium (one new internals doc, Javadoc on ~6 classes, one docs-portal link)

---

## Motivation

The platform documents the *front half* of computation well: how a Producer graph becomes a
Process tree (`docs/internals/computation-graph-to-process-tree.md`), how it is optimized
(`process-optimization-pipeline.md`), and how it is compiled and dispatched
(`backend-compilation-and-dispatch.md`). What is still undocumented is the *back half* — what
happens to the compiled artifact and the memory it touches once it exists:

- Where a generated JNI kernel library is written, whether it survives a JVM restart, and what
  a `GeneratedOperationN` class actually is (a reusable reservation slot vs. a stored kernel).
- What `AR_HARDWARE_MEMORY_SCALE` actually bounds, which providers enforce it, and what the
  failure looks like when it is exceeded. (Verified during execution: all three providers enforce
  the same byte ceiling — `MetalMemoryProvider.allocate` and `CLMemoryProvider.buffer` throw
  `HardwareException: "Memory Max Reached"`, `NativeMemoryProvider.allocate` throws
  `"Memory max reached"` — and each data context sets that ceiling to
  `getMaxReservation() * precision.bytes()`.)

- How Java GC releases off-heap memory (`HardwareMemoryProvider`'s `ReferenceQueue` loop), and
  what `KernelMemoryGuard` does and does not protect against while a kernel is in flight.
- Whether a generated kernel is safe to invoke concurrently. The `Thread Safety` section of
  `NativeInstructionSet` currently defers the question ("the underlying native code must be
  thread-safe if this is required") instead of answering it for the kernels the platform
  actually generates.
- The value semantics of generated code: kernel arguments are pointers owned outside the
  kernel; the code generator has no construct that assigns a null/zero *pointer*, only numeric
  values. A `0x0` dereference inside a generated kernel therefore points at the Java caller.

`docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` (May 2026) catalogued these gaps after a
native-crash investigation in a client application burned three wrong hypotheses, every one of
which was mechanically impossible but undocumented as such. Since then, QA documentation
passes have fixed individual stale claims, but none of the five gaps has been closed:
there is no internals page for any of them, `NativeCompiler` has no Javadoc on library
lifetime, and `NativeInstructionSet` still defers the reentrancy question.

This matters beyond crash triage. The long-term goal is a model that understands the platform
it runs on. A model (or a person) that cannot answer "what happens to this kernel after it is
compiled, and who owns the memory it reads?" does not understand the platform — it only
understands the API surface. This task closes that gap with statements verified against source.

## Scope

### 1. New internals doc: `docs/internals/native-runtime-lifecycle.md`

One page, organized as questions an investigator actually asks. Sections:

1. **Generated kernel artifacts** — the library directory (`AR_HARDWARE_LIBS`, default
   `SystemUtils.getExtensionsPath()`), file naming (`AR_HARDWARE_LIB_FORMAT`), the
   `GeneratedOperationN` / `BaseGeneratedOperation` reservation scheme, and — established from
   source, not assumed — whether and how a library from a previous JVM run can ever be loaded
   by the current one (overwrite on reservation? name reuse? explicit delete?). If the answer is
   "files persist on disk but are always regenerated before load", say exactly that.
   Also cover Metal/OpenCL program lifetime (in-memory only, or cached?) and the relationship
   to `base/hardware/docs/INSTRUCTION_CACHING.md` (in-JVM instruction reuse — link, don't repeat).
2. **Off-heap memory budget** — the `MEMORY_SCALE` formula from `Hardware`, which providers
   (`MetalMemoryProvider`, the JNI/NIO providers, `CLMemoryProvider`) check it and which do not,
   and the exact exception or OS behavior on exhaustion for each. Explicitly state that an
   allocator does not hand back a silent zero pointer (or document where it can, if source says
   otherwise).
3. **GC-driven release** — the `HardwareMemoryProvider` reference-queue lifecycle, the role of
   `MemoryDataAdapter.enableFinalizer`, `Destroyable`/try-with-resources, and the precise race a
   `KernelMemoryGuard` closes (native memory freed while a dispatched kernel still reads it) and
   the races it does not close. Link to the hardware README's "GC-Integrated Native Memory"
   section rather than duplicating it.
4. **Reentrancy of generated kernels** — per backend (JNI, OpenCL, Metal): does a compiled
   operator hold per-instance mutable state (scratch buffers, static globals, argument arrays
   reused across calls)? Can one instance be dispatched from two threads at once? Cite the
   generating code (e.g. the C scope writer for JNI, `CLOperator`, `MetalOperator`).
5. **Value semantics of generated code** — kernel arguments are externally owned memory; the
   expression/scope layer produces numeric assignments only. Show a small worked example of the
   generated source for an assignment (obtained with `ar-profile-analyzer` `get_source` from an
   existing profile under a module's `results/` directory, or from a small profiled test run).
6. **Crash triage playbook** — a short decision list for a native crash whose Java stack ends in
   `GeneratedOperationN.apply` / `NativeExecution`: which hypotheses sections 1–5 rule out, and
   where to look first (arguments at the JNI boundary, memory released before dispatch).

### 2. Javadoc corrections at the source of truth

- `NativeCompiler` — class Javadoc section "Library lifecycle" matching section 1.
- `BaseGeneratedOperation` — state that `GeneratedOperationN` classes are reservation slots.
- `NativeInstructionSet` — replace the deferring `Thread Safety` paragraph with the positive
  answer for generated kernels established in section 4.
- `Hardware` (`AR_HARDWARE_MEMORY_SCALE` block) — state which providers enforce the limit and
  the failure mode.
- `KernelMemoryGuard` — a "What this does not protect" paragraph if section 3 finds gaps.
- `MemoryData` — a short lifetime-contract paragraph pointing at the new internals doc's topic
  (by class names, not by path into `docs/plans/`).

### 3. Discoverability

- Add the new page to the internals index (`llms.txt` and/or `docs/README.md`, whichever lists
  internals docs) and link it from `base/hardware/README.md`'s Memory Management section and
  from `backend-compilation-and-dispatch.md`'s closing section.
- Delete `docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` once its gaps are covered
  (plans are temporary; see `docs/plans/CLAUDE.md`). Item 5 of that plan (ONNX runtime
  "pressure") is out of scope here unless the `extern/ml-onnx` source answers it directly.

## Approach

1. `consult` and `memory_recall` on `NativeCompiler`, `KernelMemoryGuard`,
   `HardwareMemoryProvider`, `MEMORY_SCALE` — prior sessions implemented the GC-lifecycle fix
   and left memories describing it.
2. Read the source for each section before writing a sentence about it. Every behavioral claim
   in the new doc must name the class (and method) it was verified in. No line numbers — they
   rot (a recent QA pass had to strip them from `ml-inference-pipeline.md`).
3. Where the source is ambiguous (especially library persistence across runs and per-backend
   reentrancy), verify empirically: run one existing small hardware test twice with the
   `ar-test-runner` (single test method, explicit timeout) and inspect the library directory
   between runs; use `ar-profile-analyzer` to read generated source.
4. If verification reveals a real defect (e.g. a provider that silently returns a zero pointer,
   or a kernel with shared mutable state), **document the current behavior accurately and record
   the defect in a memory and a follow-up plan** — do not fix production code in this task.
5. Store a memory per verified fact cluster as it is established (Rule 12 of `CLAUDE.md`).
6. Run the build validator (`checkstyle` at minimum, since Javadoc changes touch Java files) and
   `mvn clean install -DskipTests` to confirm Javadoc edits compile.

## Success Criteria

- `docs/internals/native-runtime-lifecycle.md` exists, covers sections 1–6, and each claim cites
  a class/method. No claim is stated that the author did not verify.
- `NativeInstructionSet` no longer defers the thread-safety question for generated kernels.
- `NativeCompiler`, `BaseGeneratedOperation`, and `Hardware` Javadoc state library lifetime and
  memory-limit enforcement explicitly.
- The page is reachable from the hardware README and the internals index.
- `consult` on "is a generated kernel library reused across JVM runs" and "what happens when
  AR_HARDWARE_MEMORY_SCALE is exceeded" returns the new page as a source.
- `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` is deleted (or reduced to only the ONNX item).
- Build validator passes; no production behavior changed.

## Dependencies

None. Builds directly on the three compilation-pipeline internals docs (already landed) and the
native-memory GC lifecycle work (`KernelMemoryGuard`, already landed).

## What This Sets Up

- A correct mental model of kernel/memory lifetime is a prerequisite for the performance work
  on compile time and kernel reuse (`CONVOLUTION_COMPILE_TIME.md`, `QWEN_PERFORMANCE.md`): any
  cross-run kernel caching proposal must start from an accurate statement of what persists today.
- It completes the end-to-end internals narrative (graph → process → compile → **run and
  release**), which is the documentation corpus a platform-aware model would be trained on.
