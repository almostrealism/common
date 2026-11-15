# base — Concept Map

The foundation layer. It defines the programming model that every layer above it speaks:
how a computation is *described*, *shaped*, *compiled*, and *run* on hardware. Nothing here
depends on a higher layer; everything above depends on these concepts.

This document maps the concepts the base modules introduce and how they fit together. It is a
companion to [base/README.md](README.md) (module-by-module reference) and to the
cross-cutting concept map in [docs/internals/concept-map.md](../docs/internals/concept-map.md),
which records the type-alignment families and gaps. Where a concept has its own internals
document, this map links to it rather than restating it.

## The spine: describe, then execute

The single idea the whole framework is built on is that **Java describes a computation; native
code executes it**. The base layer provides the vocabulary for that description and the
machinery that turns it into a kernel.

| Concept | Module (package) | Role |
|---|---|---|
| `Producer<T>` | `relation` (`io.almostrealism.relation`) | A described computation — the "recipe", not the result. `extends Supplier<Evaluable<? extends T>>, Computable, Node, Destroyable`. |
| `Evaluable<T>` | `relation` (`io.almostrealism.relation`) | The compiled, executable form of a fully described `Producer`. `producer.get()` compiles; `evaluable.evaluate(args)` runs. |
| `Process<P, T>` | `relation` (`io.almostrealism.relation`) | A node in the optimizable tree a `Producer` graph becomes. `optimize()` decides isolation and parallelism before `get()`. |
| `Computation<T>` | `code` (`io.almostrealism.code`) | A computation that emits a `Scope` of generated code for a kernel. |
| `Scope<T>` | `code` (`io.almostrealism.scope`) | A hierarchical container of statements, variables and nested scopes — the unit of code generation. |
| `Expression<T>` | `code` (`io.almostrealism.expression`) | A node in a typed expression tree (`Sum`, `Product`, `Sine`, `Conditional`, …) that simplifies and prints to a target language. |
| `TraversalPolicy` | `code` (`io.almostrealism.collect`) | The shape: dimensions and traversal rates that impose N-dimensional structure on flat memory, plus the fixed/variable count distinction that sizes a kernel. |
| `MemoryData` | `hardware` (`org.almostrealism.hardware`) | A handle to possibly device-resident memory. `extends TraversableExpression<Double>, Delegated<MemoryData>, Destroyable, Node`. |

See [Producer/Evaluable Pattern](../docs/internals/producer-evaluable-pattern.md),
[Shape and Traversal](../docs/internals/shape-and-traversal.md),
[Expression Evaluation](../docs/internals/expression-evaluation.md) and
[Process Optimization Pipeline](../docs/internals/process-optimization-pipeline.md) for the full
contracts.

## How the modules build on one another

The base modules form a dependency graph rooted at `meta`, each adding one capability. It is a
branch, not a single chain: `relation` and `io` depend on `meta` independently, `code` draws on
both, and `hardware-js` branches off `code` alongside `collect`.

```
        ┌─ relation ─┐
meta ──┤             ├─ code ─┬─ collect ── hardware
        └─ io ───────┘        └─ hardware-js
```

- **`meta`** is the absolute floor — zero external dependencies. It names the contracts the
  rest of the framework leans on: identity and naming (`Named`, `Nameable`), lifecycle
  (`Destroyable`, `Lifecycle`, `Setup`), signature-based identity (`Signature`), and indexed
  access (`Multiple`, `Plural`).
- **`io`** adds diagnostics on top of `meta`: hierarchical `Console` logging, `TimingMetric`
  and `DistributionMetric`, the alert system, and lazy initialization (`SuppliedValue`). Every
  layer uses it for output.
- **`relation`** adds the *computational model* on top of `meta` (not `io`): the `Producer`/`Evaluable`
  two-phase split, `Countable` for kernel sizing, the `Process` tree and its optimization
  (`ProcessContext`, `ParallelismTargetOptimization`, `IsolatedProcess`), and composition
  (`Factor`, `Composition`). It also defines the **graph vocabulary** — `Node`, `Group`,
  `NodeGroup`, `NodeList`, `Graph`, `Tree`, `WeightedGraph`, `IndexedGraph`, `Parent`, `Path`
  (see family 3 of the summary map). Its `frames` package is a forward-looking extension toward
  semantic relations.
- **`code`** adds *code generation* on top of both `relation` and `io`: `Expression` trees,
  `Scope`, `Computation`, `TraversalPolicy` and the collection-expression algebra
  (`CollectionExpression`, `TraversableExpression`, `Shape`), the `kernel` package (index
  sequences, kernel-series detection) and `ExpressionCache` (thread-local deduplication that
  prevents expression-tree explosion).
- **`collect`** is a dependency waypoint between `code` and `hardware`. The shape and
  collection-expression types that *conceptually* belong here currently live in `code`'s
  `io.almostrealism.collect` package because they are circularly coupled with `Expression`; the
  concrete `PackedCollection` and `CollectionProducer` live in `compute/algebra`, where they can
  see hardware memory. See [base/collect/README.md](collect/README.md).
- **`hardware`** adds *execution* on top of `collect`: `MemoryData` and the memory model
  (zero-copy delegation, thread-local arenas, GC-integrated native memory), kernel compilation
  and caching, and the CPU/OpenCL/Metal/CUDA backends. A native handle is always carried by a
  typed wrapper (`MTLBuffer`, `CUStream`, …), never a bare `long` — see
  [base/CLAUDE.md](CLAUDE.md).
- **`hardware-js`** branches off `code` (not `collect`): it is the JavaScript code-generation
  backend, an alternative emitter for the same `Scope`/`Expression` trees, so it needs the code
  layer but none of the native memory model that `hardware` adds.

## The one rule this layer exists to enforce

Because `Producer` describes rather than executes, the cardinal rule of the whole project lives
here: **never call `.evaluate()`/`.get()` inside computation code, and never do element-wise math
in Java**. Build a `Producer` graph instead and let the backend compile it to one kernel. The
`Process` optimization step (`optimize()` before `get()`) is what keeps that graph from
exploding into an un-compilable expression tree; skipping it is the most common cause of
compile timeouts. See the "FUNDAMENTAL RULE" section of the root [CLAUDE.md](../CLAUDE.md) and
[relation/README.md](relation/README.md).

## Where the data types live

The base layer defines `MemoryData` — the handle to accelerated memory — but the concrete
tensor (`PackedCollection`) and its fluent API (`CollectionProducer`) are introduced one layer
up, in `compute/algebra`, so they can bind hardware memory. The crucial cross-layer fact is
that **both are `Node`s**: `MemoryData extends Node` and `Producer extends Node`, so every
`PackedCollection`-backed value and every producer is a graph node. That is the link the
`compute` and `domain` concept maps build on. See [compute/concept-map.md](../compute/concept-map.md).
