# Module Dependency Architecture Reference

**Authoritative reference for the Almost Realism Common module dependency graph.**

Every claim about module dependencies in this document was produced by reading
`pom.xml` files bidirectionally — both the dependencies a module declares and the
consumers that declare it as a dependency. If something in this document conflicts
with a `pom.xml` file, the `pom.xml` is correct, and this document must be updated.

---

## Table of Contents

1. [Full Module List](#full-module-list)
2. [Layer Architecture Overview](#layer-architecture-overview)
3. [Complete Dependency Graph](#complete-dependency-graph)
4. [Consumer Graph (Reverse Lookup)](#consumer-graph-reverse-lookup)
5. [Architectural Invariant: No Upward Dependencies](#architectural-invariant-no-upward-dependencies)
6. [Understanding the Standalone Modules](#understanding-the-standalone-modules)
7. [Impact Analysis](#impact-analysis)
8. [How to Verify Dependencies](#how-to-verify-dependencies)
9. [Common Dependency Confusion Points](#common-dependency-confusion-points)
10. [Determining Layer Placement for New Code](#determining-layer-placement-for-new-code)
11. [How to Update This Document](#how-to-update-this-document)

---

## Full Module List

The table below lists every Maven module in the project, its artifact ID, its source
directory relative to `common/`, and its architectural layer. The artifact ID is the
canonical name used in `<dependency>` blocks across all `pom.xml` files.

| Directory | Artifact ID | Layer |
|---|---|---|
| `base/meta` | `ar-meta` | Layer 1 — Base |
| `base/io` | `ar-io` | Layer 1 — Base |
| `base/relation` | `ar-relation` | Layer 1 — Base |
| `base/code` | `ar-code` | Layer 1 — Base |
| `base/collect` | `ar-collect` | Layer 1 — Base |
| `base/hardware` | `ar-hardware` | Layer 1 — Base |
| `compute/algebra` | `ar-algebra` | Layer 2 — Compute |
| `compute/geometry` | `ar-geometry` | Layer 2 — Compute |
| `compute/stats` | `ar-stats` | Layer 2 — Compute |
| `compute/time` | `ar-time` | Layer 2 — Compute |
| `domain/graph` | `ar-graph` | Layer 3 — Domain |
| `domain/space` | `ar-space` | Layer 3 — Domain |
| `domain/physics` | `ar-physics` | Layer 3 — Domain |
| `domain/color` | `ar-color` | Layer 3 — Domain |
| `domain/chemistry` | `ar-chemistry` | Layer 3 — Domain |
| `domain/heredity` | `ar-heredity` | Layer 3 — Domain |
| `engine/optimize` | `ar-optimize` | Layer 4 — Engine |
| `engine/render` | `ar-render` | Layer 4 — Engine |
| `engine/ml` | `ar-ml` | Layer 4 — Engine |
| `engine/audio` | `ar-audio` | Layer 4 — Engine |
| `engine/utils` | `ar-utils` | Layer 4 — Engine |
| `engine/utils-http` | `ar-utils-http` | Layer 4 — Engine |
| `extern/ml-djl` | `ar-ml-djl` | Layer 5 — Extern |
| `extern/ml-onnx` | `ar-ml-onnx` | Layer 5 — Extern |
| `extern/ml-script` | `ar-ml-script` | Layer 5 — Extern |
| `studio/music` | `ar-music` | Layer 6 — Studio |
| `studio/spatial` | `ar-spatial` | Layer 6 — Studio |
| `studio/compose` | `ar-compose` | Layer 6 — Studio |
| `studio/experiments` | `ar-experiments` | Layer 6 — Studio |
| `flowtreeapi` | `ar-flowtreeapi` | Standalone |
| `graphpersist` | `ar-graphpersist` | Standalone |
| `flowtree-python` | `ar-flowtree-python` | Standalone |
| `flowtree` | `ar-flowtree` | Standalone |
| `tools` | `ar-tools` | Standalone |

---

## Layer Architecture Overview

The project is organized into six numbered layers plus a standalone tier. The layers
form a strict partial order: code in layer N may only depend on code in layers 1
through N-1. The standalone tier is above the engine layer but is not part of the
hierarchy — nothing in layers 1-6 depends on any standalone module.

```
┌─────────────────────────────────────────────────────────────────────┐
│  STANDALONE  flowtreeapi · graphpersist · flowtree-python ·         │
│              flowtree · tools                                        │
│              (depend on engine-layer modules; nothing in L1-6        │
│               depends on them)                                       │
└────────────────────────────┬────────────────────────────────────────┘
                             │ depends on ↓
┌────────────────────────────▼────────────────────────────────────────┐
│  LAYER 6 — STUDIO                                                    │
│  ar-music · ar-spatial · ar-compose · ar-experiments                 │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────────┐
│  LAYER 5 — EXTERN                                                    │
│  ar-ml-djl · ar-ml-onnx · ar-ml-script                              │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────────┐
│  LAYER 4 — ENGINE                                                    │
│  ar-optimize · ar-render · ar-ml · ar-audio                          │
│  ar-utils · ar-utils-http                                            │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────────┐
│  LAYER 3 — DOMAIN                                                    │
│  ar-graph · ar-space · ar-physics · ar-color                         │
│  ar-chemistry · ar-heredity                                          │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────────┐
│  LAYER 2 — COMPUTE                                                   │
│  ar-algebra · ar-geometry · ar-stats · ar-time                       │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────────┐
│  LAYER 1 — BASE                                                      │
│  ar-meta · ar-io · ar-relation · ar-code · ar-collect · ar-hardware  │
└─────────────────────────────────────────────────────────────────────┘
```

Each layer below is described in detail: what each module is, and which other
AR modules it directly depends on. "Direct dependency" means the artifact ID appears
in that module's `pom.xml` `<dependencies>` block. Transitive dependencies are
inherited automatically by Maven but are not listed here to keep the graph readable.

---

## Complete Dependency Graph

### Layer 1 — Base

Layer 1 is the foundation of the entire project. `ar-meta` has no AR dependencies
at all. Everything else in the stack depends transitively on this layer.

```
ar-meta         (no AR dependencies — root of the dependency tree)
ar-relation  →  ar-meta
ar-io        →  ar-meta
ar-code      →  ar-relation, ar-io
ar-collect   →  ar-code
ar-hardware  →  ar-collect
```

**ar-meta** (`base/meta`)
Provides annotations, lifecycle contracts, and semantic metadata primitives. It is
the only module with zero AR dependencies. Every other module in the project has
a transitive dependency on `ar-meta`.

**ar-relation** (`base/relation`)
Implements the Producer/Evaluable model and process optimization infrastructure.
Depends on `ar-meta` for lifecycle annotations.

**ar-io** (`base/io`)
Provides logging, metrics, alerting, and lifecycle management. Depends on `ar-meta`.
Runs alongside `ar-relation` at the same level — neither depends on the other.

**ar-code** (`base/code`)
Expression trees, scopes, and code generation. Depends on both `ar-relation` (for
the Producer/Evaluable model that expressions implement) and `ar-io` (for logging
during code generation and compilation).

**ar-collect** (`base/collect`)
`PackedCollection` and tensor storage. Depends on `ar-code` because collections
participate in the expression/code-generation pipeline. This is the module that
defines the primary data structure used throughout the compute and domain layers.

**ar-hardware** (`base/hardware`)
GPU/CPU acceleration, memory management, and kernel compilation. Depends on
`ar-collect` because hardware acceleration operates on `PackedCollection` instances.

---

### Layer 2 — Compute

Layer 2 builds mathematical domain infrastructure on top of the hardware/memory
layer from Layer 1.

```
ar-algebra   →  ar-hardware
ar-geometry  →  ar-algebra
ar-stats     →  ar-algebra
ar-time      →  ar-geometry
```

**ar-algebra** (`compute/algebra`)
Vector, matrix, and general numerical computing. Depends directly on `ar-hardware`
so that algebraic operations can dispatch to GPU or CPU kernels as appropriate.
This is the entry point for all mathematical computation in the project.

**ar-geometry** (`compute/geometry`)
Rays, transforms, and ray-tracing infrastructure. Depends on `ar-algebra` for
vector/matrix operations. Geometry needs algebra but algebra does not need geometry —
the dependency is strictly one-directional.

**ar-stats** (`compute/stats`)
Probability distributions and statistical sampling. Depends on `ar-algebra` for
numerical primitives. Stats is a sibling of geometry at the same layer — neither
depends on the other, though both are consumed by Layer 3.

**ar-time** (`compute/time`)
Temporal processing, FFT, and signal analysis. Depends on `ar-geometry` because
temporal signals are often embedded in geometric spaces (e.g., frequency-domain
transforms that operate on vector fields). This makes `ar-time` the highest module
in Layer 2 — it has both `ar-algebra` (transitively via geometry) and `ar-geometry`
as ancestors.

---

### Layer 3 — Domain

Layer 3 introduces domain-specific models that combine mathematical primitives from
Layers 1 and 2 into conceptually meaningful structures.

```
ar-heredity  →  ar-time
ar-color     →  ar-geometry, ar-stats
ar-physics   →  ar-time, ar-color
ar-graph     →  ar-geometry, ar-heredity
ar-chemistry →  ar-physics, ar-heredity
ar-space     →  ar-graph, ar-physics
```

**ar-heredity** (`domain/heredity`)
Genetic algorithms and evolutionary computation. Depends on `ar-time` — evolutionary
processes are fundamentally temporal, and the time module provides the signal
processing infrastructure that heredity builds on.

**ar-color** (`domain/color`)
RGB/RGBA, lighting, shaders, and textures. Depends on `ar-geometry` (color exists
in geometric spaces — normals, UV coordinates, surface interactions) and `ar-stats`
(probabilistic light sampling, Monte Carlo integration for rendering).

**ar-physics** (`domain/physics`)
Atomic structures, photon fields, and rigid body dynamics. Depends on `ar-time`
(physical processes evolve over time) and `ar-color` (photon behavior and light
transport are intrinsically tied to color representation).

**ar-graph** (`domain/graph`)
Neural network layers, computation graphs, and autodifferentiation. Depends on
`ar-geometry` (computation graphs have geometric structure — embeddings, attention
patterns) and `ar-heredity` (graph topology can be evolved using genetic algorithms).

**ar-chemistry** (`domain/chemistry`)
Periodic table, elements, and electron configurations. Depends on `ar-physics`
(chemistry is applied physics — electron orbitals and atomic interactions require
physical modeling) and `ar-heredity` (molecular structures can be evolved and
optimized).

**ar-space** (`domain/space`)
3D scenes, meshes, CSG (constructive solid geometry), and spatial acceleration
structures. Depends on `ar-graph` (scenes are graphs of objects with relationships)
and `ar-physics` (objects in space have physical properties and constraints).
`ar-space` is the highest module in Layer 3 because it integrates across all other
domain modules transitively.

---

### Layer 4 — Engine

Layer 4 provides runnable application frameworks and training infrastructure that
orchestrate domain models from Layer 3.

```
ar-optimize     →  ar-graph
ar-utils        →  ar-space, ar-chemistry, ar-optimize
ar-render       →  ar-space, ar-utils
ar-ml           →  ar-utils
ar-audio        →  ar-utils
ar-utils-http   →  ar-utils
```

**ar-optimize** (`engine/optimize`)
Adam optimizer, evolutionary algorithms, and training loop infrastructure. Depends
on `ar-graph` because optimization operates on computation graphs with
autodifferentiation. `ModelOptimizer` is the owner of all training loops in the
project — external code must never write raw `for (int epoch = 0; ...)` loops.

**ar-utils** (`engine/utils`)
Testing framework and cross-module utilities. Depends on `ar-space`, `ar-chemistry`,
and `ar-optimize` — it is the widest module in terms of inbound dependencies at the
engine layer. `ar-utils` is effectively the integration point for everything below
it: by depending on `ar-utils`, a module gains transitive access to all of Layers
1-4 except `ar-render` and `ar-utils-http`.

**ar-render** (`engine/render`)
Ray tracing engine, lighting, and image output. Depends on `ar-space` (the scene
representation) and `ar-utils` (utilities and testing infrastructure). Within the
named layers, `ar-render` has no consumers — it is a leaf node in the engine layer.

**ar-ml** (`engine/ml`)
Transformers, attention mechanisms, tokenizers, and diffusion models. Depends on
`ar-utils` (which transitively provides all of Layer 3 via its wide dependencies).
`ar-ml` is the primary dependency for anything that performs machine learning
inference or training.

**ar-audio** (`engine/audio`)
Audio synthesis, signal processing, and filters. Depends on `ar-utils`. Audio and
ML are sibling modules at Layer 4 — neither depends on the other, though they are
both consumed by the studio layer.

**ar-utils-http** (`engine/utils-http`)
HTTP client and REST integration. Depends on `ar-utils`. This is the thinnest engine
module — it adds HTTP transport capability on top of the general utility layer. It
is consumed by `ar-flowtree` in the standalone tier.

---

### Layer 5 — Extern

Layer 5 integrates specific external runtime libraries with the ML framework. Each
module in this layer wraps a third-party inference engine or scripting runtime.

```
ar-ml-djl    →  ar-ml
ar-ml-onnx   →  ar-ml
ar-ml-script →  ar-ml
```

**ar-ml-djl** (`extern/ml-djl`)
DJL (Deep Java Library) SentencePiece tokenization. Depends on `ar-ml` to bridge
DJL's tokenization into the AR ML pipeline.

**ar-ml-onnx** (`extern/ml-onnx`)
ONNX Runtime inference. Depends on `ar-ml` to bridge ONNX model execution into the
AR ML pipeline.

**ar-ml-script** (`extern/ml-script`)
Groovy scripting for model definition. Depends on `ar-ml` to enable scripted
specification of AR models.

All three extern modules are parallel — they have the same direct dependency
(`ar-ml`) and none depends on another. They are consumed by `ar-experiments` in
Layer 6.

---

### Layer 6 — Studio

Layer 6 provides multimedia composition and creative application frameworks.

```
ar-music        →  ar-audio
ar-compose      →  ar-audio, ar-ml
ar-spatial      →  ar-compose
ar-experiments  →  ar-compose, ar-ml-onnx, ar-ml-djl, ar-ml-script, ar-utils
```

**ar-music** (`studio/music`)
Pattern-based music composition. Depends on `ar-audio` for synthesis and signal
processing. `ar-music` is a leaf node — nothing in the named layers consumes it.

**ar-compose** (`studio/compose`)
Audio scene orchestration and arrangement. Depends on both `ar-audio` (for sound
generation) and `ar-ml` (for ML-driven composition features). This is the integration
point between the audio and ML capabilities.

**ar-spatial** (`studio/spatial`)
Spatial audio visualization. Depends on `ar-compose` for the audio scene model.
`ar-spatial` is a leaf node — nothing in the named layers consumes it.

**ar-experiments** (`studio/experiments`)
Experimental compositions and integration tests for the full studio stack. Depends
on `ar-compose`, all three extern modules (`ar-ml-onnx`, `ar-ml-djl`, `ar-ml-script`),
and `ar-utils`. It is the most widely dependent module in Layer 6, intentionally
serving as a proving ground for cross-module integration. `ar-experiments` is a leaf
node — nothing depends on it.

---

### Standalone Modules

Standalone modules depend on engine-layer modules but are not part of the Layer 1-6
hierarchy. Nothing in Layers 1-6 depends on any standalone module.

```
ar-flowtreeapi      →  ar-utils
ar-graphpersist     →  ar-utils
ar-flowtree-python  →  ar-flowtreeapi
ar-flowtree         →  ar-flowtreeapi, ar-flowtree-python, ar-graphpersist, ar-utils-http
ar-tools            →  ar-ml
```

**ar-flowtreeapi** (`flowtreeapi`)
FlowTree API abstractions. Depends on `ar-utils`. This module defines the interfaces
and contracts of the FlowTree workflow system without implementing the runtime.

**ar-graphpersist** (`graphpersist`)
Database persistence and NFS/SSH integration. Depends on `ar-utils`. Provides
storage infrastructure for computation graphs and model state, used by `ar-flowtree`.

**ar-flowtree-python** (`flowtree-python`)
Python bindings for FlowTree. Depends on `ar-flowtreeapi` for the contract it
exposes to Python clients.

**ar-flowtree** (`flowtree`)
Distributed workflow orchestration and job runner. Depends on `ar-flowtreeapi`
(the API contract), `ar-flowtree-python` (Python binding integration),
`ar-graphpersist` (persistence), and `ar-utils-http` (HTTP communication).
`ar-flowtree` is the top of the standalone hierarchy — it has the most dependencies
and no consumers within this project.

**ar-tools** (`tools`)
Development tooling and MCP servers. Depends on `ar-ml` for ML capabilities exposed
through tooling interfaces. Has no consumers within this project.

---

## Consumer Graph (Reverse Lookup)

The table below answers the question: **"If I change module X, which other modules
will be affected?"** It lists direct consumers only. Transitive consumers can be
determined by following the chain.

| Module | Direct consumers (depended on by) |
|---|---|
| `ar-meta` | `ar-relation`, `ar-io` |
| `ar-relation` | `ar-code` |
| `ar-io` | `ar-code` |
| `ar-code` | `ar-collect` |
| `ar-collect` | `ar-hardware` |
| `ar-hardware` | `ar-algebra` |
| `ar-algebra` | `ar-geometry`, `ar-stats` |
| `ar-geometry` | `ar-time`, `ar-color`, `ar-graph` |
| `ar-stats` | `ar-color` |
| `ar-time` | `ar-heredity`, `ar-physics` |
| `ar-heredity` | `ar-graph`, `ar-chemistry` |
| `ar-color` | `ar-physics` |
| `ar-physics` | `ar-chemistry`, `ar-space` |
| `ar-graph` | `ar-optimize`, `ar-space` |
| `ar-chemistry` | `ar-utils` |
| `ar-space` | `ar-render`, `ar-utils` |
| `ar-optimize` | `ar-utils` |
| `ar-utils` | `ar-render`, `ar-ml`, `ar-audio`, `ar-utils-http`, `ar-flowtreeapi`, `ar-graphpersist` |
| `ar-render` | *(no named-layer consumers — leaf node)* |
| `ar-ml` | `ar-ml-djl`, `ar-ml-onnx`, `ar-ml-script`, `ar-compose`, `ar-tools` |
| `ar-audio` | `ar-music`, `ar-compose` |
| `ar-utils-http` | `ar-flowtree` |
| `ar-ml-djl` | `ar-experiments` |
| `ar-ml-onnx` | `ar-experiments` |
| `ar-ml-script` | `ar-experiments` |
| `ar-music` | *(no consumers — leaf node)* |
| `ar-compose` | `ar-spatial`, `ar-experiments` |
| `ar-spatial` | *(no consumers — leaf node)* |
| `ar-experiments` | *(no consumers — leaf node)* |
| `ar-flowtreeapi` | `ar-flowtree-python`, `ar-flowtree` |
| `ar-graphpersist` | `ar-flowtree` |
| `ar-flowtree-python` | `ar-flowtree` |
| `ar-flowtree` | *(no consumers — top of standalone tree)* |
| `ar-tools` | *(no consumers — standalone leaf)* |

---

## Architectural Invariant: No Upward Dependencies

**The single most important rule of this architecture: lower layers never depend on
higher layers.**

This invariant is absolute. There are no exceptions. Any proposed change that would
violate it must be rejected and restructured.

The rules in precise form:

- Layer 1 (base) has no AR dependencies whatsoever
- Layer 2 (compute) depends only on Layer 1
- Layer 3 (domain) depends only on Layers 1 and 2
- Layer 4 (engine) depends only on Layers 1, 2, and 3
- Layer 5 (extern) depends only on Layers 1, 2, 3, and 4
- Layer 6 (studio) depends only on Layers 1, 2, 3, 4, and 5
- Standalone modules depend on engine-layer modules; nothing in Layers 1-6 depends on any standalone module

### Why this invariant exists

Upward dependencies create circular dependency cycles. Maven cannot resolve cycles
among modules. Even if tooling could work around cycles, they create semantic
confusion: a module can no longer be understood without understanding every module
above it in the cycle. The layered architecture ensures that each module can be
understood by reading only the modules it explicitly depends on.

### Concrete examples of violations (FORBIDDEN)

```
# VIOLATION: Layer 4 depending on Standalone
engine/utils/pom.xml listing ar-flowtreeapi
→ ar-utils is Layer 4, ar-flowtreeapi is Standalone; this is upward

# VIOLATION: Layer 2 depending on Layer 3
compute/algebra/pom.xml listing ar-graph
→ ar-algebra is Layer 2, ar-graph is Layer 3; this is upward

# VIOLATION: Layer 1 depending on Layer 2
base/collect/pom.xml listing ar-algebra
→ ar-collect is Layer 1, ar-algebra is Layer 2; this is upward

# VIOLATION: Standalone depending on Studio
flowtree/pom.xml listing ar-compose
→ Standalone must not depend on Studio (Layer 6) unless it is genuinely above it;
  the right approach is for flowtree to declare ar-ml or ar-audio directly if needed
```

### Detecting a potential violation

Before adding any dependency to any `pom.xml`, determine the layer of the module
being added to and the layer of the dependency being added. If the dependency's
layer is equal to or higher than the target module's layer, the addition is a
violation.

---

## Understanding the Standalone Modules

### Why standalone modules are not in named layers

The standalone modules share a common characteristic: they are independently
deployable and usable subsystems that have their own deployment lifecycle. They
depend on engine-layer modules to get their capabilities, but they are not meant
to be consumed by anything else in the project.

This is different from Layer 5 (extern), which is also above the engine layer.
The distinction is:

- **Layer 5 (extern)**: wraps external ML runtimes; intended to be consumed by Layer 6 (studio)
- **Standalone**: independently deployable systems; not intended to be consumed by any named layer

Concretely, `ar-ml-djl` is extern because `ar-experiments` (Layer 6) depends on it.
If `ar-flowtree` were consumed by any module in Layers 1-6, it would need to be
classified into a layer. It isn't, so it remains standalone.

### The flowtree internal hierarchy

The standalone modules are not flat — they have their own internal dependency order:

```
ar-utils          (Layer 4 — Engine)
ar-utils-http     (Layer 4 — Engine)
        │
        ├──► ar-flowtreeapi      ──► ar-flowtree-python ──┐
        │                                                  │
        └──► ar-graphpersist ────────────────────────────► ar-flowtree
```

`ar-flowtree` is the apex. It integrates everything: the API contract
(`ar-flowtreeapi`), Python bindings (`ar-flowtree-python`), persistence
(`ar-graphpersist`), and HTTP transport (`ar-utils-http`). Changes to any of
these propagate to `ar-flowtree`.

### tools: a standalone utility module

`ar-tools` provides MCP servers and development tooling. It depends on `ar-ml`
(engine layer) for ML capabilities and has no consumers. It is completely independent
of the flowtree hierarchy — the two standalone subsystems (`flowtree` family and
`tools`) do not depend on each other.

---

## Impact Analysis

When you change a module's API, behavior, or data formats, you affect all modules
that directly or transitively depend on it. The following analysis groups modules
by their approximate blast radius.

### Critical-impact modules (transitive consumers: entire project)

**ar-meta** is the root of the dependency tree. Every other module in the project
has a transitive dependency on `ar-meta`. A breaking change to `ar-meta` requires
updating every module. Treat changes here with extreme caution.

### High-impact base modules (transitive consumers: everything except siblings)

Changes to these modules propagate to all modules in their layer and above:

| Module | Transitive consumer count (approximate) |
|---|---|
| `ar-io` | all modules except `ar-relation` and `ar-meta` |
| `ar-relation` | all modules except `ar-io` and `ar-meta` |
| `ar-code` | all modules from `ar-collect` onward |
| `ar-collect` | all modules from `ar-hardware` onward |
| `ar-hardware` | all of Layer 2 and above |

### Compute-layer impact

`ar-algebra` is depended on by both `ar-geometry` and `ar-stats`, and therefore
transitively by all of Layer 3, Layer 4, Layer 5, Layer 6, and all standalone
modules. `ar-geometry` is consumed by `ar-time`, `ar-color`, and `ar-graph`,
cascading through the domain and engine layers.

### Engine convergence point: ar-utils

`ar-utils` is the widest module in the engine layer. It is consumed by:
- `ar-render` (engine layer)
- `ar-ml` (engine layer)
- `ar-audio` (engine layer)
- `ar-utils-http` (engine layer)
- `ar-flowtreeapi` (standalone)
- `ar-graphpersist` (standalone)

A change to `ar-utils`'s API cascades through almost the entire engine layer and
all standalone modules. It is second only to the base modules in breadth of impact.

### Low-impact (leaf) modules

These modules have no named-layer consumers. Changes to them affect only themselves
unless a consumer is added:

- `ar-render` — leaf in Layer 4; ray tracing output, consumed by nothing named
- `ar-music` — leaf in Layer 6; pattern-based music composition
- `ar-spatial` — leaf in Layer 6; spatial audio visualization
- `ar-experiments` — leaf in Layer 6; integration experiments
- `ar-flowtree` — top of standalone tree; no consumers in this project
- `ar-tools` — standalone leaf; no consumers in this project

### Practical impact matrix

The following is a condensed way to think about impact when planning changes:

```
Change target           Estimated scope
─────────────────────   ───────────────────────────────────────────
ar-meta                 Entire project — every module
ar-io, ar-relation      ~95% of modules
ar-code                 ~90% of modules (from collect onward)
ar-collect              ~85% of modules (from hardware onward)
ar-hardware             ~80% of modules (Layer 2 onward)
ar-algebra              ~75% of modules
ar-geometry             ~65% of modules
ar-utils                ~40% of modules (engine + standalone)
ar-ml                   ~25% of modules (extern + studio + tools)
ar-audio                ~15% of modules (music, compose, spatial, experiments)
ar-compose              ~10% of modules (spatial, experiments)
ar-render               ~0% named-layer consumers
ar-music, ar-spatial    ~0% consumers
ar-experiments          ~0% consumers
ar-flowtree             ~0% consumers
ar-tools                ~0% consumers
```

---

## How to Verify Dependencies

### Basic verification: what does module X depend on?

```bash
# Replace with the actual module directory
MODULE_DIR="engine/utils"

grep -o '<artifactId>ar-[^<]*</artifactId>' "$MODULE_DIR/pom.xml" | \
    sed 's/<[^>]*>//g'
```

Example output for `engine/utils/pom.xml`:
```
ar-space
ar-chemistry
ar-optimize
```

Conclusion (must be stated explicitly): `ar-utils` depends on `ar-space`,
`ar-chemistry`, and `ar-optimize`.

### Basic verification: what depends on module X?

```bash
# Replace ar-utils with the artifact ID you are investigating
ARTIFACT="ar-utils"

grep -rl "$ARTIFACT" $(find . -name pom.xml) | \
    grep -v "engine/utils/pom.xml"
```

This finds all `pom.xml` files that mention `ar-utils` other than its own file.
Because `<artifactId>` tags appear in the module's own declaration too, the exclusion
prevents false self-matches.

### Complete bidirectional verification for a single module

```bash
MODULE="ar-utils"
TARGET_POM="engine/utils/pom.xml"

echo "=== What $MODULE depends on ==="
grep -o '<artifactId>ar-[^<]*</artifactId>' "$TARGET_POM" | \
    sed 's/<[^>]*>//g'

echo ""
echo "=== What depends on $MODULE ==="
grep -rl "$MODULE" $(find . -name pom.xml) | \
    grep -v "^$TARGET_POM$" | \
    grep -v '/target/'
```

Always do **both** halves. "A depends on B" and "B depends on A" are two different
facts requiring two different lookups. A grep for the first tells you nothing about
the second.

### Auditing all module dependencies at once

The following shell loop produces a complete dependency audit. Run it before making
any CI change or before updating this document:

```bash
for f in $(find . -maxdepth 3 -name "pom.xml" | grep -v target | sort); do
    module=$(dirname "$f" | sed 's|^\./||')
    deps=$(grep -o '<artifactId>ar-[^<]*</artifactId>' "$f" | \
           sed 's|<[^>]*>||g' | \
           grep -v "$(basename "$module" | sed 's/-/_/g')" | \
           tr '\n' ',' | \
           sed 's/,$//')
    echo "$module: ${deps:-(none)}"
done
```

Read the output completely before drawing any conclusion. A single line is not
sufficient — the full picture requires reading every module.

### Verifying layer assignment

Given a module's dependencies, determine its layer:

1. For each AR dependency, determine its layer from the Full Module List table
2. The module's layer is one higher than the maximum dependency layer
3. If the module's dependencies span multiple layers, use the highest

```
Example: new module depends on ar-utils (Layer 4) and ar-graph (Layer 3)
  → highest dependency layer = 4
  → new module must be in Layer 5 or higher
  → if it wraps an external ML runtime → Layer 5
  → if it is an independently deployable system → Standalone
```

---

## Common Dependency Confusion Points

Documented mistakes and misstatements about this codebase. Each entry describes a
wrong claim, the correct claim, and the evidence that settles it.

### Confusion 1: "flowtree is consumed by flowtreeapi"

**Wrong.** This reverses the direction.

**Correct:** `ar-flowtreeapi` is consumed **by** `ar-flowtree`.

Evidence: `flowtree/pom.xml` lists `ar-flowtreeapi` as a dependency.
`flowtreeapi/pom.xml` does NOT list `ar-flowtree` as a dependency.

### Confusion 2: "engine/utils depends on flowtree"

**Wrong.** This would violate the architectural invariant (Layer 4 cannot depend on
Standalone).

**Correct:** `ar-flowtree` depends on `ar-utils` (via `ar-utils-http` and the
`ar-flowtreeapi` → `ar-utils` chain).

Evidence: `engine/utils/pom.xml` has no reference to `ar-flowtreeapi` or
`ar-flowtree`. `flowtreeapi/pom.xml` lists `ar-utils`. `flowtree/pom.xml` lists
`ar-utils-http`.

### Confusion 3: "ar-render is high impact"

**Partially wrong.** `ar-render` depends on many modules (`ar-space`, `ar-utils`),
so it has a large number of transitive *dependencies*. But in the opposite direction,
`ar-render` has **no named-layer consumers**. It is a leaf node. Changing `ar-render`
affects only `ar-render` itself.

The confusion arises from conflating "how many things does this depend on" with "how
many things depend on this." These are inverse questions.

### Confusion 4: "ar-utils is part of the test infrastructure"

**Incomplete.** `ar-utils` does contain the testing framework (`TestSuiteBase` and
related classes). But `ar-utils` is much wider — it also provides cross-module
utilities used in production code. It is the widest module at the engine layer and
everything above engine (extern, studio, standalone) that needs broad access depends
on it.

### Confusion 5: "studio modules only depend on audio"

**Wrong for ar-compose and ar-experiments.**

`ar-music` depends only on `ar-audio` — this part is correct.
`ar-compose` depends on **both** `ar-audio` and `ar-ml`.
`ar-experiments` depends on `ar-compose`, all three extern modules, and `ar-utils`.

### Confusion 6: "extern modules depend on each other"

**Wrong.** `ar-ml-djl`, `ar-ml-onnx`, and `ar-ml-script` are all parallel. Each
depends only on `ar-ml`. None depends on either of the other two.

### Confusion 7: "flowtree tests are run by the test-media CI job"

**Wrong.** `flowtree` tests run in the `build` CI job alongside the Maven build.
`test-media` runs audio, music, and studio tests. These are completely separate
CI jobs with completely separate test sets.

### Confusion 8: "the standalone modules are part of Layer 5"

**Wrong.** Layer 5 is the extern tier (`ar-ml-djl`, `ar-ml-onnx`, `ar-ml-script`).
The standalone modules (`ar-flowtreeapi`, `ar-graphpersist`, `ar-flowtree-python`,
`ar-flowtree`, `ar-tools`) are not part of any numbered layer. They are architecturally
above the engine layer but are independently deployable subsystems, not part of the
Layer 1-6 hierarchy.

The concrete difference: Layer 5 (extern) modules are consumed by Layer 6 (studio).
Standalone modules are not consumed by any named layer.

---

## Determining Layer Placement for New Code

When you add a new class or a new module, it must be placed in the correct layer.
Misplacement creates either an impossible dependency (if you need something from a
higher layer) or an unnecessary coupling (if you place it too high).

### Step 1: Identify all required AR dependencies

List every AR class or interface that the new code must use. Find which module each
belongs to. Find each module's layer from the Full Module List.

### Step 2: Find the highest required layer

The new code's layer is at least one higher than the highest-layer dependency it
requires.

### Step 3: Apply conceptual fit

Sometimes multiple layers are technically possible. Apply conceptual fit to choose:

- Does this code wrap an external runtime? → Layer 5 (Extern), alongside `ar-ml-djl` etc.
- Does this code provide multimedia composition? → Layer 6 (Studio)
- Is this code a standalone deployable system? → Standalone tier
- Is this code a domain model (graphs, physics, color)? → Layer 3 (Domain)
- Is this code mathematical infrastructure? → Layer 2 (Compute)
- Is this code a fundamental type or contract? → Layer 1 (Base)

### Step 4: Identify the correct module within the layer

Multiple modules may exist at the right layer. Choose the one whose conceptual
domain matches the new code:

- New ML runtime integration → `extern/ml-*`
- New audio synthesis code → `engine/audio` or `studio/music`
- New geometry primitive → `compute/geometry`
- New neural network layer → `domain/graph`
- New scene representation → `domain/space`
- New optimizer variant → `engine/optimize`

### Step 5: Verify no upward dependency is introduced

After placing the code, re-check: does the module you chose depend on anything
at or above your chosen layer? If so, you have a problem — either the placement
is wrong or the dependency graph needs redesign.

### Example: placing a MIDI serializer

New code converts `PatternElement` (a music concept) to MIDI byte sequences.

1. Required dependencies: `ar-music` (Layer 6)
2. Highest required layer: Layer 6
3. Conceptual fit: music/MIDI — belongs in `studio/music`
4. Method placement: add `toMidiEvents()` to `PatternElement` itself, not a new
   `PatternMidiExporter` class (see Code Quality rules in CLAUDE.md)
5. Verify: `ar-music` depends on `ar-audio` (Layer 4) — no upward dependency created

### Example: placing an HTTP-based ML model loader

New code fetches model weights from a remote URL and loads them into a `StateDictionary`.

1. Required dependencies: `ar-ml` (Layer 4), `ar-utils-http` (Layer 4)
2. Highest required layer: Layer 4
3. Conceptual fit: ML infrastructure — could fit in `engine/ml` or `engine/utils-http`
4. Does it belong on an existing type? If it extends `StateDictionary` — put it there
   in `engine/ml`. If it's a loading utility — put it in `engine/ml` as it is an
   ML concern, not an HTTP concern.
5. Verify: no new dependencies needed, both `ar-ml` and `ar-utils-http` are Layer 4

---

## How to Update This Document

This document must be updated whenever:

1. A new Maven module is added to the project
2. A dependency is added to or removed from any `pom.xml` file
3. A module's artifact ID changes
4. A module is moved to a different directory or layer

**Updating this document is not optional.** If the document is not updated, future
agents and developers will make wrong assumptions about the dependency graph, leading
to invalid CI configurations, layer violations, and incorrect impact assessments.

### Update procedure

Follow these steps exactly, in order.

#### Step 1: Re-audit all pom.xml files

Run the complete bidirectional audit:

```bash
# Dependency audit (what each module depends on)
for f in $(find . -maxdepth 3 -name "pom.xml" | grep -v target | sort); do
    module=$(dirname "$f" | sed 's|^\./||')
    deps=$(grep -o '<artifactId>ar-[^<]*</artifactId>' "$f" | \
           sed 's|<[^>]*>||g' | \
           tr '\n' ',' | sed 's/,$//')
    echo "$module: ${deps:-(none)}"
done
```

```bash
# Consumer audit (what depends on each module)
for module in ar-meta ar-relation ar-io ar-code ar-collect ar-hardware \
              ar-algebra ar-geometry ar-stats ar-time \
              ar-graph ar-space ar-physics ar-color ar-chemistry ar-heredity \
              ar-optimize ar-render ar-ml ar-audio ar-utils ar-utils-http \
              ar-ml-djl ar-ml-onnx ar-ml-script \
              ar-music ar-spatial ar-compose ar-experiments \
              ar-flowtreeapi ar-graphpersist ar-flowtree-python ar-flowtree ar-tools; do
    consumers=$(grep -rl "$module" $(find . -name pom.xml) 2>/dev/null | \
                grep -v '/target/' | tr '\n' ',' | sed 's/,$//')
    echo "$module consumed by: ${consumers:-(nothing)}"
done
```

#### Step 2: Identify all changes

Compare the audit output to this document. For each difference:
- Is a new module listed? → Add it to the Full Module List and all relevant sections
- Is a dependency missing? → Add it to the Complete Dependency Graph
- Is a dependency removed? → Remove it from the graph
- Has a module moved layers? → Update all references

#### Step 3: Update the Complete Dependency Graph

Update the relevant layer block under "Complete Dependency Graph." Use the `→` arrow
notation to show direct AR dependencies. List only direct dependencies — transitive
dependencies are implied.

#### Step 4: Update the Consumer Graph

Update the Consumer Graph table to reflect all changes. Every `→` arrow in the
dependency graph implies a consumer relationship in the opposite direction. Verify
that both tables are mutually consistent.

#### Step 5: Update the Impact Analysis

If a new module is added or a module's consumer set changes significantly, update
the Impact Analysis section. Leaf nodes (no consumers) should be listed in the
low-impact section. High-fanout modules (many consumers) should be in the high-impact
section.

#### Step 6: Verify the architectural invariant is preserved

For every new or changed dependency, explicitly verify that it does not violate the
"no upward dependency" rule. State the layers of both the dependent and the dependency.
If a violation is found, do NOT add the dependency — stop and report the problem.

#### Step 7: Store the update in memory

After completing the document update, call `mcp__ar-consultant__remember` to store
a memory entry:

```
namespace: decisions
tags: module-dependencies, architecture
content: "Updated module-dependency-architecture.md. Changed: [describe what changed].
          Verified: [which pom.xml files were read]. Invariant check: [result]."
```

#### Step 8: Update llms.txt

If a new module was added, update `llms.txt` to include the new module's documentation
path so that future agents discover it through the documentation index.

### Verification checklist before committing the update

- [ ] Every module in the Full Module List has a corresponding entry in the Dependency Graph
- [ ] Every `→` edge in the Dependency Graph appears as a consumer entry in the Consumer Graph
- [ ] Every consumer entry in the Consumer Graph corresponds to a `→` edge in the Dependency Graph
- [ ] No module in Layer N lists a dependency at Layer N or higher (invariant check)
- [ ] No named-layer module (Layers 1-6) lists a standalone module as a dependency
- [ ] The Impact Analysis correctly identifies leaf nodes (zero consumers)
- [ ] The audit commands above produce output consistent with the document

---

## Appendix: Quick Reference Card

For fast lookups during active development:

```
LAYER 1 (Base)
  ar-meta       ← root
  ar-relation   ← ar-meta
  ar-io         ← ar-meta
  ar-code       ← ar-relation, ar-io
  ar-collect    ← ar-code
  ar-hardware   ← ar-collect

LAYER 2 (Compute)
  ar-algebra    ← ar-hardware
  ar-geometry   ← ar-algebra
  ar-stats      ← ar-algebra
  ar-time       ← ar-geometry

LAYER 3 (Domain)
  ar-heredity   ← ar-time
  ar-color      ← ar-geometry, ar-stats
  ar-physics    ← ar-time, ar-color
  ar-graph      ← ar-geometry, ar-heredity
  ar-chemistry  ← ar-physics, ar-heredity
  ar-space      ← ar-graph, ar-physics

LAYER 4 (Engine)
  ar-optimize     ← ar-graph
  ar-utils        ← ar-space, ar-chemistry, ar-optimize
  ar-render       ← ar-space, ar-utils
  ar-ml           ← ar-utils
  ar-audio        ← ar-utils
  ar-utils-http   ← ar-utils

LAYER 5 (Extern)
  ar-ml-djl     ← ar-ml
  ar-ml-onnx    ← ar-ml
  ar-ml-script  ← ar-ml

LAYER 6 (Studio)
  ar-music        ← ar-audio
  ar-compose      ← ar-audio, ar-ml
  ar-spatial      ← ar-compose
  ar-experiments  ← ar-compose, ar-ml-onnx, ar-ml-djl, ar-ml-script, ar-utils

STANDALONE
  ar-flowtreeapi      ← ar-utils
  ar-graphpersist     ← ar-utils
  ar-flowtree-python  ← ar-flowtreeapi
  ar-flowtree         ← ar-flowtreeapi, ar-flowtree-python, ar-graphpersist, ar-utils-http
  ar-tools            ← ar-ml
```

**Golden rule:** Lower layers never depend on higher layers. If you need to draw
an arrow that goes upward in the diagram above, stop and redesign.
