---
status: proposed
created: 2026-11-06
closed:
tracker: []
---

# Ferrofluid Simulation and Rendering — Capability Inventory and Roadmap

## Revision note — 2026-11-09 (rings raytracer/photonfield migration)

This plan was first written before the rings repository's `raytracer` (`ar-raytracer`) and
`photonfield` (`ar-photonfield`) modules were inventoried. Open question 6 (§11) asked whether
that inventory should happen. It has: the **library** parts of both modules are now in `common`
on master. What moved, what was fixed, and what is still broken is recorded in
[`RINGS_RAYTRACER_MIGRATION.md`](RINGS_RAYTRACER_MIGRATION.md) and
[`RINGS_PHOTONFIELD_MIGRATION.md`](RINGS_PHOTONFIELD_MIGRATION.md), and the GLSL rendering option in
[`GLSL_RENDER_OPTION.md`](GLSL_RENDER_OPTION.md) (all three sit beside this plan in `docs/plans/`). This revision reconciles the plan with that code (every claim below was
re-checked against the current source on this branch). The substantive changes:

- **R19 frame-sequence driver** is no longer flatly MISSING: `RayTracedAnimation` (engine/render)
  renders a scene frame by frame to image files. It is a **reference, not a drop-in** — its base
  `Animation.run()` steps rigid-body physics itself, which §10 forbids of the outer frame loop
  (§4.5 R19, §7 Phase 3).
- **R17 reflection/refraction** failures are earlier and more concrete than "`getAccelerated()`
  throws": the new `ReflectionRefractionRenderTest` shows both shaders fail while
  `RayTracedScene.realize` builds the producer. The `getAccelerated()` gap still exists behind
  that (§4.5 R17, §5 items 1 and 3, §7 Phase 2).
- **Mesh and scene file formats** (GTS read/write, PLY/OBJ/RAW read) are now present in
  `org.almostrealism.space.format`, useful for verification fixtures and mesh export (§4.4).
- **The photon-absorber path** moved into `common` with several bug fixes (`Plane.intersect` now
  reports hits); it remains an alternative render path, not the fluid path (§4.2).
- **Batched camera rays** are a verified known issue (`CameraRayIntersectionTest#kernelMatchesPerPixel`)
  bearing on Phase 4 and any per-pixel kernel path (§4.5, §7 Phase 4, §9).
- **A GLSL / per-pixel rendering option** is folded in as a concrete form of §6.2 Option C — a
  possibility to weigh, not the recommendation (§6.2, §7, §9, §11).

The recommendation (height-field mesh through the existing ray tracer) is unchanged; the owner
decisions in §6 and §11 are updated but left open.

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
| R11 | Globally-coupled time-stepped loop | **PRESENT** | — | — | `SimulationRunner` (`compute/time`, `org.almostrealism.time`) owns the loop, extends `TemporalRunner`; a system implements `Temporal.tick()` as a Producer step. Well-tested: `SimulationRunnerTest` (`engine/utils`) covers element-local, **coupled** (`dampedOscillatorCoupledState`: `matmul` + copy-back), boundary input, step counter, lifecycle, reset, and a 12000×12000 `largeDenseStep` at `@TestDepth(2)`. Contract documented in [`docs/internals/simulation-loop-examples.md`](../internals/simulation-loop-examples.md). Already delivered by the completed [`PLAN-20261025-simulation-loop-owner.md`](PLAN-20261025-simulation-loop-owner.md) — this plan builds on it and proposes no loop-owner work. |
| R12 | Large device-resident arrays | **PRESENT** | — | — | `PackedCollection` (`base/collect`); indexing bounded by `int` length (~2.1e9), real limit is device memory via `AR_HARDWARE_MEMORY_SCALE`. 128³ grid (~2M) and 10⁶ particles are well within range. `largeDenseStep` exercises ~0.55 GB. |
| R20 | GPU/CPU backends, kernel compile/dispatch | **PRESENT** | — | — | `base/hardware`: native (JNI), OpenCL, Metal (`libMTL`), CUDA (NVRTC). Signature-cached compilation; `ComputeRequirement.GPU/CPU` steering; Metal command-buffer batching. `base/hardware/README.md`. |
| R21 | Determinism across backends | **IMPROVE** | No | S | No cross-backend determinism contract found; Metal is FP32, native can be FP64, shared-memory mode forces FP32. Reproducibility requires pinning precision + backend. Adequate for within-backend comparison; document and pin for validation. |
| R14 | FFT for spectral Poisson | **IMPROVE** | No* | M–L | `FourierTransform` (`compute/time`) is a real compiled radix-2/4 FFT but **1-D only, CPU only** by default: `FourierTransform.enableRecursion` defaults to `true`, and the generated recursive kernel is not supported on the GPU — recorded by the test `FourierTransformTests#compileGpu` (`engine/utils`), which throws `UnsupportedOperationException("Recursion is not supported on the GPU")` while `enableRecursion` is set (skipped unless known issues are enabled). The production class itself has no GPU guard; the limit is known only from that test. No 2D/3D transform and no Poisson solver exist. *Only blocks if a spectral pressure solve is chosen; an iterative Jacobi/multigrid solve avoids it. |
| R13 | Device neighbor search (spatial hash / cell list) | **MISSING** | Yes* | L | Repo-wide search finds no spatial hash, cell list, uniform-grid binning, or GPU k-d tree. The only spatial structure is `KdTree` (`domain/graph`), **host-Java**, used by `RayField` only; not a Producer. *Blocks only the particle (SPH) method; a grid/height-field method avoids it. |
| — | Particle substrate | **MISSING** | No | — | `ParticleGroup` (`compute/algebra`) is a one-method **host** interface (`double[][] getParticleVertices()`). It is implemented — `Triangle`, `Plane` and `RigidSphere` (`domain/space`) return fixed vertex/corner coordinates as host `double[][]` — but those are static geometry-vertex accessors, **not** a device-resident, time-stepped particle state (`Sphere` itself carries only a `// TODO Add ParticleGroup implementation`). Not a simulation substrate; do not build on it. |

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
| R17 (render input) | PhotonField / Absorber path | **PRESENT** (legacy) | No | — | `PhotonField`/`Absorber`/`VolumeAbsorber` + `DefaultPhotonField` (`engine/render`) are a working **host-Java CPU** photon simulation with tests (`DefaultPhotonFieldTest`, `AbsorberHashSetTest`, `PhotonFieldContextCloneTest`). The photon-absorber **library** parts of the rings repository's `ar-photonfield` module have now been migrated into `common` (per `RINGS_PHOTONFIELD_MIGRATION.md`): the BRDFs in `org.almostrealism.space.distribution` (`DirectionDistribution` base + `UniformHemisphericalDistribution`, `ReflectiveProbabilityDistribution`, `RefractiveProbabilityDistribution`, `OverlayBRDF`), `SphericalVolume`/`BoxVolume`/`SpecularAbsorber` in `org.almostrealism.primitives`, and `PhotonFieldAnimation` in `engine/render`; the move fixed real bugs (Snell-law refraction, `SphericalVolume` miss distance, `BoxVolume` wall offsets, `SpecularAbsorber` emission, and **`Plane.intersect`**, which now reports hits instead of missing every face). Relevant only as an **alternative render path**, not for the fluid; not on the ferrofluid critical path (see §5). |

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
| R15/R16 (verification) | Mesh / scene file I/O (GTS, PLY, OBJ, RAW) | **PRESENT** | No | — | `org.almostrealism.space.format` (migrated from the rings raytracer, per `RINGS_RAYTRACER_MIGRATION.md`) holds GTS (**read and write**), PLY, OBJ and RAW readers, plus `FileDecoder`/`FileEncoder`, `FileSystemSceneLoader`, `MeshFile` and `ModelData`; covered by `MeshFormatTest` (`engine/utils`). Not on the critical path, but a **verification aid**: a reconstructed height-field `Mesh` can be written to GTS for inspection or diffing against an external tool, and reference meshes can be loaded as test fixtures. |
| R16 | Acceleration structure (BVH/octree) | **MISSING** (BSP stubbed) | Yes | L | `SpacePartition` BSP construction works but **traversal returns null** (`intersectAt` stub; `SpacePartitionTest` asserts null), and `Mesh/Triangle.calculateBoundingSolid` **throw**. Intersection today is an **unaccelerated linear scan** over all triangles — will not scale to a spiky surface. |
| R16 | GPU mesh intersection path | **IMPROVE** | No | M | `CachedMeshIntersectionKernel` runs the triangle kernel on-device but loops rays on the host (the `MeshData.enablePartialKernel` flag, default `true`) and reads the cache back per pixel with `toDouble`. Works; not yet a single kernel. |
| — | CSG / primitives | **IMPROVE** | No | — | `CSG` supports UNION only (host Java); DIFFERENCE/INTERSECT throw. `Sphere`/`Plane` usable for scaffolding. Not on the critical path. |

### 4.5 Rendering (engine/render, domain/color)

Primary working path: `RayTracedScene` → `RayTracer` → `RayIntersectionEngine` → per-surface/light
`IntersectionalLightingEngine` aggregated by `LightingEngineAggregator`. It is a **hybrid**:
per-ray intersection and the color source are compiled, but ranked-choice surface selection and
color combine run as **host-Java per-pixel loops**. **No CI test renders and asserts a lit image in
the default lanes** — the end-to-end render tests are `@TestProperties(knownIssue = true)` and
excluded from the pipeline (`SimpleRenderTest`, and now also `ReflectionRefractionRenderTest`, which
asserts a shaded disc is not entirely black but currently fails while building the producer). Only
single-ray camera and intersection-distance unit tests run in the default lanes
(`CameraRayIntersectionTest.centreHitsAndCornerMisses` passes; its batched `kernelMatchesPerPixel` is
`knownIssue`).

| Requirement | Capability | Status | Blocks? | Size | Evidence |
|---|---|---|---|---|---|
| R16 | Ray-trace entry point (intersection) | **PRESENT** (hybrid) | — | — | `RayTracedScene`/`RayIntersectionEngine`; compiled per-ray intersection, host-loop orchestration. Single-ray camera-ray intersection is tested and passes (`CameraRayIntersectionTest.centreHitsAndCornerMisses`, `engine/render`). But **evaluating the camera-ray producers over a batch of pixels in one kernel is a known issue** (`CameraRayIntersectionTest#kernelMatchesPerPixel`, `knownIssue`): it fails "Could not obtain operator", caused by an `IllegalArgumentException` from `TraversalPolicy#subset` when the ray direction takes a one-dimensional subset of the two-dimensional pixel batch. This bears on kernelizing the per-pixel path (§7 Phase 4) and on the GLSL route-1 experiment (§6.2). |
| R17 | Diffuse / specular / ambient lighting | **PRESENT** | — | — | `LightingEngine` builds a Producer lighting graph; `DiffuseShader`/`HighlightShader`/`AmbientLight`/`PointLight`. A dark glossy base is achievable today with diffuse + highlight. |
| R17 | Glossy Fresnel reflection | **IMPROVE** (broken) | Yes | L | `ReflectionShader` has a correct Schlick/Fresnel Producer structure, but the new `ReflectionRefractionRenderTest` (`engine/render`, `knownIssue`) shows reflection fails **earlier than the previously-named `getAccelerated()` gap**: while `RayTracedScene.realize` builds the producer (before any pixel is evaluated), the reflected ray's lighting aggregator intersects it with the floor and `RayFeatures.origin` throws **"Subset cannot be performed without a TraversalPolicy"** — the reflected-ray producer carries no shape. The `LightingEngineAggregator.getAccelerated()` gap is reached only after that: `RankedChoiceEvaluableForMemoryData.getAccelerated()` is verified to still **throw `UnsupportedOperationException`** (its base implementation). So a working glossy reflection needs *both* the reflected-ray shape fixed *and* an accelerated aggregation path — the main shading blocker, and larger than one fix. |
| R17 | Refraction / Fresnel rim | **IMPROVE** (non-functional) | No | L | `RefractionShader` is class-level `// TODO Fix refraction algorithm`; a host-Java lambda. The new `ReflectionRefractionRenderTest` (`knownIssue`) shows it fails in `RayTracedScene.realize` too: `GeneratedColorProducer.getShape` throws a `ClassCastException` casting the lambda `RefractionShader` returns to `io.almostrealism.collect.Shape`. Minor for a near-opaque fluid; a thin Fresnel rim is a nicety. |
| R15 (alt) | Ray-march / SDF engines | **MISSING** (stubbed) | No* | L | `RayMarchingEngine.trace()` returns null (`// TODO`; its test asserts null); `DistanceEstimationLightingEngine` passes null to super with the march loop commented out. *Only relevant if the implicit route is chosen. |
| R18 | Camera / ray generation | **PRESENT** | — | — | `PinholeCamera.rayAt` via `ProjectionFeatures` (compiled); `ThinLensCamera` present. |
| R18 | Anti-aliasing / supersampling | **IMPROVE** | No | M | `SuperSampler`/`Pixel` average an N×M grid correctly but as a host-Java loop (`toDouble`/`addTo` per pixel). Correct, slow. |
| R18 | Image file output (PNG) | **PRESENT** | — | — | `ImageCanvas.encodeImageFile` and `RGBFeatures.saveRgb` both emit PNG via `ImageIO`. |
| R19 | Animation / frame-sequence driver | **IMPROVE** (reference only) | Yes | S–M | `RayTracedAnimation` (`engine/render`, migrated from the rings raytracer) now renders a scene **frame by frame** to image files: its `writeImage` ray-traces each frame with `RayTracedScene.realize().get().evaluate()`, writes it via `ImageCanvas.encodeImageFile(..., ImageCanvas.JPEGEncoding)` to `frame_<instance>.<i>.jpeg`, and emits an `encode.sh`. (Despite the name and extension, `ImageCanvas`'s `JPEGEncoding` branch writes **PNG** bytes through `ImageIO.write(bimg, "png", ...)`, so the frames are PNG files with a `.jpeg` suffix — harmless for most decoders, but a driver reusing this stage should name its output honestly.) So the per-frame render→save plumbing exists and is a useful **reference**. But it is **not a drop-in** ferrofluid driver: it extends `space.Animation`, whose `run()` **steps the physics itself** (rigid-body `State.update(dt)`, force application and impulse collisions each iteration, calling `writeImage` every *iterationsPerFrame*). §10 requires the outer frame loop *not* to drive the physics step — the fluid step belongs to `SimulationRunner` — so the ferrofluid frame driver must adapt this pattern, not inherit `Animation.run()`. (Two further caveats found in the source: the in-loop `clock.tick()` advance is commented out, so `Animation.run()` never advances its `Clock`; and `generateProperties` writes `bodies.length` from `size()`, counting non-serializable surfaces. The `camera.foclen` round-trip, by contrast, is fixed.) |
| — | Photon-mapping renderer | **IMPROVE** (dead) | No | — | `AbsorberHashSet` + `DefaultPhotonField` form a separate photon renderer referenced only by its own tests; not wired into `Engine`. Candidate for removal/relocation (see §5). |

---

## 5. Cleanup the engines need (and why)

The task asks specifically what cleanup the ray-tracing and physics engines need. Reported from
what the code actually is, not assumed:

**Ray tracer (engine/render).** Tie each item to the goal:

1. **Reflection is broken in two places, not one.** The new `ReflectionRefractionRenderTest`
   (`engine/render`, `knownIssue`) pins the failures and is the concrete target to turn green.
   (a) **Reflected ray has no shape:** while `RayTracedScene.realize` builds the producer, the
   reflected ray's lighting aggregator intersects it with the scene and `RayFeatures.origin` throws
   "Subset cannot be performed without a TraversalPolicy". This is the *first* failure and must be
   fixed before the next is even reached. (b) **`RankedChoiceEvaluableForMemoryData.getAccelerated()`
   throws `UnsupportedOperationException`** — the advertised GPU aggregation path, reached once (a)
   is fixed; it must be implemented as a compiled kernel (or the throw and its callers removed).
   *Why it matters:* glossy reflections (R17) are the defining look of ferrofluid and need both
   fixes. **Blocks the glossy look.** Size L (two gaps, not one).
2. **Host per-pixel orchestration** — `RankedChoiceEvaluable.evaluate`, `LightingEngineAggregator.evaluate`,
   and `SuperSampler` select/combine per pixel on the host. *Why:* a frame sequence (R19) at useful
   resolution needs the selection+combine in a compiled kernel, or render time dominates. Improves,
   does not block a first slice. Size M–L.
3. **`RefractionShader` rewrite** — non-functional host-Java (`// TODO Fix refraction algorithm`).
   `ReflectionRefractionRenderTest` shows it fails in `RayTracedScene.realize` with a
   `ClassCastException` from `GeneratedColorProducer.getShape`, casting the lambda `RefractionShader`
   returns to `io.almostrealism.collect.Shape`. *Why:* only a thin Fresnel rim is wanted for a
   near-opaque fluid; rewrite as a Producer graph (one that carries a shape) or scope it out.
   Improves. Size L.
4. **Dead/stubbed engines** — `RayMarchingEngine` and `DistanceEstimationLightingEngine` are stubs
   (null/commented-out); the `AbsorberHashSet`/`DefaultPhotonField` photon renderer is unwired.
   *Why:* remove or clearly quarantine so the one working path is unambiguous; keep the SDF engines
   only if the implicit-surface route (§7) is chosen. Size S–M.
5. **No CI render assertion** — the end-to-end render tests are `knownIssue`-excluded. The migration
   added `ReflectionRefractionRenderTest` (`engine/render`), which renders a sphere carrying only a
   `ReflectionShader` or only a `RefractionShader` over a lit floor and asserts the disc is not
   entirely black — exactly the asserting render test this item calls for, already written and
   currently `knownIssue` (run with `AR_LONG_TESTS=enabled AR_KNOWN_ISSUES=enabled`). *Why:* once
   item 1 (reflection) / item 3 (refraction) are fixed, clearing its `knownIssue` flag restores a CI
   render assertion so regressions are caught. Size M (gated on items 1 and 3). *This is additive —
   clearing a `knownIssue` on a test that already asserts, never weakening an existing one.*

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
  - **Option C, GLSL / per-pixel form (`GLSL_RENDER_OPTION.md`).** A concrete way to realize C: write
    the whole renderer as one small per-pixel program — build the camera ray from the pixel, march
    it against the SDF, shade the hit with lights, a Fresnel term and an environment lookup. The
    height field fits this especially well, since `h(x, z)` is the implicit surface `y − h(x, z) = 0`
    that a ray marcher can step against with no meshing, and the glossy look comes from shading code
    in the same program, so it does **not** depend on the broken `ReflectionShader` path. Two routes:
    **(1) per-pixel producer-graph kernels on the existing backends** (Metal/OpenCL/native) — no new
    backend or dependency, runs in CI wherever kernels run, output is a `PackedCollection` image the
    existing writers handle; a fixed march-step count must be unrolled or looped in the graph, and
    whether such a graph compiles cleanly is *unverified* (note the batched camera-ray known issue in
    §4.5, which a marcher computing ray directions directly from pixel coordinates would sidestep but
    has not been shown to avoid). **(2) a new GLSL code-generation backend** following the Metal
    backend and `base/hardware-js` — modest on the text side, but single-precision, would not run in
    CI, and a **new Maven module needs the owner's approval** (agents must not create one). *Pros:*
    the quickest route to a moving, glossy picture; interactive viewing (route 2); avoids both
    blocked shading paths; route 1 is reusable and could later be emitted as GLSL. *Cons:* a second
    render path (which this plan already counts against Option C); single precision; GLSL execution
    outside CI (route 2); the recommended meshing path reuses more working, tested code.

**Recommendation:** **A** for the slice (height-field tessellation + the existing mesh
intersection), **B** (marching cubes) as the general reconstruction capability alongside fluid
Option B. Prefer meshing (A/B) over SDF (C) because the triangle intersection path already works and
is tested, whereas the ray-march path is entirely stubbed. If **B** is chosen, the **acceleration
structure (R16) becomes mandatory**, since isosurface meshes of a spiky surface are large. Keep the
**GLSL / per-pixel form of C open as an optional experiment** (§7): its appeal is reaching a first
moving, glossy picture sooner and sidestepping the broken `ReflectionShader`; it is not promoted to
the recommendation because it is a second render path, is single-precision, and (route 2) would not
run in CI — but route 1 is a cheap, reusable comparison to run once the height field exists.

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
Make `ReflectionShader` work. This is **two fixes, not one** (§5 item 1): first give the reflected
ray producer a shape so `RayTracedScene.realize` stops throwing "Subset cannot be performed without a
TraversalPolicy", then implement `RankedChoiceEvaluableForMemoryData.getAccelerated()` (still throwing)
as a compiled kernel. Assemble a dark, glossy, near-opaque material with environment/lights;
supersampling. *Demonstrable:* a convincing still of the Phase 1 spikes with glossy Fresnel reflection.
*Verifiable:* reflection is present and correct (mirror a known environment); clear the `knownIssue`
flag on the already-written `ReflectionRefractionRenderTest` (`engine/render`) so a CI render
assertion runs. *Size note:* larger than the original single-`getAccelerated()` estimate, because the
realize-stage reflected-ray failure must be fixed first.

**Phase 3 — Animation (frame sequence).**
Add the frame-sequence driver (step → reconstruct → render → save per frame). `RayTracedAnimation`
(`engine/render`) is a **reference** for the per-frame render→save plumbing (it already ray-traces
each frame and writes image files via `ImageCanvas.encodeImageFile` plus an encode script), but it
must **not** be inherited wholesale: its base `Animation.run()` steps the physics itself, which §10
forbids of the outer frame loop (the fluid step belongs to `SimulationRunner`). The driver reuses
`RayTracedAnimation`'s render/write stage while keeping the physics in the simulation loop.
*Demonstrable:* a short clip of spikes forming as the field ramps up. *Verifiable:* N frames on disk,
monotonic field schedule, deterministic within a pinned backend/precision.

**Phase 3 (optional experiment) — GLSL / per-pixel route-1 comparison.**
Once the height field exists, render the same surface the mesh path renders as a per-pixel
producer-graph kernel (§6.2 Option C, route 1) and compare the two pictures and their cost. This is
an optional, low-commitment experiment — kept because it may reach a moving, glossy picture sooner
and sidesteps the blocked `ReflectionShader` path; it is not a replacement for the recommended mesh
path. Mind the batched camera-ray known issue (§4.5) when building the per-pixel graph. A GLSL
*backend* (route 2) is out of scope unless interactive viewing becomes a goal and the owner approves
a new module.

**Phase 4 — Scaling and acceleration (make it usably fast / higher resolution).**
Add a triangle acceleration structure (R16) and move the ray-trace orchestration + supersampling
into compiled kernels (ray-tracer cleanup items 1–2). Moving the per-pixel orchestration into a
single kernel must contend with the **batched camera-ray known issue** (`CameraRayIntersectionTest#kernelMatchesPerPixel`,
§4.5): evaluating the camera-ray producers over a pixel batch fails today in `TraversalPolicy#subset`,
so this gap is a prerequisite for a fully kernelized per-pixel render (and for GLSL route 1).
*Demonstrable:* the same clip at higher resolution / larger grid at materially better frame time.
*Verifiable:* measured intersection scaling vs. the linear-scan baseline; frame time budget met at a
stated resolution.

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
  geometrically correct angle); an asserting CI render test — concretely, the already-written
  `ReflectionRefractionRenderTest` (`engine/render`) with its `knownIssue` flag cleared once the
  reflection fixes land (it asserts the shaded disc is not entirely black); no `knownIssue` exclusion
  of that render assertion.
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
  is an unknown until measured. A full kernelization of the per-pixel path is additionally gated on
  the **batched camera-ray known issue** (`CameraRayIntersectionTest#kernelMatchesPerPixel`,
  `TraversalPolicy#subset`), which must be fixed for a single-kernel render.
- **Glossy reflection is deeper than first stated.** The defining look (R17) is blocked by *two*
  failures, not just the `getAccelerated()` throw: the reflected-ray producer also lacks a shape and
  fails earlier in `RayTracedScene.realize`. Phase 2 is correspondingly larger, and the risk is that
  the realize-stage producer construction hides further issues once the first is fixed.
- **GLSL / per-pixel option trade-off.** The GLSL / per-pixel form of surface Option C
  (`GLSL_RENDER_OPTION.md`) could reach a moving, glossy picture sooner and avoid the blocked
  `ReflectionShader` path, but it introduces a second render path, is single-precision, and (as a
  dedicated backend) would not run in CI and would need a new module. Its route-1 (producer-graph)
  form is the low-risk way to keep it open as an experiment; whether such a graph compiles cleanly is
  *unverified*.
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
6. **The rings repository (answered).** When this plan was written, the rings repository's
   `ar-raytracer` and `ar-photonfield` modules were not in the checkout and could not be inspected.
   They have since been **inventoried, and their library parts migrated into `common`** on master
   (recorded in `RINGS_RAYTRACER_MIGRATION.md` and `RINGS_PHOTONFIELD_MIGRATION.md`). What landed
   that is relevant here: the mesh/scene file formats (`org.almostrealism.space.format`, §4.4), the
   frame-sequence driver `RayTracedAnimation` (§4.5 R19), the photon-absorber path
   (`space.distribution` BRDFs, `primitives` volumes/`SpecularAbsorber`, `PhotonFieldAnimation`,
   §4.2), and two render tests: `ReflectionRefractionRenderTest` (both methods `knownIssue`) and
   `CameraRayIntersectionTest` (only `kernelMatchesPerPixel` is `knownIssue`;
   `centreHitsAndCornerMisses` runs and passes). The ferrofluid render therefore uses **this** repository's ray
   tracer, now including the migrated pieces; there is no external rings-based renderer to assess.
   What stayed in rings is UI, demos, one-offs, and the distributed ray-tracing jobs (deferred,
   not needed for ferrofluid). **New question for the owner:** the frame-sequence driver (R19) can
   either build on the migrated `RayTracedAnimation`/`Animation` (which currently steps rigid-body
   physics in its own `run()` loop, with a commented-out `clock.tick()` advance — a pattern §10
   forbids for the fluid step) or be a fresh `SimulationRunner`-driven driver that reuses only
   `RayTracedAnimation`'s render/write stage. Which does the owner prefer, and is reworking
   `Animation.run()` itself in scope or strictly out of it?
7. **Validation strictness (§8):** what tolerances are acceptable for the Phase 1 physical checks
   (threshold field, spike wavelength), and is a within-backend (FP32-pinned) determinism guarantee
   sufficient, or is cross-backend agreement required?
8. **Performance target (§9):** what resolution and frame-time budget define "useful" for the
   demonstration?
9. **GLSL / per-pixel rendering option (§6.2, `GLSL_RENDER_OPTION.md`):** should the per-pixel
   producer-graph form of surface Option C (route 1) be run as an optional experiment once the
   height field exists (it may reach a moving, glossy picture sooner and sidesteps the broken
   `ReflectionShader` path), and is a dedicated GLSL code-generation backend (route 2 — a **new
   module**, single-precision, not CI-exercised) of interest only if interactive viewing becomes a
   goal? The recommendation keeps the height-field mesh through the existing tracer as the primary
   path; this question is only about whether to keep the GLSL route open.

---

## 12. Summary

The platform already provides the hard-to-build foundations: a tested, loop-owning
`SimulationRunner` with a coupled-step contract that fits a fluid update; a multi-backend GPU
execution model; large device-resident arrays; a working compiled ray–triangle intersection core; a
dynamically buildable mesh; and working PNG output. What is missing is the **domain middle**: all of
the magnetics and fluid physics, free-surface tracking and a time integrator, **surface
reconstruction** (the single largest upstream gap), a **triangle acceleration structure**, and
working **glossy reflection** (two gaps: the reflected-ray producer's missing shape and the
`getAccelerated()` throw). A **frame-sequence driver** is now partly in place — `RayTracedAnimation`
renders frame by frame, but its base `Animation.run()` drives the physics itself, so a
`SimulationRunner`-honoring driver must reuse its render stage rather than inherit it. The chemistry
tools cannot honestly supply bulk material properties, which are therefore continuum inputs.

The recommended path minimizes new infrastructure and surfaces integration risk early: a 2D
height-field fluid with an analytic field and height-field tessellation, rendered through the
existing (reflection-fixed) tracer, proven against the analytic Rosensweig threshold and wavelength —
then deepened toward a general 3D fluid and isosurface renderer only if the owner wants that
generality. Every phase leaves a reusable capability behind, which is the point: ferrofluid is the
means of finding and closing these gaps, and the framework is stronger for each one closed.
