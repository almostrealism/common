# Module Review Log

Reviews conducted against [MODULE_RUBRIC.md](MODULE_RUBRIC.md).

---

## ar-meta (formerly ar-uml)

**Reviewed:** 2026-03-23
**Branch:** feature/module-layer-review

### Concept
Foundational interfaces for naming, identity, indexed access, and lifecycle management. Zero dependencies — exists to provide shared contracts that `io` and `relation` both need without forcing a dependency between them.

### Types (post-cleanup)

| Type | Package | Downstream Imports | Role |
|------|---------|-------------------|------|
| Named | meta | 18 | Read-only name access |
| Nameable | meta | 11 | Mutable name access |
| Signature | meta | 18 | Identity/caching hashes |
| Multiple | meta | 16 | Indexed element access |
| Plural | meta | 7 | Positional value access |
| Destroyable | lifecycle | 61 | Resource cleanup |
| Lifecycle | lifecycle | 17 | Reset/reuse |

### Ratings

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Adequate | Not a single concept but a justified dependency-breaker. The types it contains are the shared vocabulary that both `io` and `relation` need. |
| Clarity | Strong | Each type has a distinct role. Named/Nameable split is clean. Multiple vs Plural overlap is the one soft spot. |
| Dependencies | Strong | Zero dependencies by design — the absolute root of the tree. |
| Necessity | Strong | All 7 types are actively used (61+ imports for Destroyable alone). |

### Actions Taken
- **Removed** `@Function`, `@ModelEntity`, `@Stateless`, `@ViewModel`, `Standards` — unused or barely-used marker annotations that added no behavioral value
- **Renamed** module from `ar-uml` to `ar-meta` (folder `base/uml/` → `base/meta/`)

### Open Questions
- `Multiple` vs `Plural` overlap — could these be consolidated?

---

## ar-relation

**Reviewed:** 2026-03-23
**Branch:** feature/module-layer-review

### Concept
Deferred computation model: describe computations with `Producer`, execute them with `Evaluable`, compose and optimize them with `Process` trees. Graph/tree structures model how computations compose.

### Key Types (52 total across 4 packages)

**Core (io.almostrealism.relation):**
- `Producer` (429 imports) — computation description, factory for Evaluable
- `Evaluable` (191) — compiled executable computation
- `Factor` (68) — Producer→Producer transformation
- `Provider` (32) — constant value wrapper
- `Countable` (31) — element count for parallelism
- `Delegated` (14) — transparent proxying
- `Node`, `Tree`, `Graph`, `Group`, `NodeGroup`, `NodeList`, `WeightedGraph`, `IndexedGraph` — composition structures
- ~~`Editable` (9) — removed: defunct UI property editor~~
- ~~`Sortable` (3) — removed: deprecated~~

**Process optimization (io.almostrealism.compute):**
- `Process`, `ParallelProcess` — composable/optimizable work units
- `ProcessContext`, `ProcessContextBase`, `ParallelProcessContext` — optimization context
- `ProcessOptimizationStrategy`, `ParallelismTargetOptimization`, `ReplicationMismatchOptimization`, `CascadingOptimizationStrategy` — optimization strategies
- `ParallelismSettings` — scoring functions

**Relational Frame Theory (io.almostrealism.frames):**
- `Predicate`, `CoordinationFrame`, `ComparativeFrame`, `SpatialFrame`, `TemporalFrame`, `CausalFrame`, `DiecticFrame` — cognitive modeling (misplaced)

**Streaming (io.almostrealism.streams):**
- `StreamingEvaluable`, `StreamingEvaluableBase`, `EvaluableStreamingAdapter` — async computation

### Ratings

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Adequate | Core Producer/Evaluable/Process story is strong. `frames` package (7 RFT types) is a major outlier. |
| Clarity | Adequate-to-Strong | Core model is clean. 5 graph/collection abstractions is heavy but principled. |
| Dependencies | Strong | Depends only on ar-meta. All usages justified. |
| Necessity | Strong | Core types are load-bearing (429+ imports). Dead weight exists at edges. |

### Actions Taken
- **Removed** `Sortable` — deprecated, 3 imports, use Comparable/Comparator instead
- **Removed** `Function<IN,OUT>` — marker interface with empty body, only 2 implementors, name collision with java.util.function.Function
- **Removed** `Editable` + `EditableFactory` — defunct UI property editor infrastructure with zero callers. Removed `implements Editable` and all Editable method implementations from 8 shader/texture classes (DiffuseShader, HighlightShader, SilhouetteShader, BlendingShader, ImageTexture, StripeTexture, ReflectionShader, RefractionShader). StripeTexture refactored from Object[] props array to direct typed fields.

### Resolved: frames package
The `frames` package (7 RFT types, currently zero importers) represents a forward-looking extension of the module's core idea: extending relational descriptions from computational domains to real-world semantic domains (temporal, causal, spatial, etc.). Each frame type carries entailment rules that a future solver could exploit. The types are embryonic but conceptually belong here — they are *relations*, and this is the relation module. Documented in the updated README as "Semantic Relations and Relational Frames."

---

## ar-io

**Reviewed:** 2026-03-23
**Branch:** feature/module-layer-review

### Concept
Logging, metrics, alerting, and lifecycle management.

### Key Types (22 total across 2 packages)

**Core I/O (org.almostrealism.io):**
- `Console` (112 imports), `ConsoleFeatures` (111) — hierarchical logging
- `SystemUtils` (45) — platform detection, properties, file ops
- `OutputFeatures` (28) — file output listeners
- `TimingMetric` (16), `DistributionMetric` (5), `MetricBase` (1) — performance metrics
- `Alert` (2), `AlertDeliveryProvider` (1) — structured alerting
- `Describable` (13) — self-describing objects
- `PrintWriter` (7), `FilePrintWriter`, `PrintStreamPrintWriter` — indented text output
- `JobOutput` (10), `OutputHandler` (7) — FlowTree job results (misplaced?)
- `RSSFeed` (2) — RSS 2.0 generator (misplaced)
- `Bits` (1) — bit packing (misplaced)
- `Storable` (1), `DecodePostProcessing` (2) — serialization

**Lifecycle (org.almostrealism.lifecycle):**
- `SuppliedValue`, `ThreadLocalSuppliedValue` — lazy initialization with lifecycle
- `WeakRunnable` — weak-reference runnable

### Ratings

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Adequate | Core logging/metrics is coherent. RSSFeed and Bits are acceptable as general I/O utilities. JobOutput/OutputHandler are FlowTree concepts but structurally trapped here (see below). |
| Clarity | Adequate | Console hierarchy is clean. No type confusion in the core concern. |
| Dependencies | Strong | Depends only on ar-meta. Uses Named and Signature, both justified. |
| Necessity | Adequate | Console/SystemUtils are essential. Low-import types (RSSFeed, Bits) are acceptable as I/O primitives for future use. |

### Actions Taken
- (none yet)

### Relocation Investigation

**`RSSFeed`** — Only imported by flowtree (2 files). Should move to flowtree. Trivial.

**`Bits`** — Only imported by base/code (1 file). Acceptable in io as a low-level utility. No action needed.

**`JobOutput` + `OutputHandler`** — These are FlowTree job system concepts trapped in the foundation layer. Ideally they belong in flowtreeapi. However, `DatabaseConnection` in graphpersist (730 lines) is deeply intertwined with both types, and graphpersist does not depend on flowtreeapi — they are peers. The clean fix requires extracting the job-output-handling portions of `DatabaseConnection` into a class that lives in flowtree (which depends on both graphpersist and flowtreeapi), leaving `DatabaseConnection` as a pure database abstraction. This is a meaningful refactor — deferred for a dedicated session.

**`Editable`** — Used by domain/color (6 files) and engine/render+utils (3 files). It's a UI/tooling concern in the computation foundation, but it's already in a position where everything that needs it can reach it. Relocating upward would require domain/color to take on a new dependency. Leave as-is.

### Open Questions
- `RSSFeed` — move to flowtree (trivial, deferred)
- `JobOutput` / `OutputHandler` / `DatabaseConnection` — refactor as described above (deferred)

---

## ar-code

**Reviewed:** 2026-03-24
**Branch:** feature/module-layer-review

### Concept
Code generation infrastructure: expression trees, scope management, kernel indexing, and compilation targeting all backends (OpenCL, Metal, JNI). The bridge between the relational computation model (ar-relation) and hardware execution (ar-hardware).

### Package Structure (post-cleanup: 10 packages, ~208 types)

| Package | Types | Role |
|---------|-------|------|
| `io.almostrealism.code` | 43 | Core: Computation, ComputationBase, ProducerComputation, adapters |
| `io.almostrealism.expression` | 47 | Expression tree AST: Sum, Product, Sine, Conditional, etc. |
| `io.almostrealism.collect` | 44 | Collection expressions, traversal policies, shape algebra |
| `io.almostrealism.kernel` | 27 | Kernel indexing, index sequences, matrix operations |
| `io.almostrealism.scope` | 21 | Scope hierarchy: Variable, Argument, Cases, Repeated |
| `io.almostrealism.profile` | 9 | Operation profiling, timing, metadata |
| `io.almostrealism.lang` | 6 | Backend code generation: CodePrintWriter, LanguageOperations |
| `io.almostrealism.compute` | 6 | Additional optimization strategies (extends relation's compute package) |
| `io.almostrealism.util` | 5 | Internal utilities: FrequencyCache, NumberFormats, Sequence |
| `io.almostrealism.concurrent` | 2 | Semaphore for async kernel execution |

### Ratings

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Adequate-to-Strong | Core code-gen story is strong (~208 types). Minor outliers cleaned up. |
| Clarity | Adequate | Expression and scope hierarchies are clean. Package naming collisions with collect and compute across modules are confusing but deferred. |
| Dependencies | Strong | ar-relation + ar-io, both essential. |
| Necessity | Strong | 6,859 imports across 24 modules. base/hardware alone accounts for 5,197 code.* imports. |

### Actions Taken
- **Removed** `io.almostrealism.html` package (8 types) — web page generation framework with only 1 downstream user (Tensor.toHTML). Replaced Tensor's implementation with plain string concatenation.
- **Moved** `io.almostrealism.cycle.Setup` to `io.almostrealism.lifecycle.Setup` in ar-meta — lifecycle initialization interface belongs with Destroyable and Lifecycle, not in code generation. Updated 24 import sites.
- **Created** `io.almostrealism.sequence` package — consolidated the integer sequence concept that underpins kernel computation. Moved `Sequence` and `ArrayItem` from `io.almostrealism.util`, and `Index`, `DefaultIndex`, `IndexChild`, `IndexValues`, `IndexSequence`, `ArithmeticIndexSequence`, `ArrayIndexSequence`, `SequenceGenerator`, `KernelSeries`, `KernelSeriesMatcher` from `io.almostrealism.kernel`. These types form the algebraic foundation for kernel index mappings — a kernel is fundamentally a computation over the positive integers, and these types represent that mapping.

### Structural Debt (deferred)
- **Split packages across modules**: `io.almostrealism.collect` spans ar-code (44 types) and ar-collect; `io.almostrealism.compute` spans ar-code (6 types) and ar-relation (10 types). This is a common pattern where types "wanted" to live in the lower module but needed something from higher up.
- **ar-collect is empty**: The 44 collection types in ar-code's `io.almostrealism.collect` are hardware-agnostic and conceptually belong in ar-collect, but 22 files in ar-code's expression/scope/kernel packages reference them (Expression→CollectionExpression coupling). Moving them creates a circular module dependency. The fix requires breaking the expression/collection coupling — either by abstracting the collection references out of expressions, or by moving the dependent expressions alongside the collection types. This is a significant refactoring effort, deferred for a dedicated session.

---

## ar-hardware

**Reviewed:** 2026-03-24
**Branch:** feature/module-layer-review

### Concept
Hardware acceleration: GPU/CPU kernel compilation, memory management, and multi-backend execution (Metal, OpenCL, JNI, JVM, NIO, external).

### Package Structure (157 types across 16 packages)

| Package | Types | Role |
|---------|-------|------|
| `org.almostrealism.hardware` | 24 | Core: Hardware, MemoryData, AcceleratedOperation, OperationList |
| `org.almostrealism.hardware.metal` | 23 | Metal backend |
| `org.almostrealism.hardware.cl` | 21 | OpenCL backend |
| `org.almostrealism.hardware.mem` | 20 | Memory management, heap, allocation |
| `org.almostrealism.hardware.jni` | 15 | JNI native compilation |
| `org.almostrealism.hardware.instructions` | 10 | Scope compilation, execution key caching |
| `org.almostrealism.c` | 10 | C code generation for native kernels |
| `org.almostrealism.hardware.computations` | 7 | Assignment, Loop, Periodic |
| `org.almostrealism.nio` | 6 | NIO shared memory / native buffer interop |
| `org.almostrealism.hardware.ctx` | 6 | Compute context, thread-local context |
| `org.almostrealism.hardware.kernel` | 4 | Kernel series cache, traversal operations |
| `org.almostrealism.hardware.external` | 4 | External compute context |
| `org.almostrealism.hardware.profile` | 2 | Profiling data |
| `org.almostrealism.hardware.jvm` | 2 | JVM memory provider |
| `org.almostrealism.hardware.arguments` | 2 | Process argument evaluation |
| `org.almostrealism.concurrent` | 1 | SuspendableThreadPoolExecutor |

### Ratings

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Strong | Every package serves hardware acceleration. No outliers. |
| Clarity | Adequate-to-Strong | Consistent backend pattern across all backends. Size is inherent complexity. |
| Dependencies | Strong | ar-collect + JOCL, both essential. |
| Necessity | Strong | 561 downstream imports. All packages active. |

### Actions Taken
- None needed — this module is well-organized.

---

## Compute Layer (ar-algebra, ar-geometry, ar-stats, ar-time)

**Reviewed:** 2026-03-24
**Branch:** feature/module-layer-review

### Overview

The compute layer provides typed mathematical abstractions over the base layer's hardware-accelerated primitives. Clean linear dependency chain: `ar-hardware → ar-algebra → {ar-geometry, ar-stats} → ar-time`.

### Module Ratings

| Module | Types | Coherence | Clarity | Dependencies | Necessity |
|--------|-------|-----------|---------|--------------|-----------|
| ar-algebra | 93 | Strong | Adequate | Strong | Strong (170 files) |
| ar-geometry | 34 | Strong | Strong | Strong | Strong (87 files) |
| ar-stats | 4 | Strong | Strong | Strong | Weak (5 files) |
| ar-time | 19 | Strong | Strong | Strong | Strong (85 files) |

### Notes
- **ar-algebra** hosts `org.almostrealism.collect` (PackedCollection, CollectionProducer, feature interfaces) because these concrete types need hardware access. This is the documented split-package debt from the ar-collect review.
- **ar-stats** has only 4 types and 5 downstream imports. Justified as a future growth area for Monte Carlo, probabilistic inference, and statistical modeling — but should be monitored.
- **ar-time** depends on ar-geometry for trigonometric functions. This is reasonable given that trig is foundational to signal processing (FFT, etc.).

### Actions Taken
- None needed — the compute layer is well-organized.

---

## Engine Layer — Below Utils

### ar-optimize

**Reviewed:** 2026-03-24

19 types, 1 package. Depends only on ar-graph. ModelOptimizer owns training loops, PopulationOptimizer owns evolutionary algorithms, loss functions (MSE, MAE, NLL), Adam optimizer. 26 downstream files.

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Strong | Single package, single purpose. |
| Clarity | Strong | 19 types, no overlap, clear naming. |
| Dependencies | Strong | Only ar-graph. |
| Necessity | Strong | 26 files. |

No actions needed.

### ar-utils

**Reviewed:** 2026-03-24

31 main types + 158 test types. Depends on ar-space, ar-chemistry, ar-optimize. The **gate between platform infrastructure and application-facing modules** — everything above utils can assume the full platform is available.

| Criterion | Rating | Notes |
|-----------|--------|-------|
| Coherence | Weak-to-Adequate | Testing core (90.6% of usage) is strong. Auth, events, and misc utilities are grab-bag items justified by dependency convenience. |
| Clarity | Adequate | TestSuiteBase + @TestDepth pattern is clean. Module name "utils" communicates nothing. |
| Dependencies | Adequate | Heavy deps justified for cross-module testing, but creates transitive bloat. |
| Necessity | Mixed | Testing: Strong (296 test subclasses). Non-testing: varies. |

**Why 158 test classes live here:** Tests often need types from multiple modules (e.g., testing PackedCollection memory performance with trig functions requires both collect and geometry). Utils sits high enough in the dependency tree to bring everything together. This is intentional, though some simpler tests could move to their respective modules.

#### Actions Taken
- **Removed** `GoogleImagery` — zero imports, dead code.
- **Moved** auth package (3 types: Login, Authenticatable, AuthenticatableFactory) from ar-utils to ar-utils-http. Added ar-utils-http dependency to flowtree (no new transitive external libraries — flowtree already had ar-utils and jackson-databind).

#### Deferred Improvements
- **event package** (6 types) — Only used in utils-http test. Could move to utils-http.
- **Test migration** — Many of the 158 test classes could move to their respective modules if the CI pipeline is updated to run tests for all modules (currently skips some, creating a gap where tests are written but not executed).

---

## Domain Layer (ar-color, ar-heredity, ar-graph, ar-physics, ar-space, ar-chemistry, ar-llvm)

**Reviewed:** 2026-03-24
**Branch:** feature/module-layer-review

### Module Ratings

| Module | Types | Coherence | Clarity | Dependencies | Necessity |
|--------|-------|-----------|---------|--------------|-----------|
| ar-color | 57 | Strong | Strong | Strong | Strong (63 files) |
| ar-heredity | 24 | Strong | Strong | Strong | Strong (60 files) |
| ar-graph | 69 | Strong | Strong | Strong | Strong (135 files) |
| ar-physics | ~31 (post-move) | Strong | Adequate | Strong | Adequate (13 non-chemistry files) |
| ar-space | 42 | Strong | Strong | Strong | Adequate (21 files) |
| ar-chemistry | 23 (post-refactor) | Strong | Strong | Strong | Adequate (specialized) |
| ~~ar-llvm~~ | ~~2~~ | — | — | — | ~~Removed (zero usage)~~ |

### Actions Taken

- **Removed** ar-llvm module entirely — 2 types, zero downstream imports, aspirational GraalVM integration superseded by the JNI compilation path in ar-hardware. Removed from parent pom.xml and docs/index.html.

- **Moved 13 atomic types from ar-physics to ar-chemistry** — Atom, Atomic, Element (interface), Electron, Electrons, Orbital, Shell, SubShell, Spin, Substance, AtomicProtonCloud, Valence, Photon. These are atomic/molecular modeling concepts, not macro-scale physics. ar-physics now focuses on macro-scale simulation (rigid body, absorbers, photon fields, physical constants).

- **Replaced 118 element classes with Element enum** — Collapsed 118 individual element class files + PeriodicTable.java into a single `Element` enum implementing `Atomic`. Each enum constant carries its atomic number and shell configuration. All PeriodicTable grouping methods (alkali metals, noble gases, periods, groups, orbital blocks) preserved as static methods on the enum. Module went from 129 types to 23.

### Notes
- **ar-physics** retains macro-scale types: RigidBody, Absorbers, PhotonField, PhysicalConstants, BlackBody, Volume, Pressure, Clock, light sources
- **ar-graph** has 2 types in the `org.almostrealism` root package — minor but odd
- **ar-heredity** is surprisingly well-used (60 files) driven by studio/compose audio optimization
