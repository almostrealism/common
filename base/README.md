# base — Foundation

The foundation layer provides the core primitives, abstractions, and execution infrastructure upon which all other layers are built. These modules have minimal external dependencies and define the fundamental programming model for the entire framework.

## Modules

### [ar-uml](uml/README.md)
Foundational annotations and interfaces for semantic metadata and system introspection. Provides semantic annotations (`@ModelEntity`, `@Function`, `@Stateless`), naming contracts (`Named`, `Nameable`), lifecycle management (`Destroyable`, `Lifecycle`), and signature-based identification for deduplication. Zero external dependencies.

### [ar-io](io/README.md)
Core infrastructure for logging, performance monitoring, alerts, and lifecycle management. Includes hierarchical console logging, `TimingMetric` and `DistributionMetric` for performance tracking, an alert system with pluggable delivery, and lazy initialization via `SuppliedValue`. Used across all modules for diagnostics and output.

### [ar-relation](relation/README.md)
Defines the computational programming model: `Producer`/`Evaluable` two-phase execution (describe computation, then evaluate), `Countable` for parallel kernel sizing, and the `Process` framework for composable, optimizable work units. Proper use of process optimization is critical for preventing expression tree explosion, test timeouts, and memory issues.

### [ar-code](code/README.md)
Code generation, expression trees, and traversal abstractions. Provides the `Expression` system for typed expression trees, `Scope` for hierarchical code organization, `TraversalPolicy` for multi-dimensional collection shapes, and kernel abstractions for compiling to OpenCL, Metal, and JNI backends. Includes `ExpressionCache` to prevent exponential expression tree growth.

### [ar-collect](collect/README.md)
`PackedCollection` — the fundamental data structure for multi-dimensional numerical data with hardware acceleration. Stores data as flat arrays interpreted via `TraversalPolicy` to represent N-dimensional tensors. `CollectionProducer` provides 40+ chainable operations as a lazy computation model. Supports zero-copy views through delegation and automatic heap/off-heap memory management.

### [ar-hardware](hardware/README.md)
Hardware abstraction for GPU/CPU acceleration with zero-code backend configuration. Manages memory allocation (zero-copy delegation, thread-local arenas, GC-integrated native memory), operation compilation and caching, and multi-backend execution (CPU, OpenCL, Metal). Instruction caching provides up to 10,000x speedup for repeated operations. `AR_HARDWARE_LIBS` is auto-detected.
