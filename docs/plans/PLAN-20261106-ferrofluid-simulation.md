---
status: proposed
created: 2026-11-06
closed:
tracker: []
---

# Ferrofluid Simulation and Rendering — Capability Inventory and Roadmap

## 1. Purpose and framing

This is a proof-of-value plan. The motivating phenomenon is **ferrofluid**: a colloidal
suspension of magnetic nanoparticles in a carrier liquid, stabilized by a surfactant. Placed
in a strong, roughly vertical magnetic field, a ferrofluid's free surface becomes unstable and
spontaneously forms a regular array of sharp conical spikes — the **normal-field (Rosensweig)
instability**. The end goal is end-to-end: define a material and a scene, **simulate** the
fluid responding to a magnetic field, and **ray-trace** the result into images (ultimately a
frame sequence), using this repository's own physics, chemistry, geometry and rendering tools.

Ferrofluid was chosen because doing it honestly exercises a wide span of the platform:
magnetostatics, continuum fluid dynamics with a free surface, surface reconstruction, a
scalable intersection/acceleration path, and glossy reflective shading. **The phenomenon is the
means, not the end.** Wherever there is a choice, this plan favors work that leaves the
framework generally stronger (a reusable fluid step, a reusable isosurface extractor, a cleaned
ray tracer) over a one-off effect wired only for this demo.

This document is an **inventory first**: for each capability the end-to-end result needs, what
exists today, what needs improvement, and what is missing — with evidence from the code. It then
proposes a pipeline, the cleanup the engines need, the key technical choices, a dependency-ordered
roadmap with an early end-to-end slice, proof-of-value criteria, and open questions for the owner.

### How the evidence was gathered

Every status below rests on **reading the current source** on this branch (physics, chemistry,
space, geometry, color, render, and the shared compute/time, compute/algebra, base/collect,
base/hardware). Where a status depends on runtime behavior that reading cannot settle, it is
marked **unverified** — no simulation or render was executed in preparing this plan, and the
existing render/sim tests were read, not run. Classes are referenced by stable name and module
path rather than line number (per `docs/CLAUDE.md`); the git history and the files named are
sufficient to locate each claim.

---

## 2. What the end-to-end result requires

Working outward from the physics of the Rosensweig instability and the desired output, an
honest end-to-end ferrofluid pipeline needs all of the following. Each is a requirement the
inventory in §4 is measured against.

**Material and scene definition**
- R1. A material description: carrier density, dynamic viscosity, surface tension, and the
  magnetic response (susceptibility χ, and ideally a saturation magnetization M_s giving a
  nonlinear M(H) curve), plus optical appearance (dark, glossy, near-opaque).
- R2. A scene: a container, a free surface at rest, a magnet or applied field, a camera, lights.

**Magnetics**
- R3. A magnetic field in the simulation domain: either an analytic applied field H₀ or a field
  from a source (magnet), and the demagnetizing correction inside the magnetizable fluid.
- R4. The fluid's magnetization M(H) (linear χ·H, or a saturating Langevin/`tanh` law).
- R5. The magnetic body force on the fluid and/or the magnetic normal stress (Maxwell stress) at
  the free surface — the term that, balanced against gravity and surface tension, drives spikes.

**Fluid dynamics**
- R6. An incompressible flow solver: advection, pressure projection (divergence-free velocity),
  viscosity, and gravity.
- R7. Surface tension as a force proportional to interface curvature (the Rosensweig balance is
  magnetic stress vs. gravity + surface tension; both the gravity and surface-tension terms must
  be present or the instability's threshold and wavelength are meaningless).
- R8. Free-surface tracking: a representation of where the liquid is that can develop spikes and
  pinch — a height field (cheapest, good for the canonical flat-dish instability), a level set /
  volume fraction, or a particle surface.
- R9. A stable time integrator with a sensible stability condition (CFL / viscous / surface-tension
  limits), driven by the framework's simulation loop owner.
- R10. Boundary and container conditions: walls (no-penetration, optionally no-slip), and a
  wetting/contact condition at the container rim.

**Simulation substrate**
- R11. A time-stepped, globally-coupled loop (a fluid step reads neighbors, so it is coupled).
- R12. Large state arrays (a 2D height field is cheap; a 3D grid at 128³ is ~2M cells; a particle
  method is 10⁵–10⁶ particles) resident on the device.
- R13. For a particle method only: neighbor search (spatial hash / cell list) on the device.
- R14. For a grid/spectral method: a pressure/Poisson solve (iterative, multigrid, or spectral FFT).

**Geometry and rendering**
- R15. Surface reconstruction: turn simulation state (height field or scalar field or particles)
  into **renderable geometry** every frame — a triangle mesh (marching cubes / height-field
  tessellation) or an implicit surface for ray marching.
- R16. Ray–surface intersection that **scales** to the triangle counts a spiky surface produces,
  i.e. an acceleration structure, not a linear scan.
- R17. Materials and shading for a dark, glossy, nearly-opaque fluid: diffuse + specular base,
  glossy Fresnel reflection, environment/lighting, optional thin refractive rim.
- R18. Camera, anti-aliasing, and image file output.
- R19. A frame-sequence driver: step the sim, reconstruct, render, write frame N, repeat.

**Cross-cutting**
- R20. GPU/CPU acceleration across backends (native, Metal, OpenCL, CUDA) honoring the Producer
  pattern, within device memory limits, at a useful resolution.
- R21. Determinism sufficient to compare runs, and a validation strategy combining physical checks
  (analytic thresholds, known instability behavior) with visual ones.

---

## 3. The end-to-end pipeline

The proposed pipeline and the data handed between stages. Stage boundaries are the only places
`.evaluate()`/host reads are allowed; everything inside a stage is a Producer graph compiled to a
kernel (see §10, Design constraints).

```
[A] Material + Scene definition
        density ρ, viscosity μ, surface tension σ,
        susceptibility χ (and M_s), optical appearance;
        container geometry, applied field H0 / magnet, camera, lights
            │  (scalar/continuum parameters + scene objects)
            ▼
[B] Magnetic field solve  (per frame, or static if H0 is fixed)
        H(x) in the domain, M(H) in the fluid, magnetic stress/force field
            │  (PackedCollection field(s) over the sim domain)
            ▼
[C] Fluid step  (owned by SimulationRunner; many steps per rendered frame)
        advect + viscosity + gravity + surface tension + magnetic force,
        pressure projection, boundary conditions → new fluid state
            │  (PackedCollection: height field h(x,y) or scalar φ(x,y,z) or particles)
            ▼
[D] Surface reconstruction
        height field → tessellated Mesh, or scalar field → marching-cubes Mesh,
        or φ → implicit surface for ray marching
            │  (Mesh / MeshData, rebuilt per frame, or an SDF Producer)
            ▼
[E] Scene assembly
        reconstructed surface + fluid material (dark glossy) + container + lights + camera
            │  (Scene / ShadableSurface graph)
            ▼
[F] Ray-trace render
        RayTracedScene → intersection + shading → RGB image, with supersampling
            │  (RGB[][] / CollectionProducer image)
            ▼
[G] Image output
        encode PNG per frame → frame sequence on disk
```

Stages B and C run inside the simulation loop (C is stepped many times between rendered frames);
D–G run once per output frame. A host boundary sits between C and D each frame: the simulation
state is handed to reconstruction. The frame-sequence driver (R19) owns the outer frame loop; it
must **not** own the inner physics step loop — that belongs to `SimulationRunner`.

---

## 4. Capability inventory

Legend for **Status**: **PRESENT** (implemented, connected, usable as-is) · **IMPROVE** (exists
but partial, stubbed, host-side, or disconnected) · **MISSING** (no implementation). **Blocks?**
= does the end-to-end result fail without it (Yes), or is it only a fidelity/quality improvement
(No). **Size** is a rough order of magnitude for the work to make it usable: S / M / L.

### 4.1 Simulation and compute infrastructure (base, compute, engine/utils)

| Requirement | Capability | Status | Blocks? | Size | Evidence |
|---|---|---|---|---|---|
| R11 | Globally-coupled time-stepped loop | **PRESENT** | — | — | `SimulationRunner` (`compute/time`, `org.almostrealism.time`) owns the loop, extends `TemporalRunner`; a system implements `Temporal.tick()` as a Producer step. Well-tested: `SimulationRunnerTest` (`engine/utils`) covers element-local, **coupled** (`dampedOscillatorCoupledState`: `matmul` + copy-back), boundary input, step counter, lifecycle, reset, and a 12000×12000 `largeDenseStep` at `@TestDepth(2)`. Contract documented in `docs/internals/simulation-loop-examples.md`. |
| R12 | Large device-resident arrays | **PRESENT** | — | — | `PackedCollection` (`base/collect`); indexing bounded by `int` length (~2.1e9), real limit is device memory via `AR_HARDWARE_MEMORY_SCALE`. 128³ grid (~2M) and 10⁶ particles are well within range. `largeDenseStep` exercises ~0.55 GB. |
| R20 | GPU/CPU backends, kernel compile/dispatch | **PRESENT** | — | — | `base/hardware`: native (JNI), OpenCL, Metal (`libMTL`), CUDA (NVRTC). Signature-cached compilation; `ComputeRequirement.GPU/CPU` steering; Metal command-buffer batching. `base/hardware/README.md`. |
| R21 | Determinism across backends | **IMPROVE** | No | S | No cross-backend determinism contract found; Metal is FP32, native can be FP64, shared-memory mode forces FP32. Reproducibility requires pinning precision + backend. Adequate for within-backend comparison; document and pin for validation. |
| R14 | FFT for spectral Poisson | **IMPROVE** | No* | M–L | `FourierTransform` (`compute/time`) is a real compiled radix-2/4 FFT but **1-D only, CPU only** by default (`enableRecursion=true`; `compileGpu` throws `UnsupportedOperationException`). No 2D/3D transform and no Poisson solver exist. *Only blocks if a spectral pressure solve is chosen; an iterative Jacobi/multigrid solve avoids it. |
| R13 | Device neighbor search (spatial hash / cell list) | **MISSING** | Yes* | L | Repo-wide search finds no spatial hash, cell list, uniform-grid binning, or GPU k-d tree. The only spatial structure is `KdTree` (`domain/graph`), **host-Java**, used by `RayField` only; not a Producer. *Blocks only the particle (SPH) method; a grid/height-field method avoids it. |
| — | Particle substrate | **MISSING** | No | — | `ParticleGroup` (`compute/algebra`) is a one-method host interface (`double[][] getParticleVertices()`) with **no implementation**; `Sphere` even carries a `// TODO Add ParticleGroup implementation`. Not a simulation substrate; do not build on it. |

### 4.2 Physics (domain/physics)

`domain/physics` has **no `src/test` of its own**; the only tests touching it (photon/absorber
path) live in `engine/render` and `engine/utils`. The module is overwhelmingly a legacy
photon/light-absorption simulator plus a small rigid-body state holder.

| Requirement | Capability | Status | Blocks? | Size | Evidence |
|---|---|---|---|---|---|
| R3 | Magnetic field / magnetostatics | **MISSING** | Yes | L | No magnetostatics anywhere. The only "magnetic" tokens in the repo are quantum numbers in `domain/chemistry` (`Orbital`, `Spin`). Closest analog is the **electrostatic** `PotentialMap`/`ProtonCloud` (`org.almostrealism.electrostatic`), but that is a host-Java per-point `k·q/|r|` **sampler**, and its aggregate forms (`SpanningTreePotentialMap`, `PotentialMapHashSet`) are **stubs returning 0**. Useful only as a conceptual template. |
| R4 | Magnetization M(H) of the fluid | **MISSING** | Yes | M | No magnetization model. (Once H exists, a linear χ·H or saturating `tanh`/Langevin law is a small Producer graph — the cost is in the field, not the constitutive law.) |
| R5 | Magnetic body force / surface stress | **MISSING** | Yes | M | No Kelvin body force or Maxwell stress term. This is the term that drives the instability. |
| R6 | Incompressible flow (advection, pressure projection, viscosity) | **MISSING** | Yes | L | No Navier–Stokes, no advection, no pressure projection, no viscosity anywhere in the repo. `Pressure` (`domain/physics`) is an **empty placeholder class** (javadoc only). |
| R7 | Surface tension | **MISSING** | Yes | M | No surface-tension/curvature term anywhere. |
| R8 | Free-surface tracking | **MISSING** | Yes | M–L | No height field, level set, or volume-fraction interface. `Volume` (`domain/physics`) is a static containment interface (`inside`/`intersect`), not an evolving surface. |
| R9 | Time integrator (Euler/Verlet/RK) with stability | **MISSING** | Yes | M | No general integrator. The only ones present are an exponential-Euler inside `RatePopulation` (`domain/graph`, neural) and a hand-rolled semi-implicit Euler inside `RigidBody.State` (host-Java). An integrator must be written as a Producer step. |
| R10 | Boundary / container conditions | **MISSING** | Yes | M | No fluid BCs (no-slip, wetting, pressure). `Volume.inside()` gives static containment only. |
| — | Rigid body dynamics | **IMPROVE** | No | M | `RigidBody.State.update()` (`domain/physics`) is host-Java semi-implicit Euler on `Vector`; **untested**, not driven by any loop, consumed only by `RigidBodyStateShader` (rendering). Not on the ferrofluid critical path (a fluid is not a rigid body); relevant only if a container is treated as dynamic. |
| — | Physical constants | **IMPROVE** | No | S | `PhysicalConstants` is real and correct but scoped to photon-sim units (µm/µs/eV); lacks µ₀ and fluid constants. Add magnetic/fluid constants where the new physics lives. |
| R17 (render input) | PhotonField / Absorber path | **PRESENT** (legacy) | No | — | `PhotonField`/`Absorber`/`VolumeAbsorber` + `DefaultPhotonField` (`engine/render`) are a working **host-Java CPU** photon simulation with tests (`DefaultPhotonFieldTest`, `AbsorberHashSetTest`, `PhotonFieldContextCloneTest`). **Does not depend on the external `rings` repo.** Relevant only as an alternative render path, not for the fluid; likely dead weight for this project (see §5). |

**Conclusion for physics:** essentially the entire physics stack the ferrofluid needs
(magnetostatics, magnetization, magnetic force, incompressible flow, surface tension, free-surface,
integrator, BCs) must be **built new** as Producer/`CollectionProducer` graphs driven by
`SimulationRunner`. The physics module's existing content is not reusable for it beyond
`PhysicalConstants` and shared math types.

### 4.3 Chemistry and materials (domain/chemistry)

| Requirement | Capability | Status | Blocks? | Size | Evidence |
|---|---|---|---|---|---|
| R1 (optical) | Element identity / periodic table | **PRESENT** | No | — | `Element` holds 118 constants (atomic number + electron shells). Tested (`ElementConstructionTest`, `ElementPeriodicTableTest`, `engine/utils`). |
| R1 | Composition bookkeeping | **PRESENT** (as a sampler) | No | — | `Alloy` is a `ProbabilisticFactory<Atom>` that picks an element by ratio — it records composition but **never averages physical properties**. |
| R1 | Bulk properties from composition (density, viscosity, χ, M_s) | **MISSING** | No | L | `Material` **discards its constructor argument** (empty stub; its own javadoc lists density/conductivity as "future"). `Element` carries no atomic mass, density, or magnetic moment. `Spin` is a valueless `{Up,Down}` enum, never aggregated into a moment. There is no path from composition to any bulk mechanical or magnetic property. |

**Conclusion for chemistry:** the chemistry tools model electron configuration and optical/spectral
behavior only. They **cannot honestly derive** the bulk properties a ferrofluid needs, and real
ferrofluid bulk magnetics/viscosity are empirical anyway. Recommendation: supply ρ, μ, σ, χ, M_s as
**continuum input parameters** (R1), and use `Element`/`Alloy` at most as an **optional provenance
layer** that records what the fluid is nominally made of. This is a quality nicety, never a blocker.

### 4.4 Geometry and surface representation (domain/space, compute/geometry)

| Requirement | Capability | Status | Blocks? | Size | Evidence |
|---|---|---|---|---|---|
| R15 | Surface reconstruction (field/particles → mesh) | **MISSING** | Yes | L | No marching cubes, isosurface, metaball, or height-field tessellation anywhere in the repo. **Single largest upstream gap** — nothing bridges simulation state to geometry. |
| R15 (alt) | Implicit-surface / SDF path | **MISSING** (stubbed) | Yes* | L | `DistanceEstimator` (`domain/space`) is a host-Java interface (`double estimateDistance(Ray)`), only implemented by `Sphere`; the render side (`RayMarchingEngine`, `DistanceEstimationLightingEngine`) is stubbed (see §4.5). *Blocks only if the implicit route is chosen. |
| R16 | Ray–triangle intersection | **PRESENT** | — | — | Compiled Möller–Trumbore via `MeshData.evaluateIntersectionKernel` + `TriangleIntersectAt` (`domain/graph`); closest-hit by `RankedChoiceEvaluable`. Tested (`MeshDataIntersectionTest`). |
| R15 (mesh I/O) | Dynamic mesh build each frame | **PRESENT** | — | — | `Mesh` has a full mutable API and a `VertexData` provider; `getMeshData()` rebuilds the packed GPU buffer. A per-frame rebuild is feasible (the blocker is producing the geometry, not the mesh API). `buildSpatialVertexTree()` throws (not needed if reconstruction emits a flat mesh). |
| R16 | Acceleration structure (BVH/octree) | **MISSING** (BSP stubbed) | Yes | L | `SpacePartition` BSP construction works but **traversal returns null** (`intersectAt` stub; `SpacePartitionTest` asserts null), and `Mesh/Triangle.calculateBoundingSolid` **throw**. Intersection today is an **unaccelerated linear scan** over all triangles — will not scale to a spiky surface. |
| R16 | GPU mesh intersection path | **IMPROVE** | No | M | `CachedMeshIntersectionKernel` runs the triangle kernel on-device but loops rays on the host (`enablePartialKernel=true`) and reads the cache back per pixel with `toDouble`. Works; not yet a single kernel. |
| — | CSG / primitives | **IMPROVE** | No | — | `CSG` supports UNION only (host Java); DIFFERENCE/INTERSECT throw. `Sphere`/`Plane` usable for scaffolding. Not on the critical path. |

### 4.5 Rendering (engine/render, domain/color)

Primary working path: `RayTracedScene` → `RayTracer` → `RayIntersectionEngine` → per-surface/light
`IntersectionalLightingEngine` aggregated by `LightingEngineAggregator`. It is a **hybrid**:
per-ray intersection and the color source are compiled, but ranked-choice surface selection and
color combine run as **host-Java per-pixel loops**. **No CI test renders and asserts a lit image**
— the end-to-end `SimpleRenderTest` cases are `@TestProperties(knownIssue = true)` and excluded from
the pipeline; only camera-ray and intersection-distance unit tests run (unverified beyond reading).

| Requirement | Capability | Status | Blocks? | Size | Evidence |
|---|---|---|---|---|---|
| R16 | Ray-trace entry point (intersection) | **PRESENT** (hybrid) | — | — | `RayTracedScene`/`RayIntersectionEngine`; compiled per-ray intersection, host-loop orchestration. |
| R17 | Diffuse / specular / ambient lighting | **PRESENT** | — | — | `LightingEngine` builds a Producer lighting graph; `DiffuseShader`/`HighlightShader`/`AmbientLight`/`PointLight`. A dark glossy base is achievable today with diffuse + highlight. |
| R17 | Glossy Fresnel reflection | **IMPROVE** (broken) | Yes | M–L | `ReflectionShader` has a correct Schlick/Fresnel Producer structure but calls `LightingEngineAggregator.getAccelerated()`, which routes to `RankedChoiceEvaluableForMemoryData.getAccelerated()` — **throws `UnsupportedOperationException`**. Reflections are broken by default. Fixing this is the main shading blocker for a glossy fluid. |
| R17 | Refraction / Fresnel rim | **IMPROVE** (non-functional) | No | L | `RefractionShader` is class-level `// TODO Fix refraction algorithm`; a host-Java lambda with debug `Math.random()` logging and a deprecated duplicate `refract()`. Minor for a near-opaque fluid; a thin Fresnel rim is a nicety. |
| R15 (alt) | Ray-march / SDF engines | **MISSING** (stubbed) | No* | L | `RayMarchingEngine.trace()` returns null (`// TODO`; its test asserts null); `DistanceEstimationLightingEngine` passes null to super with the march loop commented out. *Only relevant if the implicit route is chosen. |
| R18 | Camera / ray generation | **PRESENT** | — | — | `PinholeCamera.rayAt` via `ProjectionFeatures` (compiled); `ThinLensCamera` present. |
| R18 | Anti-aliasing / supersampling | **IMPROVE** | No | M | `SuperSampler`/`Pixel` average an N×M grid correctly but as a host-Java loop (`toDouble`/`addTo` per pixel). Correct, slow. |
| R18 | Image file output (PNG) | **PRESENT** | — | — | `ImageCanvas.encodeImageFile` and `RGBFeatures.saveRgb` both emit PNG via `ImageIO`. |
| R19 | Animation / frame-sequence driver | **MISSING** | Yes | S–M | Two abandoned `Animation` classes (`domain/color/texture`, `domain/space`); no scene/time-coupled frame driver. `RayTracedScene` offers only single-image `realize()`. The pieces exist; a driver must orchestrate step → reconstruct → render → save per frame at the host boundary. |
| — | Photon-mapping renderer | **IMPROVE** (dead) | No | — | `AbsorberHashSet` + `DefaultPhotonField` form a separate photon renderer referenced only by its own tests; not wired into `Engine`. Candidate for removal/relocation (see §5). |

---

## 5. Cleanup the engines need (and why)

The task asks specifically what cleanup the ray-tracing and physics engines need. Reported from
what the code actually is, not assumed:

**Ray tracer (engine/render).** Tie each item to the goal:

1. **`RankedChoiceEvaluableForMemoryData.getAccelerated()` throws** — this is the advertised GPU
   aggregation path, and `ReflectionShader` depends on it unconditionally. *Why it matters:* glossy
   reflections (R17) are the defining look of ferrofluid and are broken until this is implemented as
   a compiled kernel (or the throw and its callers are removed). **Blocks the glossy look.** Size L.
2. **Host per-pixel orchestration** — `RankedChoiceEvaluable.evaluate`, `LightingEngineAggregator.evaluate`,
   and `SuperSampler` select/combine per pixel on the host. *Why:* a frame sequence (R19) at useful
   resolution needs the selection+combine in a compiled kernel, or render time dominates. Improves,
   does not block a first slice. Size M–L.
3. **`RefractionShader` rewrite** — non-functional host-Java with debug randomness and a deprecated
   duplicate method. *Why:* only a thin Fresnel rim is wanted for a near-opaque fluid; rewrite as a
   Producer graph or scope it out. Improves. Size L.
4. **Dead/stubbed engines** — `RayMarchingEngine` and `DistanceEstimationLightingEngine` are stubs
   (null/commented-out); the `AbsorberHashSet`/`DefaultPhotonField` photon renderer is unwired.
   *Why:* remove or clearly quarantine so the one working path is unambiguous; keep the SDF engines
   only if the implicit-surface route (§7) is chosen. Size S–M.
5. **No CI render assertion** — the end-to-end render tests are `knownIssue`-excluded. *Why:* the
   project needs a test that renders a known scene and asserts on the image, restored once the path
   works, so regressions are caught. Size M (gated on item 1). *This is additive — a new/restored
   test, never weakening an existing one.*

**Physics "engine."** There is essentially no fluid/magnetics engine to clean up — it must be
written (§4.2). The genuine cleanup items are: (a) `Pressure` is an empty placeholder to be
replaced by real pressure handling; (b) `PhysicalConstants` needs µ₀ and fluid constants added;
(c) decide the fate of `RigidBody.State` (host-Java, untested) — out of the ferrofluid critical
path, so leave it unless a dynamic container needs it, in which case it is reworked as a Producer
step. The electrostatic `PotentialMap`/`SpanningTreePotentialMap` stubs are a conceptual template
for a field source but are not worth reviving as-is.

---

## 6. Key technical choices

Each choice lists options, a recommendation, and reasoning. Choices marked **[OWNER]** are
value/scope decisions the owner should make; the rest are engineering recommendations open to
revision.

### 6.1 Fluid method — **[OWNER]**

- **Option A — 2D height field (shallow surface model).** Represent the free surface as h(x,y) on
  a grid; evolve it with a surface PDE including gravity, surface tension (curvature of h), and the
  magnetic normal stress. *Pros:* smallest state (a 2D grid), maps directly to the canonical
  flat-dish Rosensweig experiment, no neighbor search, no 3D pressure solve, reuses the existing FFT
  (1-D along rows/cols) or a cheap iterative solve; cheapest path to the actual spikes. *Cons:*
  cannot represent overturning/pinching spikes or arbitrary geometry; a reduced physical model.
- **Option B — 3D Eulerian grid (MAC grid, level set / volume-of-fluid free surface).** Full
  incompressible Navier–Stokes with pressure projection and a level-set interface. *Pros:* general,
  physically complete, reusable fluid engine. *Cons:* needs a 3D Poisson solve (iterative or a new
  3D FFT), a level-set/VOF advection + reinitialization, and far more new code; highest risk.
- **Option C — SPH particles.** Lagrangian particles with a magnetic body force. *Pros:* free
  surfaces are automatic, visually appealing. *Cons:* **requires the missing device neighbor
  search** (R13, a large new subsystem), surface reconstruction from particles is harder, and
  pressure/incompressibility with SPH is delicate. Highest infrastructure cost.

**Recommendation:** start with **A (height field)** for the vertical slice and the canonical
instability, because it reaches real, checkable spikes with the least missing infrastructure and
leaves behind a reusable 2D surface-dynamics capability; then pursue **B (3D grid)** as the
general fluid engine if the owner wants arbitrary geometry and overturning dynamics. Treat **C
(SPH)** as a later option justified only if particle aesthetics or splashing become a goal — it
front-loads the largest missing piece (neighbor search). The A-vs-B/C scope call is the owner's.

### 6.2 Surface representation for rendering — **[OWNER, coupled to 6.1]**

- **Option A — height-field tessellation.** A height field maps trivially to a regular triangle
  grid (two triangles per cell). *Pros:* trivial, fast, reuses the working `Mesh`/`MeshData`
  intersection path; pairs with fluid Option A. *Cons:* only single-valued surfaces.
- **Option B — marching cubes from a scalar field.** Extract an isosurface mesh from a 3D
  level-set/VOF field. *Pros:* general, handles overturning; pairs with fluid Option B; a broadly
  reusable capability. *Cons:* a substantial new Producer-based extractor, and larger meshes that
  need the acceleration structure (R16).
- **Option C — implicit surface / SDF ray marching.** Render φ directly without meshing. *Pros:*
  no meshing, no triangle explosion. *Cons:* requires reviving the stubbed ray-march engines and a
  Producer-form SDF; a second, parallel render path to maintain.

**Recommendation:** **A** for the slice (height-field tessellation + the existing mesh
intersection), **B** (marching cubes) as the general reconstruction capability alongside fluid
Option B. Prefer meshing (A/B) over SDF (C) because the triangle intersection path already works and
is tested, whereas the ray-march path is entirely stubbed. If **B** is chosen, the **acceleration
structure (R16) becomes mandatory**, since isosurface meshes of a spiky surface are large.

### 6.3 Magnetic model

- **Option A — analytic applied field H₀ (uniform or simple gradient), linear M = χ·H.** *Pros:*
  no field solver; directly yields the Rosensweig threshold for the flat-dish case; smallest code.
  *Cons:* ignores demagnetization and spatial field structure from a real magnet.
- **Option B — field from a source + demagnetizing correction; saturating M(H) (Langevin/`tanh`).**
  *Pros:* physically richer, needed for a real magnet geometry and strong fields. *Cons:* needs a
  magnetostatic solve (a Poisson-like problem — the same solver infrastructure as the pressure
  step).

**Recommendation:** **A** first (it is sufficient to reproduce the instability threshold and
wavelength and is a small Producer graph), then **B** sharing whatever Poisson/field-solve
machinery the fluid step introduces. The constitutive law (linear vs. saturating) is cheap either
way; the cost is in the field, so couple B's timing to the pressure-solver decision (§6.4).

### 6.4 Pressure / field solver (if fluid Option B) 

- **Option A — iterative (Jacobi/Gauss–Seidel/CG, optionally multigrid)** as Producer graphs.
  *Pros:* no new FFT work, flexible boundaries, each iteration is a stencil kernel. *Cons:* many
  iterations per step; multigrid is more code.
- **Option B — spectral (FFT) Poisson solve.** *Pros:* fast for periodic/simple boundaries.
  *Cons:* requires a **new 2D/3D FFT** and a validated GPU path (current FFT is 1-D CPU-only).

**Recommendation:** **iterative (A)** first — it reuses only the existing collection algebra and
avoids the FFT gap; revisit spectral (B) only if iteration dominates runtime. Not needed at all for
fluid Option A with a cheap surface solve.

### 6.5 Materials source

- **Option A — continuum parameters.** Supply ρ, μ, σ, χ, M_s directly.
- **Option B — derive from chemistry composition.** Not supported honestly (§4.3).

**Recommendation:** **A**, with `Element`/`Alloy` used at most as an optional provenance record.
This is an engineering recommendation, not an owner call, because B is not achievable without
inventing new chemistry data/physics.

---

## 7. Phased roadmap

Ordered by dependency. Every phase ends with something demonstrable and verifiable. Phase 0 is the
**early reduced vertical slice** that goes end-to-end at low fidelity so integration risk surfaces
first. Later phases deepen fidelity and generality. (These phases are logical; they are not tracker
tasks and create none — see §11.)

**Phase 0 — End-to-end vertical slice (height field, analytic field, static render).**
Build the thinnest complete path: a 2D height field h(x,y) with gravity + surface tension + a
**prescribed static** magnetic stress term, stepped by `SimulationRunner`; height-field → `Mesh`
tessellation; a single ray-traced image of the surface with a dark glossy (diffuse+highlight)
material; PNG output. No field solver, no reflections, no animation driver yet.
*Demonstrable:* one rendered PNG of a perturbed liquid surface produced by the real sim → mesh →
ray-trace path. *Verifiable:* the mesh matches the height field; the image is non-trivial and
lit. *Purpose:* proves the stage boundaries (C→D→E→F→G) and the Producer/loop constraints end to
end before any hard physics.

**Phase 1 — Magnetics and the instability (physics correctness).**
Add the magnetization law M(H) and the magnetic normal stress coupled to the height field; replace
the prescribed stress with χ·H₀ (magnetic Option A). Tune gravity/surface-tension/magnetic balance.
*Demonstrable:* a simulation where, above a field threshold, a flat surface spontaneously forms a
regular spike pattern; below it, stays flat. *Verifiable (physical):* the critical field for onset
and the spike spacing (wavelength) match the analytic Rosensweig/Cowley–Rosensweig predictions
within tolerance; dimensional/energy sanity checks.

**Phase 2 — Rendering quality (glossy fluid).**
Fix `RankedChoiceEvaluableForMemoryData.getAccelerated()` so `ReflectionShader` works; assemble a
dark, glossy, near-opaque material with environment/lights; supersampling. *Demonstrable:* a
convincing still of the Phase 1 spikes with glossy Fresnel reflection. *Verifiable:* reflection is
present and correct (mirror a known environment); a restored CI render test asserts on the image.

**Phase 3 — Animation (frame sequence).**
Add the frame-sequence driver (step → reconstruct → render → save per frame). *Demonstrable:* a
short clip of spikes forming as the field ramps up. *Verifiable:* N frames on disk, monotonic
field schedule, deterministic within a pinned backend/precision.

**Phase 4 — Scaling and acceleration (make it usably fast / higher resolution).**
Add a triangle acceleration structure (R16) and move the ray-trace orchestration + supersampling
into compiled kernels (ray-tracer cleanup items 1–2). *Demonstrable:* the same clip at higher
resolution / larger grid at materially better frame time. *Verifiable:* measured intersection
scaling vs. the linear-scan baseline; frame time budget met at a stated resolution.

**Phase 5 (optional) — Generality: 3D fluid + marching cubes.**
Only if the owner wants arbitrary geometry/overturning: 3D grid fluid (Option B), pressure
projection (§6.4), level-set/VOF free surface, marching-cubes reconstruction, field-from-source
magnetics (magnetic Option B). *Demonstrable:* a 3D ferrofluid drop/pool interacting with a magnet.
*Verifiable:* mass conservation, divergence-free velocity, and the instability still reproduced.

Each phase leaves a reusable capability behind: Phase 0–1 a surface-dynamics step and a
height-field mesher; Phase 2 a working glossy reflection path; Phase 3 a frame-sequence render
driver; Phase 4 an acceleration structure and kernelized render; Phase 5 a general fluid engine and
isosurface extractor — all usable beyond ferrofluid.

---

## 8. Proof of value — concrete criteria

Proof of value is the end-to-end demonstration **plus** per-phase checkable criteria, with physical
checks wherever possible (not only "it looks right").

**Overall demonstration (project success):** a frame sequence, produced entirely by this
repository's tools, of a ferrofluid surface forming the characteristic spike array as an applied
magnetic field increases past threshold, rendered as a dark glossy liquid — accompanied by a
physical check that onset and spike spacing match theory.

**Per-phase acceptance:**
- **Phase 0:** one PNG from the real C→G path; an automated check that the rendered geometry is the
  sim's height field (vertex positions equal h within tolerance) and the image has non-background,
  lit pixels.
- **Phase 1 (physical):** (a) **threshold** — below the predicted critical magnetization the flat
  state is stable (surface stays flat to within noise); above it, perturbations grow. (b)
  **wavelength** — measured spike spacing matches the critical wavelength λ_c = 2π√(σ/(ρg)) (2π
  times the capillary length √(σ/(ρg))) within a stated tolerance. Both (a) and (b) are predictions
  of **linear** stability theory and are the robust, quantitative acceptance criteria for a
  height-field model. (c) **hexagonal/regular pattern selection**, as observed experimentally — a
  *weakly-nonlinear* phenomenon. Treat this as a **qualitative/stretch** check, not a pass/fail gate:
  pattern selection and the sharp, overturning conical spike morphology are nonlinear effects that a
  single-valued height field may not reproduce honestly (see §6.1 Option A and §9); reaching them
  faithfully may require the 3D model (Phase 5). (d) energy/dimensional sanity. Criteria (a), (b),
  and (d) are quantitative, not visual.
- **Phase 2:** reflection correctness (a known environment is mirrored in the surface at the
  geometrically correct angle); a restored, asserting CI render test; no `knownIssue` exclusion of
  the new render assertion.
- **Phase 3:** N frames, correct ordering, reproducible under a pinned backend+precision; field
  schedule honored.
- **Phase 4:** measured intersection cost scales sub-linearly vs. triangle count relative to the
  linear-scan baseline; stated resolution renders within a stated time budget.
- **Phase 5:** mass conservation and divergence-free velocity within tolerance; instability still
  reproduced in 3D.

**Validation method.** Physical checks are preferred and are expressed as automated tests
(analytic threshold/wavelength comparisons, conservation checks) following the test rules in
CLAUDE.md (extend `TestSuiteBase`, bounded sizes, timeouts). Visual checks supplement them. All new
tests are additive; no existing test is weakened to make a phase pass.

---

## 9. Risks and unknowns

- **Scope.** A complete, general 3D magnetohydrodynamic free-surface simulator is a large,
  multi-quarter effort. The height-field slice (Phase 0–2) is the realistic proof-of-value target;
  3D generality (Phase 5) is explicitly optional.
- **Numerical stability.** Surface tension and magnetic stress impose tight time-step limits
  (surface-tension CFL). The integrator and step size must respect them; getting a stable, correct
  instability is the main physics risk. Mitigated by the analytic checks in Phase 1.
- **Height-field model validity (physics).** A single-valued height field h(x,y) captures the
  **linear** onset of the Rosensweig instability — threshold field and critical wavelength — which is
  what the Phase 1 quantitative checks (§8) target. It cannot represent the fully-developed sharp
  conical spikes (which overturn and are strongly nonlinear), and the experimentally-observed
  **hexagonal pattern selection** is a weakly-nonlinear effect it may not reproduce faithfully. If a
  visually convincing spike field or correct pattern selection is a hard requirement rather than the
  linear onset, the 3D model (Phase 5) — not the height-field slice — is the honest vehicle. This is
  a model-fidelity limit, independent of the numerical-stability risk above.
- **Render performance.** The current orchestration is host-side per pixel and the intersection is a
  linear scan; naive frame sequences will be slow. Phase 4 addresses this, but the useful resolution
  is an unknown until measured.
- **FP32 precision.** Metal is FP32; accumulation in iterative solvers and long simulations may need
  FP64 (native) for the validation runs. Determinism is within-backend only.
- **Marching cubes at scale.** If Phase 5 is pursued, isosurface meshes are large and interact with
  the (new) acceleration structure; both are L-sized and coupled.
- **Chemistry contribution is minimal.** The plan deliberately supplies bulk properties as
  parameters; anyone expecting first-principles material properties from the chemistry module will
  be disappointed (§4.3).

**Out of scope (unless the owner expands it):** real-time/interactive rendering; global illumination
/ caustics / subsurface scattering beyond a glossy opaque look; thermal effects; surfactant/colloid
nanoscale modeling (the nanoparticle scale is represented only through continuum properties);
coupling to external magnet-design tools; the SPH particle method (unless §6.1 Option C is chosen);
and any first-principles derivation of ρ/μ/σ/χ from composition.

---

## 10. Design constraints the implementation must honor

From CLAUDE.md and `docs/internals`, binding on every phase:

- **Java is orchestration, not execution.** Every per-cell/per-particle/per-pixel computation — the
  fluid step, the magnetic force, surface tension, reconstruction, shading — is a `CollectionProducer`
  graph compiled to a kernel. No Java `for` loops over collection elements, no `toDouble`/`setMem`
  hot loops, no host arithmetic on simulation state. `.evaluate()`/host reads occur **only** at stage
  boundaries (§3): once per frame when handing sim state to reconstruction, and at image output.
- **`SimulationRunner` owns the step loop.** The fluid step is a `Temporal.tick()` returning an
  `OperationList`; the runner is constructed by a thin caller that does not write a step loop. A
  globally-coupled update **writes a next-state collection and copies it back in a later operation of
  the same step** (per the step contract in `docs/internals/simulation-loop-examples.md`); it must
  not use `buffer(n)`/`Loop` for the coupled step. The **outer frame loop** (render N frames) is a
  separate host-level concern and may use a frame driver, but must not drive the physics step.
- **`PackedCollection` is a GPU-memory handle,** not an array: no `System.arraycopy`/`Arrays.copyOf`;
  use the Producer pattern. Respect lifecycle (`Destroyable`/try-with-resources only for
  non-escaping locals).
- **Honor interfaces and orderings.** Computations respect whatever `TraversalOrdering` their
  arguments carry; no `instanceof` on implementations; new representations are collections configured
  with an ordering.
- **Module placement.** New fluid/magnetics physics belongs in `domain/physics` (it depends on
  `ar-color` and `ar-time`, so it can use `SimulationRunner`). Surface reconstruction belongs in
  `domain/space` (depends on `ar-physics`, `ar-graph`). Render cleanup is in `engine/render`.
  **No new Maven modules** may be created (CLAUDE.md); if a phase seems to need one, stop and report
  it. Domain-agnostic helpers belong in the general layer, not in a demo-specific location.
- **Code policy / quality.** `CodePolicyViolationDetector`, checkstyle (no `var`, no
  `@SuppressWarnings`, javadoc required, test timeouts), and the test-integrity rules apply.
  Validate with the build validator before completing any implementation phase.

---

## 11. Open questions for the owner

1. **Scope depth (§6.1):** is the target the **height-field slice** (Phases 0–2/3, reaches the real
   instability and a glossy clip) or the **general 3D fluid engine** (Phase 5)? This is the single
   biggest driver of effort.
2. **Fluid method and surface representation (§6.1, §6.2):** confirm height field + height-field
   tessellation first; approve 3D grid + marching cubes as the generalization, or redirect.
3. **Magnetic fidelity (§6.3):** is an analytic applied field (χ·H₀) sufficient for the demo, or is
   a real magnet geometry with a field solve and saturation required?
4. **Chemistry provenance (§4.3):** should `Element`/`Alloy` record the nominal composition as a
   provenance layer, or is that out of scope? (Bulk properties are supplied as parameters regardless.)
5. **Ray-tracer investment (§5):** how far to take the render cleanup — fix reflections only (Phase 2),
   or fully kernelize orchestration + add acceleration (Phase 4) within this project vs. as separate
   platform work?
6. **The `rings` repository.** Downstream documentation states that the separate `almostrealism/rings`
   repository holds `ar-raytracer` and `ar-photonfield`. **That repository is not in this checkout and
   could not be inspected.** The ray tracing inventoried here lives entirely in **this** repository
   (`engine/render` `org.almostrealism.raytrace`/`render`/`rayshade`, `domain/color`, `domain/space`),
   and the physics audit found **no dependency from this repo's render path on `rings`** (the
   `PhotonField`/`Absorber` code here is self-contained). **Open question:** does the owner intend the
   ferrofluid render to use this repository's ray tracer (the subject of this plan), or an external
   `rings`-based tracer? If any `rings` code is meant to be the renderer, it must be inventoried
   separately — this plan cannot assess code it cannot see.
7. **Validation strictness (§8):** what tolerances are acceptable for the Phase 1 physical checks
   (threshold field, spike wavelength), and is a within-backend (FP32-pinned) determinism guarantee
   sufficient, or is cross-backend agreement required?
8. **Performance target (§9):** what resolution and frame-time budget define "useful" for the
   demonstration?

---

## 12. Summary

The platform already provides the hard-to-build foundations: a tested, loop-owning
`SimulationRunner` with a coupled-step contract that fits a fluid update; a multi-backend GPU
execution model; large device-resident arrays; a working compiled ray–triangle intersection core; a
dynamically buildable mesh; and working PNG output. What is missing is the **domain middle**: all of
the magnetics and fluid physics, free-surface tracking and a time integrator, **surface
reconstruction** (the single largest upstream gap), a **triangle acceleration structure**, working
**glossy reflection** (the `getAccelerated()` throw), and a **frame-sequence driver**. The chemistry
tools cannot honestly supply bulk material properties, which are therefore continuum inputs.

The recommended path minimizes new infrastructure and surfaces integration risk early: a 2D
height-field fluid with an analytic field and height-field tessellation, rendered through the
existing (reflection-fixed) tracer, proven against the analytic Rosensweig threshold and wavelength —
then deepened toward a general 3D fluid and isosurface renderer only if the owner wants that
generality. Every phase leaves a reusable capability behind, which is the point: ferrofluid is the
means of finding and closing these gaps, and the framework is stronger for each one closed.
