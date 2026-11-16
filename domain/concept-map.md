# domain — Concept Map

The domain-models layer. It combines the base (execution model) and compute (math) layers into
concrete subjects: neural-network graphs, colour and lighting, 3D scenes, genetics, physics,
chemistry and biology. Most domain types are computation graphs — they compile to native
kernels like everything else — but now carry the vocabulary of a particular field; others are
host-side data and structure (an `Element` of the periodic table, a `Scene`'s surface
hierarchy) that describe or assemble those graphs rather than compiling to a kernel themselves.

Companion to [domain/README.md](README.md) and the summary map in
[docs/internals/concept-map.md](../docs/internals/concept-map.md). The graph (family 3) and
shading/surface branches of the summary map live in this layer.

## How this layer builds on `base` and `compute`

Three lower-layer concepts do most of the work here:

- **`PackedCollection` / `CollectionProducer`** (compute/algebra) back every weight, colour,
  vertex buffer and neuron state.
- **`Vector`** (compute/algebra) and **`Ray` / `TransformMatrix`** (compute/geometry) give colour and space their
  positions, directions and transforms.
- **The `Node`/`Graph` vocabulary** (base/relation) is what the central pattern of this layer —
  the computation graph — is an instance of. Because `Producer` and `MemoryData` are both
  `Node`s, a domain type that is "made of" producers and collections is naturally a graph.

The keystone is `domain/graph`, which introduces the **Cell / Receptor / Transmitter** pattern
and the `Block`/`Model` abstractions the whole engine layer trains and runs. It also hosts the
PDSL language core and the automatic-differentiation machinery.

## Concepts each module contributes

### graph — computation graphs and trainable layers
The foundational domain module; most others are independent of it, but the engine layer is
built on it.

| Concept | Role |
|---|---|
| `Cell<T>` | A node in a directed computation graph; `extends Transmitter<T>, Receptor<T>, Cellular` — it receives, computes, and transmits. Any class named `…Cell` must implement this. |
| `Receptor<T>`, `Transmitter<T>` | The input and output endpoints a `Cell` composes. |
| `CellularPropagation<T>` | Bidirectional propagation — exposes forward and backward `Cell`s for autodiff. |
| `Block` | A composable network unit; `extends Component, CellularPropagation<PackedCollection>, Setup, LayerFeatures`. Any class named `…Block` must implement this. |
| `Layer`, `CellularLayer` | Trainable components with weights; bridged to `Block` by `DefaultCellularLayer`. |
| `Model` | The top-level container that composes layers; `implements Setup, Destroyable, Tracking, CodeFeatures`. Trained by `ModelOptimizer` (engine/optimize). |
| PDSL core | Lexer/parser/interpreter and domain-agnostic built-ins for the model-definition language. See [PDSL Language Reference](../docs/internals/pdsl-language.md). |
| `delta` / `DefaultGradientPropagation` | The backward pass that turns a forward graph into per-weight gradients. See [Automatic Differentiation](../docs/internals/automatic-differentiation.md). |

### color — colour, light and shading
The rendering math (not the pipeline, which is `engine/render`).

| Concept | Role |
|---|---|
| `RGB`, `RGBA` | Colours as hardware-accelerated collections (`RGB extends PackedCollection`). |
| `Light`, `PointLight` | Light sources with position, intensity and attenuation. |
| `Shader`, `ShaderSet`, `ShaderContext` | Surface-colour evaluation; composable and context-driven. |
| `ShadableCurve`, `ShadableSurface` | The shading branch of the summary map's families 1 and 2 — a `Curve`/`Intersectable` that also shades. |

### space — 3D scenes and meshes
Scene structure and spatial acceleration, built on `color` (surfaces are shadable) and
`compute/geometry`.

| Concept | Role |
|---|---|
| `AbstractSurface` and primitives (`Sphere`, `Plane`, `Mesh`, `Triangle`, …) | Renderable surfaces that implement `ShadableSurface`. |
| `Scene<T extends ShadableSurface>` | A container of surfaces, lights and a camera. |
| `Mesh` | A vertex/triangle surface that `implements Graph<Vector>` — the graph/mesh link of family 3. |
| `SpacePartition`, CSG, `DistanceEstimator` | Acceleration structures, constructive solid geometry and signed-distance estimation. Bounds come from `compute/geometry`'s `BoundingSolid`. |

### heredity — evolutionary computation
Genes, chromosomes and genomes as producer-backed structures.

| Concept | Role |
|---|---|
| `Gene<T>`, `Chromosome<T>`, `Genome<T>` | The hierarchy of `Factor`-valued genetic material (indexed via `Plural`). |
| `ProjectedGene`/`ProjectedGenome`, `ChoiceGene` | Weighted and discrete gene representations; `ProjectedGenome` stores parameters as a `PackedCollection`. |
| `ChromosomeBreeder`, `GenomeBreeder` | Crossover and mutation operators used by `engine/optimize`. |

### physics — atomic and classical simulation
Quantum structure and rigid-body dynamics, integrating with `chemistry` and `space`.

| Concept | Role |
|---|---|
| `Atom`, `Shell`, `Orbital`, `Electron` | The atomic model with electron configuration and energy levels. |
| `PhotonField` | Photon propagation and absorption over a field, stepped over time. |
| `RigidBody` / `RigidBody.State` | Classical dynamics (position, velocity, force, torque) integrated by `space` primitives. |
| `Volume<T>` | A `Curve` of occupancy — the host-valued member of family 1 awaiting a producer-valued intersection. |

### chemistry — the periodic table
Elements and their mixtures, integrating with `physics` for atomic construction.

| Concept | Role |
|---|---|
| `Element` | All 118 elements, each able to `construct()` an `Atom`; organised by group/period/block. |
| `Atomic`, `Substance` | The atom-factory contract and the `Node`-rooted marker for chemical substances. |
| `Alloy` | A probabilistic element mixture, selecting by weight (integrating with `heredity`). |

### biology — neurons and connectomes
Spiking dynamics expressed as `Block`s so a simulation compiles like any other model; depends on
`chemistry` and `graph`.

| Concept | Role |
|---|---|
| `ExponentialSynapseNeurons` | Leaky integrate-and-fire neurons with exponential synapses, stepped by the exact solution of their equations; a `Block`. |
| `PoissonInput` | Reproducible counter-based Poisson drive; a `Block`. |

(Applications that also need the ML engine — running a connectome as a trained network — live in
`studio/bioscience`, not here.)

## How the domain modules relate

`graph` is foundational and mostly independent of the others. `color` + `space` form the
rendering subsystem (`space` depends on `color`). `physics`, `chemistry`, `heredity` and
`biology` form a loosely-coupled life-and-matter subsystem: `chemistry` constructs atoms through
`physics`, `heredity` supplies the probabilities an `Alloy` mixes with, and `biology` builds its
neurons as `graph` `Block`s. Training loops, rendering pipelines and transformer architectures
are deliberately *absent* — those belong to `engine`. See [domain/CLAUDE.md](CLAUDE.md).
