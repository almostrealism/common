# base/ — Foundation Layer

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

## Modules

- [meta](meta/README.md) — Foundational interfaces for naming, identity, lifecycle, no dependencies
- [relation](relation/README.md) — Computation relations and process optimization
- [code](code/README.md) — Expression trees, scope management, code generation
- [io](io/README.md) — File I/O, console logging, output features
- [collect](collect/README.md) — PackedCollection: hardware-accelerated tensor storage
- [hardware](hardware/README.md) — GPU/CPU acceleration, memory management, kernel compilation
