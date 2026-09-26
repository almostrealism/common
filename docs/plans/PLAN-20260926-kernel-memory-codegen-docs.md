# PLAN: Document the Native Kernel Compilation Cache, Off-Heap Memory Model, and Codegen Value Semantics

**Date:** 2026-09-26
**Slug:** kernel-memory-codegen-docs
**Category:** 1 — Documentation
**Estimated Complexity:** Medium

---

## Title

Document the three subsystems that most reliably mislead investigators of
native-kernel behavior: the on-disk generated-kernel cache lifecycle, the
off-heap memory model, and the "no-null" value semantics of the codegen
language — plus the associated class-level Javadoc that makes each subsystem
self-describing at the source.

## Category

**Documentation.** Evaluated the four task categories in priority order.
Documentation is the first category with genuine, unaddressed, high-leverage
work, so this is where the task lands. The specifics are grounded in a prior
investigation, not speculation (see Motivation).

## Motivation

The single hardest thing to reason about in this platform is the boundary
between Java orchestration and native execution: how a `CollectionProducer`
graph becomes a compiled dylib, how that dylib's lifetime is managed, how the
off-heap memory it reads and writes is allocated and freed, and what the
generated code is and is not allowed to express. This is exactly the region
where both humans and LLM agents burn the most iterations on hypotheses that
are *mechanically impossible* — because the mental model they bring from
ordinary Java/JVM development does not apply.

`docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` captured this concretely:
a real crash investigation produced three substantively wrong hypotheses in a
row, each traceable to a missing or misleading piece of documentation rather
than to misread evidence. The wrong turns were:

1. "Clear the stale kernel cache" — impossible, because the generated dylibs
   are destroyed and rebuilt every JVM start; there is no cross-run reuse.
2. "The 1024MB off-heap budget was exhausted and the allocator returned a null
   pointer" — impossible, because that figure is not a framework-enforced
   budget and exhaustion surfaces as an exception/OS-OOM, never a silent
   zero-valued pointer.
3. "A thread nulled a pointer field inside the kernel mid-invocation" —
   impossible, because the codegen language has no null and no
   pointer-erase operation; pointers enter read-only across the JNI boundary.

Each of these is a *rule of the platform* that, once written down, permanently
removes an entire class of dead-end hypothesis. That is the highest-leverage
kind of documentation there is: it does not merely describe what the code does,
it tells a reader (human or model) what *cannot* happen, which is precisely the
knowledge a fresh investigator lacks.

This connects directly to the larger vision. If we intend to eventually train
models that study and reason about this codebase, the most valuable text we can
give them is not another API listing — it is the set of hard invariants that
distinguish this platform's execution model from the generic one every model
already carries. A model that "knows" that generated kernels cannot null a
pointer will not waste inference proposing that they might. Documentation that
encodes impossibility is documentation that compounds.

## Scope

This task operationalizes `KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` into
concrete, reviewable deliverables. It is a documentation-only change: **no
production code behavior changes.** Javadoc additions are the only edits to
`src/main` files.

### Verified starting state (as of this plan)

- None of the four proposed internals pages exist yet:
  `docs/internals/KERNEL_CACHE.md`, `OFF_HEAP_MEMORY.md`,
  `CODEGEN_VALUE_SEMANTICS.md`, `KERNEL_THREAD_SAFETY.md` are all **missing**.
- `base/hardware/docs/INSTRUCTION_CACHING.md` exists but documents only the
  **in-JVM signature cache** (`InstructionSetManager`, `FrequencyCache`,
  signature/execution keys). It does not cover the **on-disk dylib** lifetime,
  the off-heap budget, or codegen value semantics.
- `NativeCompiler.java` has no class-level Javadoc describing the on-disk cache
  lifecycle (purge-at-JVM-start).
- All target source files exist and are correctly named in the gaps doc:
  `NativeCompiler`, `BaseGeneratedOperation`, `Hardware`, `MemoryData`,
  `NativeInstructionSet`, and `mem/KernelMemoryGuard`.

### Deliverables

**A. New internals pages** (single-page references under `docs/internals/`),
in the priority order established by the source plan:

1. `docs/internals/CODEGEN_VALUE_SEMANTICS.md` *(highest leverage)* — the
   grammar of allowed assignments in the codegen language; the fact that there
   is no `null` and no pointer-erase primitive; that pointer arguments are
   read-only references to memory owned outside the kernel; the two (and only
   two) ways a `0x0` dereference can arise inside a generated kernel (a
   zero pointer at the JNI boundary, or arithmetic producing a zero offset);
   and the resulting investigation playbook ("when you see `far: 0x0`, start at
   the Java caller, not the kernel body"). Include a short worked example.

2. `docs/internals/KERNEL_CACHE.md` — the on-disk generated-kernel dylib cache:
   directory location, key/identity scheme, the precise mechanism by which
   entries are invalidated at JVM startup, and the guarantee that no entry
   survives JVM termination. Explicitly contrast this with the in-JVM signature
   cache documented in `INSTRUCTION_CACHING.md` and cross-link the two so a
   reader understands there are two distinct caches. State the consequence: a
   native crash in `GeneratedOperationN.apply` cannot be caused by a "stale
   dylib from a prior build."

3. `docs/internals/OFF_HEAP_MEMORY.md` — must carefully distinguish the four
   things the gaps doc warns are easy to conflate: (a) the *configured* limit
   and where it is read (`AR_HARDWARE_MEMORY_SCALE`, `Hardware`); (b) what
   actually *enforces* it (and that there is no "automatic GC by bytes used"
   budget); (c) the JVM-GC-driven free path (a `MemoryData` whose holder
   becomes unreachable has its native block released by `Cleaner`/finalization);
   (d) the real *exhaustion* failure mode (allocation exception / OS-OOM, never
   a silent null pointer). Describe the actual race an investigator should
   suspect — a still-numerically-valid pointer whose backing page was unmapped
   after its holder became GC-eligible — and the role of
   `KernelMemoryGuard.acquireFor(data)`.

4. `docs/internals/KERNEL_THREAD_SAFETY.md` — promote the deferral currently in
   `NativeInstructionSet`'s Javadoc ("the underlying native code must be
   thread-safe if this is required") into a positive statement about the
   *generated* kernels that are the only implementations shipped: whether a
   generated `apply()` carries per-instance mutable state, and whether a single
   instance is safe to invoke concurrently.

**B. Class-level Javadoc** on the source files, so the invariants live at the
code and not only in prose:

- `NativeCompiler` — on-disk cache lifecycle (location, when written, when/by
  what mechanism removed, JVM-termination guarantee).
- `BaseGeneratedOperation` — clarify that the pre-allocated
  `GeneratedOperationN.java` classes are reservation slots, not stored kernels;
  the compiled artifact behind each slot is generated fresh per JVM run.
- `Hardware` (where configured off-heap RAM is read) — what the number means in
  practice and what enforces it.
- `MemoryData` — the Java-reference-to-native-block lifetime contract and the
  explicit statement that there is no separate "GC by bytes used" budget.
- `NativeInstructionSet` — replace the thread-safety deferral with a positive
  statement for generated kernels.

**C. Index wiring** — add the four new pages to `llms.txt` under the internals
section (alongside `backend-compilation-and-dispatch.md`) so both the docs
portal and the LLM documentation index surface them.

### Out of scope

- Misunderstanding 5 from the source plan ("ONNX-induced pressure") — the
  lowest-priority gap; defer unless it falls out naturally while writing
  `OFF_HEAP_MEMORY.md`.
- Any change to compilation, memory-management, or codegen *behavior*. This is
  documentation only.
- A broad survey of all undocumented framework behavior. If writing these pages
  reveals further adjacent gaps, capture them in a new follow-up plan rather
  than expanding this one.

## Approach

1. **Consult first, then read the code that owns each invariant.** For every
   claim, verify it against the source before writing it down. The failure mode
   this whole task exists to prevent is confidently documenting a plausible but
   false mechanism — so each page must cite the class/method that establishes
   the invariant, and where a claim cannot be confirmed from the source, it must
   be stated as an open question rather than asserted. Use `ar-profile-analyzer`
   `get_source` on a real profiled run to read the generated kernel source and
   argument bindings when documenting codegen value semantics — do not theorize
   about what the emitted code looks like.

2. **Reference code by stable identifier, never by line number** (per
   `docs/CLAUDE.md`): name the class, method, or field, not `File.java:NNN`.

3. **Write impossibility explicitly.** Each page should contain a short "What
   cannot happen" section, because the ruled-out hypotheses are the highest-value
   content. Mirror the crash-signature → correct-first-move framing from the
   source plan.

4. **Cross-link, don't duplicate.** `KERNEL_CACHE.md` (on-disk) and
   `INSTRUCTION_CACHING.md` (in-JVM) are different caches; link between them and
   state the distinction rather than restating either.

5. **Keep the Javadoc and the internals page consistent.** The page is the
   long-form reference; the Javadoc is the at-the-source summary that points to
   it by name. They must not contradict each other.

## Success Criteria

- The four new internals pages exist, each with: an audience line, a "what is
  actually true" statement of the invariant, a "what cannot happen" section, and
  the debugging consequence. Every mechanical claim is traceable to a named
  class/method.
- Class-level Javadoc is added to `NativeCompiler`, `BaseGeneratedOperation`,
  `Hardware`, `MemoryData`, and `NativeInstructionSet` as described, with no
  source-line-number references.
- `llms.txt` lists the four new pages under the internals section.
- `mvn clean install -DskipTests` succeeds (Javadoc compiles).
- `ar-build-validator` passes (checkstyle, code_policy, test_timeouts,
  duplicate_code) — Javadoc additions must not trip checkstyle (no `var`, no
  `@SuppressWarnings`, no forbidden log prefixes, file-length limits).
- A spot-check of the three originally-wrong hypotheses from the source plan:
  each new page, read cold, would steer an investigator away from the impossible
  hypothesis and toward the correct first move.
- `docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` is updated to mark which
  gaps this task closed (or a note added at its top pointing to the pages), so
  the gaps doc does not re-trigger the same proposal.

## Dependencies

None hard. This is self-contained documentation work against existing,
verified source files. It benefits from — but does not require — access to a
profiled run for `ar-profile-analyzer` inspection of generated kernel source;
if none is available, a small profiled reproduction can be wired to inspect the
emitted code for the `CODEGEN_VALUE_SEMANTICS.md` worked example.

## Estimated Complexity

**Medium.** Four short pages plus five Javadoc edits is not large in volume, but
the value depends entirely on *accuracy* — the whole point is to replace
confident-but-wrong mental models, so getting a claim wrong here is worse than
omitting it. The care required to verify each invariant against the source (and
to distinguish the four easily-conflated off-heap concepts) is what makes this
medium rather than small. Achievable in a single focused session.

## Sequence Context

- **What came before:** The in-JVM signature cache is already well documented
  (`INSTRUCTION_CACHING.md`), and the compilation/dispatch path has an internals
  page (`backend-compilation-and-dispatch.md`). Recent automated QA work has
  kept documentation from drifting relative to code. What has *not* been written
  is the layer of hard invariants at the Java↔native boundary that the crash
  investigation exposed as missing.
- **What this task is:** the first deliberate step to write down the platform's
  *impossibility rules* at that boundary — the knowledge that most reliably
  prevents wasted investigation, for humans and for the models we hope to train.
- **What comes after:** with the boundary invariants documented, natural
  follow-ups are (a) the deferred ONNX-pressure note; (b) a broader
  "debugging native crashes" playbook that stitches these pages into a single
  investigation protocol (companion to `ci-investigation-protocol.md`); and
  eventually (c) using this corpus as high-signal training material for a
  model that reasons about the platform's execution model.
