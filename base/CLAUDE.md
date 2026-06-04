# base/ — Foundation Layer

## CRITICAL: NEVER Create New Maven Modules

**Agents MUST NEVER create new Maven modules.** The Maven module structure is externally controlled. If a task requires a new module, **STOP and abandon the task**. Do not create new `pom.xml` files, add `<module>` entries to parent POMs, or create directory structures constituting a new module. Document the requirement in completion notes instead — the project owner handles module creation.

---

The foundation of the AR framework. Provides core primitives, the execution model,
memory management, code generation, and hardware acceleration backends. Everything
above this layer depends on it; nothing here depends on higher layers.

## What Belongs Here

- Core type system and UML abstractions (Nameable, Typed, Named)
- Computation relations, process lifecycle, and optimization infrastructure
- Expression trees, scope management, code generation primitives
- File I/O, console logging, output formatting
- PackedCollection: hardware-accelerated tensor storage and memory layout
- CollectionProducer: lazy computation API for building expression graphs
- GPU/CPU acceleration backends, kernel compilation, memory management
- Hardware abstraction layer (Metal, OpenCL, JNI bridges)

## What Does NOT Belong Here

- Mathematical domain types like Vector, Matrix, Ray, or distributions — those belong in `compute/`
- Neural network layers, cells, or graph structures — those belong in `domain/graph`
- Color, lighting, or rendering logic — those belong in `domain/color` or `engine/render`
- Audio synthesis or signal processing — those belong in `engine/audio`
- Any module that imports algebra, geometry, or time concepts — those belong in `compute/` or higher

## Key Conventions

- `meta` has zero external dependencies — it is the absolute foundation
- No circular dependencies between base modules
- PackedCollection is NOT a Java array — never use `System.arraycopy`, `Arrays.copyOf`, or tight `setMem` loops
- Use the Producer pattern: `cp(source).multiply(2.0).evaluate()`
- Call `Process.optimize()` before `Process.get()` — skipping this breaks expression embedding
- Never return null from `getValueAt()` in any Process implementation
- Only `IsolatedProcess` should break expression embedding

## Native handles are typed — using JNI is not a license to drop the type system

A native object (an `id<MTLBuffer>`, an `MTLSharedEvent`, a `cl_event`, a device pointer, …) is
represented by a **Java object** of a type that means that thing — e.g. `MTLBuffer`, `MTLEvent`,
`MTLCommandBuffer`, `CLSemaphore`. The raw `long`/`int` native pointer lives **only** inside the
thin JNI binding class (`MTL`, `CL`) as the argument/return type of the `native` methods; it does
**not** leak into any higher-level code. Never carry native objects around as bare `long`s, never
key collections on them (`Set<Long>`, `Map<Long, …>`), and never compare or store native addresses
outside the wrapper. If you need identity or bookkeeping for a native object, hold the wrapper
object and use it. The fact that a value crosses JNI does not make it acceptable to stop caring
about its type.

## Modules

- [meta](meta/README.md) — Foundational interfaces for naming, identity, lifecycle, no dependencies
- [relation](relation/README.md) — Computation relations and process optimization
- [code](code/README.md) — Expression trees, scope management, code generation
- [io](io/README.md) — File I/O, console logging, output features
- [collect](collect/README.md) — PackedCollection: hardware-accelerated tensor storage
- [hardware](hardware/README.md) — GPU/CPU acceleration, memory management, kernel compilation
