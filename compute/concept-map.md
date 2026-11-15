# compute — Concept Map

The mathematical-domains layer. It gives higher layers a typed vocabulary — linear algebra,
geometry, probability, time and frequency — built entirely on the base layer's `PackedCollection`
and `Producer`. Every type here is a *semantic wrapper* over accelerated memory: it adds meaning
(a 3-vector, a ray, a transform, a time series) without leaving the compiled-kernel model.

Companion to [compute/README.md](README.md) and the summary map in
[docs/internals/concept-map.md](../docs/internals/concept-map.md). The two geometry families in
the summary map (spatial functions and intersection) originate in this layer.

## How this layer builds on `base`

The base layer defines *how* to describe and run a computation; `compute` defines *what* is
being computed. The pivot is `compute/algebra`, which introduces the two types the base layer
deliberately left abstract:

- **`PackedCollection`** — the concrete N-dimensional tensor. `extends MemoryDataAdapter`
  (which `implements MemoryData`), so a `PackedCollection` *is* a `MemoryData` and therefore a
  `Node`. It interprets a flat, possibly device-resident block through a `TraversalPolicy`, and
  supports zero-copy views, delegation and bulk copies. See
  [PackedCollection Examples](../docs/internals/packed-collection-examples.md).
- **`CollectionProducer`** — the fluent, lazy API for building computation graphs over
  collections (`add`, `multiply`, `reshape`, `sum`, `delta`, …). It is a `Producer`, so it is
  also a `Node`. `CollectionFeatures` is the mixin that hands out the 200+ factory methods
  (`c()`, `v()`, `p()`, `shape()`, `matmul()`, `sigmoid()`, …). See
  [CollectionProducer Operations](../docs/internals/collection-producer-operations.md) and the
  [Features Pattern](../docs/internals/features-pattern.md).

Because `PackedCollection` and `Vector`/`RGB`/`Ray` are all `Node`s, the numeric data types of
the whole framework share a root with the graph vocabulary from `base/relation` — this is why a
mesh can be a `Graph<Vector>` and why a `Curve` can `extend Node`.

## Concepts each module contributes

### algebra — the numeric core
The foundational collection infrastructure plus the small algebraic wrappers.

| Concept | Role |
|---|---|
| `PackedCollection`, `CollectionProducer`, `CollectionFeatures` | The tensor, its lazy API, and the factory mixin (see above). |
| `Vector`, `Pair` | 3-vector and 2-tuple wrappers over `PackedCollection` (`Vector extends PackedCollection implements VectorFeatures`). |
| `FieldCalculusFeatures`, `GridBoundary` | Finite-difference stencil operators (`gradient`, `divergence`, Laplacians, Poisson relaxation) over a grid collection, with ghost-cell boundaries. The "fields on grids" family of the summary map. See [Grid Field Calculus](../docs/internals/grid-field-calculus.md). |
| automatic differentiation | `CollectionProducer.delta(target, ReduceAxis, weight)` — the only public derivative entry point. See [Automatic Differentiation](../docs/internals/automatic-differentiation.md). |

### geometry — space and rays
3D primitives and the ray-intersection vocabulary, built on `Vector`/`PackedCollection`.

| Concept | Role |
|---|---|
| `Ray` | Origin + direction, packed as six values; `extends PackedCollection`. |
| `TransformMatrix`, `BasicGeometry` | 4×4 homogeneous transform and a position/rotation/scale container that composes one. |
| `Camera`, `PinholeCamera`, `OrthographicCamera` | Projection models that generate viewing rays for pixel coordinates. |
| `Differentiable` → `Gradient` → `Curve` → `ImplicitSurface` | The spatial-function hierarchy (family 1 of the summary map) — a function of position with a normal and a value. `Curve extends Node`. |
| `Intersectable`, `DiscreteField`, `ContinuousField` | The ray-intersection hierarchy (family 2). `Intersectable.intersectAt` returns a `ContinuousField`; `DiscreteField extends NodeList`. |

### stats — probability
Distributions and sampling expressed as producers, used by ML (token sampling) and rendering
(Monte Carlo path tracing).

| Concept | Role |
|---|---|
| `DistributionFeatures` | Discrete inverse-transform sampling and softmax, as a `CollectionFeatures` mixin. |
| `SphericalProbabilityDistribution`, `BRDF` | Directional sampling on the unit sphere and the physically-based reflectance interface that holds one. |

### time — temporal and frequency processing
Tick-based execution and signal analysis over time-indexed collections.

| Concept | Role |
|---|---|
| `Temporal`, `TemporalRunner` | The `tick()`-based execution interface and the setup/tick orchestrator. |
| `SimulationRunner` | Owner of the loop for globally-coupled recurrent dynamical systems — never hand-write that loop. See [Simulation Loop Examples](../docs/internals/simulation-loop-examples.md). |
| `TimeSeries`, `AcceleratedTimeSeries` | Host and device-accelerated time-indexed storage with interpolation. |
| `FourierTransform`, `MultiOrderFilter`, `SpectralFeatures`, `Frequency` | FFT/IFFT, FIR filtering, spectral operators, and Hz/BPM conversion — each compiled to a kernel. |

## What stays out

`compute` types define math, not applications: geometry defines shapes and transforms but not
*how to render* them (that is `engine/render`); time defines signal transforms but not
*audio-specific* processing (that is `engine/audio`); stats defines distributions but not
*training* (that is `engine/optimize`). Keeping them application-agnostic is what lets the same
`Vector` or FFT serve rendering, physics and ML alike. See [compute/CLAUDE.md](CLAUDE.md).
