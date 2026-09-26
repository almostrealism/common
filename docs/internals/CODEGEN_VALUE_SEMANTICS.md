# Codegen Value Semantics

**Audience:** Engineers and AI agents debugging a native crash — especially a
`0x0` dereference — whose Java-side stack ends in a
`GeneratedOperationN.apply` JNI call.

This page describes what the generated-kernel language *can* and *cannot*
express. Its purpose is to remove an entire class of hypothesis that a reader
coming from ordinary Java/JVM development will otherwise reach for: that a
generated kernel "nulled a pointer," "cleared a reference," or "wiped a field"
mid-invocation. None of those are expressible in the code the framework emits.

## What is actually true

The unit of generated code is a statement, and the only value-producing
statement is an assignment. Assignments are modeled by
`io.almostrealism.code.ExpressionAssignment`, whose constructor **rejects a
null value expression** — it throws `IllegalArgumentException` when the
assigned `Expression` is `null`. A statement is rendered to C by
`LanguageOperations.assignment(dest, value)` (plain assignment) or
`LanguageOperations.declaration(type, dest, value)` (declaration-assignment).
Both take a destination expression and a **value expression**; there is no
overload, literal, or primitive that assigns "nothing," "null," or "an erased
pointer."

Consequently:

- **Every assigned value is a numeric expression.** Writing `0.0` (or any
  other number) into a value slot is a numeric write, not a pointer-erase. It
  changes the contents of a location; it does not invalidate a pointer.
- **Pointer arguments are not produced inside the kernel.** The pointers a
  generated kernel operates on enter from outside, across the JNI boundary, as
  the `long[] arg` array of `NativeInstructionSet.apply`. The kernel body does
  not allocate, reassign, or zero them.
- **Pointer *identity and lifetime* are external and read-only; the *pointee*
  is not.** A generated kernel does not change which memory a pointer refers to
  or how long that memory lives — those are decided by the Java caller and the
  memory provider. It does, however, **write through** output-argument pointers:
  producing results in caller-owned buffers in place is the normal, intended
  behavior. "Read-only" describes the pointer value, never the bytes it targets.

## Worked example

A Java-level expression that *looks* like it might null a pointer — for example
assigning a zero into a destination — does not compile to a pointer-clearing
instruction, because the codegen language has no such instruction. It compiles
to one of exactly two things:

1. A numeric assignment such as `buffer[offset + i] = 0.0;` — a value write to
   a location the pointer already addresses. The pointer is untouched; only the
   number stored there changes.
2. Nothing at all — if the destination is not a writable reference, the
   assignment is rejected while the `Scope` is being built (the
   `ExpressionAssignment` constructor throws), long before any C is emitted.

There is no third outcome in which the emitted C sets a pointer variable to a
null/zero pointer.

## What cannot happen

- A generated kernel **cannot** set one of its pointer arguments to null.
- A generated kernel **cannot** free, unmap, or reallocate the memory a pointer
  argument refers to.
- Another thread **cannot** "wipe a pointer field to null" inside a running
  kernel, because there is no pointer field to wipe and no null to write —
  see [KERNEL_THREAD_SAFETY.md](KERNEL_THREAD_SAFETY.md).

Because none of these are expressible, a `0x0` dereference observed *inside* a
generated kernel can arise in only two ways:

1. **A pointer argument was already `0` at the JNI boundary** — i.e. the Java
   caller passed a zero content pointer. This is a caller-side bug (for
   example, memory that was deallocated or unmapped before dispatch). The
   framework tries to catch this before it reaches native code:
   `NativeInstructionSet.apply(long, RAM[], int[], int[], int, long)` validates
   that every `args[i].getContentPointer()` is non-zero and that the OpenCL
   command queue is either `-1` (no OpenCL) or non-zero, converting a silent
   `SIGSEGV` into a `NullPointerException`/`IllegalArgumentException` named for
   the kernel. See [OFF_HEAP_MEMORY.md](OFF_HEAP_MEMORY.md) for the
   use-after-free race this guards against.
2. **Arithmetic produced a zero (or out-of-range) offset** — for example an
   offset computation that wraps or subtracts a base from itself, yielding an
   address of `0x0` or an out-of-bounds access. This is an offset/indexing bug
   in the computation graph, not a pointer-lifetime bug.

## Debugging consequence

When you see `far: 0x0` (or an equivalent null-dereference signature) inside a
generated kernel, **start at the Java caller, not the kernel body.** The kernel
did not create the zero. Establish which argument was zero and why it was zero
at dispatch time:

- Was a `MemoryData`/`RAM` argument destroyed or unmapped before `apply`? (See
  the use-after-free race in [OFF_HEAP_MEMORY.md](OFF_HEAP_MEMORY.md) and the
  `KernelMemoryGuard` mitigation.)
- Or is an offset expression producing `0` / out-of-range for some index?

Do **not** spend iterations on "the kernel cleared a pointer," "a stale cached
kernel has the wrong argument layout" (see [KERNEL_CACHE.md](KERNEL_CACHE.md)),
or "a concurrent thread nulled a field." Those are mechanically impossible here.

## Related source

| Concern | Source identifier |
|---|---|
| Assignment statement; rejects null value | `io.almostrealism.code.ExpressionAssignment` |
| Rendering of assignments/declarations | `LanguageOperations.assignment`, `LanguageOperations.declaration` |
| Pointer validation at the JNI boundary | `NativeInstructionSet.apply(long, RAM[], int[], int[], int, long)` |
| Free-while-kernel-running mitigation | `org.almostrealism.hardware.mem.KernelMemoryGuard` |

## What would make these statements false

If the codegen language ever gains a nullable pointer type, a pointer-erase
primitive, or in-kernel reassignment of argument pointers, this page must be
updated — at that point "the kernel nulled a pointer" would become a valid
hypothesis again.
