# Concept Map

This document is the platform's concept map: it situates the core concept of every module in
the six-layer stack (`base` through `extern`/`studio`) and records where mathematically-related
concepts are — and are not — connected in the type system. A reader (or an agent about to
introduce a new type) uses it to find the concept that already expresses an idea, in the layer
it lives in, before writing a parallel one. The standalone `flowtree` and `tools` module groups
sit above this stack (see `llms.txt`) and are outside its scope; this map covers the layered
platform they build on.

It has two parts:

- **Part I — Layered concept overview** situates every module's core concepts in the layered
  architecture, starting from the foundational type system (`Producer`/`Evaluable`,
  `PackedCollection`, `CollectionProducer`) that all the rest is built on. Each layer also has
  its own, fuller concept map next to its modules (`base/concept-map.md`, `compute/concept-map.md`,
  …), linked below. Part I is for orientation: *where does a concept live, and what does it build
  on?*
- **Part II — Type-alignment families and known gaps** is the detailed, source-verified record
  the concept-alignment round maintains: for a set of mathematically-related families it lists
  the declared supertypes, the modules, and the places where the type system does **not** yet
  express a relationship it could. Part II is for alignment: *before I add a type, which existing
  interface already expresses this concept?*

The families in Part II are the seed set. Later concept-alignment work extends them as it
surveys further families and as gaps are closed.

---

# Part I — Layered concept overview

The framework is a stack of six module layers, each building on the one below:

```
base → compute → domain → engine → extern / studio
```

The governing idea runs through all of them: **Java describes a computation graph; native code
executes it.** Every concept below is either part of that description machinery, a typed value
that flows through it, or an application assembled from it.

## The foundational spine

These are the concepts the entire framework speaks, introduced in `base` and made concrete in
`compute/algebra`. They are the ones a reader most needs and the ones this map previously
omitted.

| Concept | Where | What it is | Deeper reference |
|---|---|---|---|
| `Producer<T>` | `base/relation` | A *described* computation — the recipe, not the result. `extends Supplier<Evaluable<? extends T>>, Computable, Node, Destroyable`. | [Producer/Evaluable Pattern](producer-evaluable-pattern.md) |
| `Evaluable<T>` | `base/relation` | The compiled, executable form; `producer.get()` compiles, `evaluate(args)` runs. | [Producer/Evaluable Pattern](producer-evaluable-pattern.md) |
| `Process<P, T>` | `base/relation` | A node in the optimizable tree a producer graph becomes; `optimize()` before `get()` decides isolation and parallelism. | [Process Optimization Pipeline](process-optimization-pipeline.md) |
| `Expression<T>`, `Scope<T>`, `Computation<T>` | `base/code` | The typed expression tree, the code-generation container, and a computation that emits a `Scope`. | [Expression Evaluation](expression-evaluation.md) |
| `TraversalPolicy` (`Shape`, `Traversable`) | `base/code` | The *shape*: N-dimensional structure imposed on flat memory, plus the fixed/variable count that sizes a kernel. | [Shape and Traversal](shape-and-traversal.md) |
| `MemoryData` | `base/hardware` | A handle to possibly device-resident memory. `extends TraversableExpression<Double>, Delegated<MemoryData>, Destroyable, Node`. | [Native Runtime Lifecycle](native-runtime-lifecycle.md) |
| `PackedCollection` | `compute/algebra` | The concrete N-dimensional tensor; `extends MemoryDataAdapter` (so it **is** a `MemoryData`, and a `Node`). | [PackedCollection Examples](packed-collection-examples.md) |
| `CollectionProducer` | `compute/algebra` | The fluent, lazy API over collections (`add`, `multiply`, `reshape`, `sum`, `delta`, …); it is a `Producer`, so also a `Node`. | [CollectionProducer Operations](collection-producer-operations.md) |
| `CollectionFeatures` (and the `*Features` mixins) | `compute/algebra` | The factory mixin that hands out producer-construction methods without inheritance. | [Features Pattern](features-pattern.md) |

**The cross-link that ties the stack together:** both `MemoryData` and `Producer` `extend Node`
(the graph marker from `base/relation`). Therefore every `PackedCollection`-backed value —
`Vector`, `RGB`, `Ray`, `TransformMatrix` — and every producer is a **graph node**. This is why
the graph vocabulary (Part II, family 3) reaches the numeric types: because `Vector` is a `Node`,
a `Mesh` can bind `Graph<Vector>`. (`Curve` is a graph node as well, but by its own declaration —
`Curve<T> extends Gradient<T>, Node` — not inherited from the collections it evaluates over; its
supertypes `Differentiable` and `Gradient` do not extend `Node`.)

## The layers and their per-layer maps

Each layer's own concept map covers its modules in full. This table is the index.

| Layer | Builds on | Core concepts it contributes | Per-layer map |
|---|---|---|---|
| **base** | — | `Producer`/`Evaluable`, `Process`, `Expression`/`Scope`, `TraversalPolicy`, `MemoryData`, the `Node`/`Graph` vocabulary, hardware backends. | [base/concept-map.md](../../base/concept-map.md) |
| **compute** | base | `PackedCollection`, `CollectionProducer`, `Vector`/`Pair`; `Ray`/`TransformMatrix`/`Camera` and the spatial-function & intersection hierarchies; distributions; `Temporal`/FFT/`SimulationRunner`. | [compute/concept-map.md](../../compute/concept-map.md) |
| **domain** | base, compute | `Cell`/`Receptor`/`Transmitter`, `Block`/`Model` and the PDSL core; `RGB`/`Light`/`Shader`; `Scene`/`Mesh`/surfaces; genes/genomes; photons/rigid bodies; elements/atoms; neurons/connectomes. | [domain/concept-map.md](../../domain/concept-map.md) |
| **engine** | domain, compute, base | The loop owners — `ModelOptimizer`, `DiffusionSampler`, `AutoregressiveModel`; `RayTracedScene`/`LightingEngine`; `StateDictionary`/`AttentionFeatures`/`Tokenizer`; audio `CellFeatures`/`CellList`; `TestSuiteBase`. | [engine/concept-map.md](../../engine/concept-map.md) |
| **extern** | engine, domain | Thin adapters to external runtimes — `SentencePieceTokenizer` (DJL), `Onnx*` models, the Groovy `Ops` DSL — converting at the AR-type boundary. | [extern/concept-map.md](../../extern/concept-map.md) |
| **studio** | engine, compute, domain | Composition and arrangement — `PatternSystemManager`, `AudioScene`/`MixdownManager`, `TemporalSpatialContext`, `ConnectomeNetwork`. | [studio/concept-map.md](../../studio/concept-map.md) |

For the module-by-module reference behind each row, see the layer READMEs
([base](../../base/README.md), [compute](../../compute/README.md), [domain](../../domain/README.md),
[engine](../../engine/README.md), [extern](../../extern/README.md), [studio](../../studio/README.md)).

---

# Part II — Type-alignment families and known gaps

The remainder of this document is the source-verified alignment record. A relationship here is
only what the source declares (`extends`/`implements`). A gap is only listed when closing it would
not require a new module dependency *and* every member of the proposed supertype would be correct
for the subtype; where a link would need a new dependency or cannot honour the contract, that is
said explicitly. A gap may instead be a relationship the source already declares whose contract is
not yet honoured at runtime (for example an implemented interface method that throws); such a gap
names the unmet contract rather than a missing supertype. When something is unclear it is written
as unclear rather than guessed.

## 1. Spatial functions

Functions of a position in space. `Gradient` declares only a normal (`getNormalAt`), and
`Curve` adds a value (`getValueAt`) of an arbitrary type `T`; the specific relationship "the
normal is the normalized gradient of the value" is the contract of scalar implicit surfaces
(`ImplicitSurface` and below), not of every type in this family. The hierarchy starts at a marker (`Differentiable`) and narrows through `Gradient`
and `Curve`, then **branches**: `ImplicitSurface`, `ShadableCurve` and `Volume` are separate
extensions of `Curve`, and `SignedDistanceFunction` and `HeightField` specialize
`ImplicitSurface` below it.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Differentiable<T>` | interface (no supertype) | `compute/algebra` (`org.almostrealism.algebra`) | none — marker |
| `Gradient<T>` | `extends Differentiable<T>` | `compute/algebra` (`org.almostrealism.algebra`) | `getNormalAt(Producer) : Producer<PackedCollection>` (fixed type, independent of `T`) |
| `Curve<T>` | `extends Gradient<T>, Node` | `compute/geometry` (`org.almostrealism.geometry`) | `getValueAt(Producer)` |
| `ImplicitSurface` | `extends Curve<PackedCollection>, VectorFeatures` | `compute/geometry` (`org.almostrealism.geometry`) | `lipschitzBound()`, covariant `getValueAt`/`getNormalAt` returning `CollectionProducer`, default `partialDerivative` |
| `SignedDistanceFunction` | `extends ImplicitSurface` (`@FunctionalInterface`) | `compute/geometry` (`org.almostrealism.geometry`) | `getValueAt(Producer)`; `lipschitzBound()` fixed at `1` |
| `HeightField` | `class implements ImplicitSurface` | `compute/geometry` (`org.almostrealism.geometry`) | bilinear height sampling, analytic normal and Lipschitz bound |
| `ShadableCurve` | `extends Curve<PackedCollection>, Shadable` | `domain/color` (`org.almostrealism.color`) | none of its own — colour value plus shading |
| `Volume<T>` | `extends Curve<T>` | `domain/physics` (`org.almostrealism.physics`) | `inside`, `intersect(Vector, Vector)`, `getSurfaceCoords`, `getSpatialCoords` |

Notes on the declarations:

- `Differentiable` carries no operations; its javadoc names the concept (a function with
  continuous derivatives) and points at `Gradient`. The operations that actually realize the
  concept — `getNormalAt`, `getValueAt` — are declared one and two levels down, on `Gradient`
  and `Curve`.
- `ImplicitSurface` narrows `getValueAt` (declared on `Curve` as `Producer<T>`) and `getNormalAt`
  (declared on `Gradient` as `Producer<PackedCollection>`) covariantly to `CollectionProducer` and adds `lipschitzBound()`, a bound on the rate of change of the value
  (not itself a distance): dividing the value by the bound gives a safe ray marching step. Its
  javadoc states the realignment that motivated this map: an implicit
  surface *is* a `Curve` of scalar values whose normal is the normalized gradient.
- `Curve extends Node` (see family 3), so `Curve` and every type that extends it are also graph
  nodes. `Differentiable` and `Gradient` do not extend `Node`.

### Gaps

- **`Differentiable` is a marker with no members** — a concept documented by an interface that
  carries no operations. A type written against `Differentiable<T>` can do nothing with it; any differentiation
  operation lives on `Gradient`/`Curve` instead. Open because no member that belongs on
  `Differentiable` itself (a value, or a derivative, independent of "normal") has been defined,
  and adding one would reshape every implementor; it needs design, not a mechanical change.
- **`Volume.intersect(Vector, Vector)` is host-valued and parallels the intersection family.**
  It returns a host `double`, and a source comment on the method asks for it to be replaced with
  an intersectable; the producer-valued counterpart is `Intersectable.intersectAt` (family 2).
  `Volume` is in `domain/physics`, which reaches `compute/geometry` transitively through
  `domain/color` (`physics → color → geometry`), so the link would **not** need a new
  dependency. It is open because the signature is host-valued (`double`, and
  `getSurfaceCoords`/`getSpatialCoords` likewise return `double[]`, with a source comment on
  `getSurfaceCoords` asking for a `Producer<Pair>` return): the concept must be moved onto producers before
  `Volume` can satisfy `Intersectable`.

---

## 2. Intersection

Testing a ray against a surface, and the set of points-with-directions that results. The
core intersection operation (`Intersectable.intersectAt`, returning a `ContinuousField`) is
producer-valued; the host-valued members are `DistanceEstimator` in full (`estimateDistance`
returns a `double`) and, on `ShadableSurface`, the `getShadeFront`/`getShadeBack` flags
(`boolean`) and `calculateBoundingSolid` (which returns a `BoundingSolid`).

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Intersectable<T>` | `extends Supplier<Operator<T>>` | `compute/geometry` (`org.almostrealism.geometry`) | `intersectAt(Producer) : ContinuousField`, `expect() : Operator<T>` |
| `DiscreteField` | `extends NodeList<Producer<PackedCollection>>` | `compute/geometry` (`org.almostrealism.geometry`) | none of its own — a list of `Ray` producers |
| `ContinuousField` | `extends DiscreteField, Gradient<PackedCollection>` | `compute/geometry` (`org.almostrealism.geometry`) | none of its own |
| `ShadableSurface` | `extends ShadableCurve, Intersectable<PackedCollection>` | `domain/color` (`org.almostrealism.color`) | `getShadeFront`, `getShadeBack`, `calculateBoundingSolid` |
| `DistanceEstimator` | interface (no supertype) | `domain/space` (`org.almostrealism.space`) | `estimateDistance(Ray) : double` |

Notes and the cross-links this family already carries:

- `ContinuousField` is the join between this family and family 1: it is both a `DiscreteField`
  (a set of sampled points) and a `Gradient` (whose only member, `getNormalAt`, makes the normal
  evaluable anywhere — `Gradient` does not declare a value operation; `getValueAt` lives one
  level down on `Curve`). `Intersectable` returns one.
- `ShadableSurface` is the join between family 1's shading branch (`ShadableCurve`) and this
  family (`Intersectable`): a shadable, intersectable 3D surface.
- `DiscreteField extends NodeList`, so this family is also rooted in the graph family (3).

### Gaps

- **`DistanceEstimator` is "Similar to `Intersectable`" (its own javadoc) but stands alone and
  is host-valued.** It returns a host `double estimateDistance(Ray r)` from a host `Ray`, while
  `Intersectable.intersectAt` is producer-valued. Conceptually its "distance that could lead to
  an intersection" is also what an `ImplicitSurface` yields (family 1), though neither of its
  methods provides it alone: `getValueAt` is not in general a distance and `lipschitzBound()` is
  a rate-of-change bound, and the conservative marching distance is the value divided by the
  bound. `DistanceEstimator` is in `domain/space`, which
  reaches `compute/geometry` transitively, so neither link would need a new dependency; the gap
  is the host-valued signature (`double` + host `Ray`), which must be moved onto producers
  before it can align with either `Intersectable` or `ImplicitSurface`.
- **The position-function family (1) and the ray-intersection family (2) are parallel surface
  descriptions that do not meet.** An `ImplicitSurface` can be ray-marched, but it does not
  implement `Intersectable`. The blocker is contract, not module: `Intersectable.intersectAt`
  specifies a `ContinuousField` of the `t` roots of `p = o + t d`, whereas marching an implicit
  surface yields a hit position, and `expect()` has no natural meaning for a signed-distance
  value. Bridging the two is a design question about the intersection contract — not an
  `instanceof`/adapter, which the project's "honor the interface" design rule forbids.

---

## 3. Graphs

The node/graph/tree vocabulary in `base/relation`, and the spatial types that build on it.
`Group` (enumerable) and `Graph` (neighbour relationships) are kept deliberately distinct;
their javadoc explains why one does not extend the other.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Node` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | none — marker |
| `Group<T>` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | `children() : Stream<T>`, default `all()` |
| `NodeGroup<T extends Node>` | `extends Group<T>` | `base/relation` (`io.almostrealism.relation`) | none of its own |
| `NodeList<T extends Node>` | `extends NodeGroup<T>, List<T>` | `base/relation` (`io.almostrealism.relation`) | default `children()` over `List.stream()` |
| `Graph<T extends Node>` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | `neighbors(T)`, `countNodes()` |
| `Tree<T extends Tree>` | `extends Graph<T>, NodeGroup<T>, Parent<T>, Node` | `base/relation` (`io.almostrealism.relation`) | tree traversal and depth defaults |
| `WeightedGraph<T extends Node>` | `extends Graph<T>, Group<T>` | `base/relation` (`io.almostrealism.relation`) | `edgeWeight(T, T)`, defaults `weightedNeighbors`/`weightedDegree`; enumerable through `Group` |
| `IndexedGraph<T extends Node>` | `extends WeightedGraph<T>` | `base/relation` (`io.almostrealism.relation`) | `nodeAt(int)`, `indexOf(T)`, defaults `neighborIndices`/`edgeWeight(int, int)` |
| `Parent<T>` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | `getChildren() : Collection<T>`, default `generate(List<T>)` |
| `Path` | `extends Plural<Node>` | `base/relation` (`io.almostrealism.relation`) | an ordered run of nodes |
| `TreeRef<T extends Tree>` | `class implements Tree<T>` | `base/relation` (`io.almostrealism.relation`) | a reference-backed `Tree` |
| `Mesh` | `extends SpacePartition<Triangle> implements Graph<Vector>, ConsoleFeatures` | `domain/space` (`org.almostrealism.space`) | a graph whose nodes are vertices |

How the other families root here:

- `Producer` and `Curve` both `extend Node`; `MemoryData` (the supertype of
  `PackedCollection`, hence of `Vector`) extends `Node`. So producers, spatial-function curves
  and every `PackedCollection`-backed value are graph nodes, which is why `Mesh` can bind
  `Graph<Vector>`.
- `DiscreteField extends NodeList` (family 2).

### Gaps

- **`Mesh` implements `Graph<Vector>` (vertices as nodes), but leaves `neighbors` unsupported.**
  `Mesh.countNodes()` returns the vertex count, but `Mesh.neighbors(Vector)` currently throws
  `UnsupportedOperationException`, so the vertex graph is a declared relationship whose adjacency
  operation fails at runtime; any code that calls `neighbors` on a `Mesh` throws. Supplying it
  needs vertex adjacency derived from the triangle index data, which is within `domain/space` and
  needs no new dependency.
- **Triangle-level adjacency is not expressed through the graph hierarchy.** A `Triangle` is
  already a `Node` transitively (`Triangle` → `AbstractSurface` → `ShadableSurface` →
  `ShadableCurve` → `Curve` → `Node`), and the triangles of a mesh are already held in a
  `NodeGroup`: `SpacePartition<Triangle>` extends `SurfaceGroup<Triangle>`, which implements
  `NodeGroup<T>`. The gap is only that triangle adjacency is not stated through
  `Graph`/`neighbors` (and the container is not a `NodeList`). Whether a triangle-level
  graph is wanted is **unclear** — it is recorded here as an observation, not a prescription; a
  round should confirm the need before aligning, because a `Graph` over triangles must honour
  `neighbors`/`countNodes` for every mesh.
- **The neuron connectome is a weighted graph that does not implement this hierarchy.** See
  family 5: `NeuronConnectome` is a neuron-level connectome — N nodes and E weighted edges stored
  in compressed sparse rows — while `CellTypeConnectome` aggregates the same wiring to a dense
  `[T, T]` type-by-type weight matrix. Both are columnar and device-resident, so aligning them
  onto `WeightedGraph`/`IndexedGraph` needs a node abstraction rather than a member rename.

---

## 4. Fields on grids

Values sampled on a structured grid, and the finite-difference calculus over them. This
family sits across two modules, and the word "field" is **overloaded** between it and family
2 (see the caution below).

| Type | Declared as | Module (package) | Role |
|---|---|---|---|
| `FieldCalculusFeatures` | `extends CollectionFeatures` (mixin) | `compute/algebra` (`org.almostrealism.calculus`) | finite-difference operators (`shift`, `gradient`, `divergence`, Laplacians, Poisson relaxation) on a grid collection, as producers |
| `GridBoundary` | `abstract class implements CollectionFeatures` | `compute/algebra` (`org.almostrealism.calculus`) | ghost-cell boundary conditions (`periodic`/`clamped`/`fixed`) used by the operators |
| `HeightField` | `class implements ImplicitSurface` | `compute/geometry` (`org.almostrealism.geometry`) | a 2D grid of heights presented as a 3D implicit surface |

### Gaps

- **`HeightField` is a 2D grid field, but does not use the grid field calculus.** It wraps an
  `[nx, nz]` height collection and, in its private `Sample`, computes slopes
  (`dh/dx`, `dh/dz`) by hand — a derivative-of-height operation related to, but not the same as,
  what `FieldCalculusFeatures.gradient` expresses. `HeightField` is in `compute/geometry`, which
  depends on `compute/algebra`, so a link would **not** need a new dependency. The gap is not a
  direct is-a, because the two differ in sampling contract: `HeightField` differentiates its
  bilinear interpolation of the four surrounding cell corners at arbitrary sub-cell positions,
  whereas `FieldCalculusFeatures.gradient` applies central-difference stencils to a whole-grid,
  node-aligned field. There is also **no interface** naming "a field
  sampled on a grid" that both a grid-calculus field and a `HeightField` implement — the two
  types share the meaning without a common interface naming it. Whether a shared abstraction is worth introducing is
  **unclear** given the different access patterns; it is recorded for a round to judge, not
  prescribed.

### Caution: "field" means two different things

`DiscreteField`/`ContinuousField` (family 2, `compute/geometry`) are a *list of `Ray`
producers* — sampled points each with a direction. The "field" of `FieldCalculusFeatures`
(this family, `compute/algebra`) is a *collection sampled on a structured grid*. They share
the word but not the concept: one is an enumerated set of point/direction samples, the other a
dense grid of values. This is coincidental naming, **not** a gap — a link between them would
join unrelated meanings.

---

## 5. Neural dynamics: populations and connectomes

The recurrent neural-dynamics types: a *population* holds device-resident state and advances it
one time step (the simulation-loop owner `SimulationRunner` drives the loop, as `ModelOptimizer`
drives training), and a *connectome* is the weighted wiring between units. The family spans
`compute/time` (the step contract `Temporal`), `domain/graph`, `domain/biology` and
`studio/bioscience`.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Temporal` | `extends TemporalFeatures` | `compute/time` (`org.almostrealism.time`) | `tick() : Supplier<Runnable>` — one state-advancing step, no argument |
| `RatePopulation` | `implements Temporal, LayerFeatures, Destroyable` | `domain/graph` (`org.almostrealism.graph`) | `tick()` (owns its drive `getInput()`), `couplingInput()`, `activate(..)` |
| `LeakyIntegrateAndFirePopulation` | `implements LayerFeatures, Destroyable` | `domain/graph` (`org.almostrealism.graph`) | `step(Producer) : OperationList` (takes the step's synaptic input), `delayedSpikes()`, `reset()` |
| `ExponentialSynapseNeurons` | `implements Block` | `domain/biology` (`org.almostrealism.biology.neuron`) | a pipeline-stage `Block` (input/output shape, forward/backward `Cell`) |
| `PoissonInput` | `implements Block` | `domain/biology` (`org.almostrealism.biology.neuron`) | a `Block` that injects Poisson kicks |
| `ConnectomeNetwork` | `implements Temporal, Setup, Destroyable, CollectionFeatures` | `studio/bioscience` (`org.almostrealism.bioscience.connectome`) | `tick()`, `setup()`, `meanRates(..)` — couples a connectome to a population |
| `NeuronConnectome` | `implements CollectionFeatures, Destroyable` | `studio/bioscience` (`org.almostrealism.bioscience.connectome`) | neuron-level: `getNeuronCount`, `getEdgeCount`, `getWeights` (`[N,N]`, CSR), `getOrdering`, `indexOf(long bodyId)` |
| `CellTypeConnectome` | `implements CollectionFeatures, Destroyable` | `studio/bioscience` (`org.almostrealism.bioscience.connectome`) | cell-type-level aggregate: `getTypeCount`, `getWeights` (`[T,T]`, dense), `getTypeSizes`, `getOutSynapses`, `indexOf(String type)` |

Notes on the declarations:

- The step contract that is already aligned is `Temporal`: a population stepped by
  `SimulationRunner` with a no-argument, state-advancing `tick()` *is a* `Temporal`.
  `RatePopulation` and `ConnectomeNetwork` declare it; each binds its external drive at
  construction — `RatePopulation` allocates and owns a `getInput()` collection, while
  `ConnectomeNetwork` retains a caller-supplied drive (or, with none, a silent one it allocates)
  — so their step needs no argument.
- `ExponentialSynapseNeurons` and `PoissonInput` are single-signal pipeline stages, so they are
  `Block`s (family of `domain/graph`'s `Cell`/`Block`), not populations.

### Gaps

- **`NeuronConnectome` and `CellTypeConnectome` are weighted directed graphs but do not
  implement the graph hierarchy (family 3).** `NeuronConnectome` holds N neuron nodes and E
  weighted edges in compressed sparse rows (`CompressedRowTraversalOrdering`) — an `[N,N]` signed
  weight matrix backed by only the E stored entries — with `indexOf(long bodyId)`; its CSR row
  offsets and columns are host-readable, so adjacency is available. `CellTypeConnectome` is the
  same wiring aggregated to a dense `[T,T]` type-by-type weight matrix with `indexOf(String type)`.
  The blocker is **contract, not module**: `Graph<T extends Node>`/`WeightedGraph`/
  `IndexedGraph` (`base/relation`, reachable transitively from `studio/bioscience`, so no new
  dependency) are host-object based — `Collection<T> neighbors(T)`, `T nodeAt(int)`,
  `edgeWeight(T,T)` — and the connectomes are columnar and device-resident with no per-neuron
  node object and no `neighbors`/`edgeWeight` members to align. A second, independent mismatch is
  the edge-weight convention: `WeightedGraph` documents its weights as non-negative, with
  `edgeWeight` returning `0` to mean *no edge*, whereas both connectome matrices are **signed**
  (`getWeights()` carries the presynaptic sign — negative for an inhibitory neuron), so even once
  node objects exist, implementing `WeightedGraph` is not behaviour-preserving unless its
  edge-weight contract is broadened or the sign is represented separately. Honouring the contract
  means introducing a neuron-`Node` abstraction and materialising node objects (a design decision
  and a feature, "can be converted to a graph" rather than "is one"), so it is filed rather than
  forced here. This is the same host-object-vs-columnar tension as `Mesh.neighbors` in family 3.
- **`LeakyIntegrateAndFirePopulation` and `RatePopulation` are parallel populations with no
  common contract.** Both own device-resident state and build one step the `SimulationRunner` loop
  drives, but they expose it differently and only one is a `Temporal`. `RatePopulation` owns its
  drive and *is a* `Temporal`, so `SimulationRunner` drives it directly through the no-argument
  `Temporal.tick()`. `LeakyIntegrateAndFirePopulation` builds its step as an `OperationList`
  returned from `step(Producer)` — it takes the step's synaptic input as a
  `Producer<PackedCollection>` argument (its dual data flow emits `delayedSpikes()` and the caller
  forms the weighted input between emit and consume), does not implement `Temporal`, and so reaches
  `SimulationRunner` only through a caller-provided `Temporal` adapter that wraps `step(..)` (as
  `NeuralPopulationSimulationExamplesTest` does). So `LIF` cannot honour the no-argument `tick()`
  as it stands, and naming the shared "driven stepped system" concept would require a **new
  interface** — which this round does not introduce. Filed for a round to judge.

---

## 6. Audio processing

Transforming a source buffer of audio into a destination buffer. This family has two parallel
contracts for the same idea in `engine/audio` (`org.almostrealism.audio.filter`): a
producer-valued one that is the intended target, and a `@Deprecated` host-valued one that a
migration is moving away from. It is the same host-valued-vs-producer-valued tension as family 2
(`Volume`/`DistanceEstimator`), here within a single module.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `AudioProcessor` | `extends Lifecycle` | `engine/audio` (`org.almostrealism.audio.filter`) | `process(Producer destination, Producer source) : Supplier<Runnable>` (producer-valued, deferred); static `fromWave` |
| `StatelessFilter` | interface (no supertype) | `engine/audio` (`org.almostrealism.audio.filter`) | `filter(..)`; default `toAudioProcessor(..)` adapting a stateless filter to an `AudioProcessor` |
| `EnvelopeProcessor` | `@Deprecated` interface (no supertype) | `engine/audio` (`org.almostrealism.audio.filter`) | `process(PackedCollection input, PackedCollection output) : void` (host-valued, eager); ADSR setters `setDuration`/`setAttack`/`setDecay`/`setSustain`/`setRelease` |
| `FilterEnvelopeProcessor` | `implements EnvelopeProcessor, CellFeatures, EnvelopeFeatures` | `engine/audio` (`org.almostrealism.audio.filter`) | the ADSR setters plus the host-valued `process` |
| `MultiOrderFilterEnvelopeProcessor` | `implements EnvelopeProcessor, Destroyable, CellFeatures, EnvelopeFeatures` | `engine/audio` (`org.almostrealism.audio.filter`) | the ADSR setters plus the host-valued `process` |

Notes on the declarations:

- `AudioProcessor`'s javadoc names `EnvelopeProcessor` as the concept it supersedes, and
  `EnvelopeProcessor`'s javadoc and a source TODO say to use `AudioProcessor` instead. Both
  `FilterEnvelopeProcessor` and `MultiOrderFilterEnvelopeProcessor` carry a
  `// TODO  This should implement AudioProcessor`.

### Gaps

- **The ADSR envelope processors are `AudioProcessor`s but implement only the deprecated,
  host-valued `EnvelopeProcessor`.** A `FilterEnvelopeProcessor`/`MultiOrderFilterEnvelopeProcessor`
  *is an* `AudioProcessor` (it transforms a source buffer into a destination buffer), and
  everything lives in `engine/audio` so no new dependency is needed. The blocker is **contract,
  not module**: `EnvelopeProcessor.process(PackedCollection, PackedCollection)` is host-valued and
  eager (it copies concrete buffers into internal `input`/`output` fields and calls `.run()`),
  whereas `AudioProcessor.process(Producer, Producer)` is producer-valued and deferred (returning a
  `Supplier<Runnable>` over arbitrary source/destination producers). The processors' internal
  compiled pipelines are hardwired to their own `input`/`output` fields, so honouring
  `AudioProcessor` is a re-architecture onto producers, not a member rename — and doing it by
  evaluating in Java would violate the Producer rules. Existing tests call the host-valued
  `process`, so the migration cannot be behaviour-preserving for them. Filed for a person to
  approve rather than forced here; this is the same shape as the `Volume`/`DistanceEstimator` gaps
  in families 1 and 2.

---

## How to use this map

To find where a concept lives and what it builds on, start from Part I and follow the link to the
relevant layer's own concept map. To decide whether a *new* type should extend an existing one,
use Part II as follows.

Before introducing a type with a `value`/`normal`/`getValueAt`/`getNormalAt` pair, a
ray-`intersect`, a `neighbors`/node structure, or a state-advancing step the simulation loop
owner drives (a `tick`/`step`), find the family above and extend the existing interface rather
than a `*Features` mixin alone — a population whose step takes no argument *is a* `Temporal`
(family 5). A grid of sampled values is the one
case where that advice is reversed: family 4 records that **no interface names "a field
sampled on a grid"**, and the finite-difference calculus is delivered deliberately through the
`FieldCalculusFeatures` mixin — so reuse that mixin rather than reimplementing its operators or
inventing a parallel interface. If an alignment cannot be made because a member would be wrong
for the new type, or because it would need a new module dependency, record that reason as a new
gap here instead of leaving the concept orphaned.
