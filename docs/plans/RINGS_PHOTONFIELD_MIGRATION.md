---
status: in-progress
created: 2026-11-08
closed:
tracker: []
---

# Migrating the rings `photonfield` module into common

**Scope:** the `photonfield` module of the rings repository (`ar-photonfield`), 42 main classes and
4 tests. It follows the `raytracer` migration (`RINGS_RAYTRACER_MIGRATION.md`) under the same rule:
library code moves to common; UI, demos and one-offs stay.

`photonfield` is mostly the Swing editor for the photon-absorber simulator. None of it is on the
ferrofluid critical path. The ferrofluid plan treats the photon simulation as an alternative render
path, not the one it uses. The library pieces were moved for the general rule. The one item
relevant to ferrofluid is the reflection and refraction render test.

## Status

| Classes | Decision | Status |
|---|---|---|
| `UniformHemisphericalDistribution`, `ReflectiveProbabilityDistribution`, `RefractiveProbabilityDistribution`, `OverlayBRDF` | Move to `ar-space`, package `org.almostrealism.space.distribution`, on a new base class `DirectionDistribution` | **Done** |
| `geometry.Sphere` (a volume) | Move to `ar-space` as `primitives.SphericalVolume` | **Done** |
| `geometry.Box` | Move to `ar-space` as `primitives.BoxVolume` | **Done** |
| `SpecularAbsorber` | Move to `ar-space`, package `org.almostrealism.primitives` | **Done** |
| `PhotonFieldAnimation` | Move to `ar-render`, package `org.almostrealism.render` | **Done** |
| `CausticTest` | Port to `ReflectionRefractionRenderTest` in `engine/render` | **Done** |
| `LampTest` | Delete: its only line was commented out | **Done** |
| UI: `photon/ui`, `photon/ui/load`, `photon/xml` | Stay in rings | — |
| Demos: `BlackBodyExperiment`, `HarmonicAbsorberExperiment`, `TestRoom`, `RandomLights` | Stay in rings | — |
| Distributed jobs: `PhotonFieldJob`, `PhotonFieldServer`, `ExtractImageClient`, `DefaultSceneLoader`, and `FileLoader` | Stay in rings, deferred with raytracer's group 2 | — |

The distributions need `Length` and `CodeFeatures`, which are above `ar-stats`, so they live in
`ar-space` rather than beside `SphericalProbabilityDistribution`. The package is new instead of a
second `org.almostrealism.stats`, so that `ar-stats` keeps the only documentation for that package.

`FileLoader` is a SAX loader for an XML absorber-set format. No file in that format exists in any
of the repositories, and its only caller is `PhotonFieldJob`, whose `run()` always throws because
its resource loading is commented out.

## What changed in the move

Tests: `DirectionDistributionTest`, `AbsorberVolumeTest` and `SpecularAbsorberTest` in
`engine/utils` (package `org.almostrealism.primitives.test`), and `PhotonFieldAnimationTest` in
`engine/render`.

- **Refraction** did not follow Snell's law: at 30 degrees into an index of 1.5 it bent the ray to a
  sine of 0.17 instead of 0.33, because the tangent it added was not normalized. It is rewritten,
  and reflects beyond the critical angle instead of returning NaN.
- **`SphericalVolume`:**
  - A ray that misses now returns the miss distance; it used to return NaN.
  - Surface and spatial coordinates are now inverse latitude and longitude; before, they did not
    match, and the surface coordinates ignored the radius.
- **`BoxVolume`:**
  - It kept its walls in a `HashSet` but paired each with an offset by iteration order, so walls
    got the wrong offsets; it now pairs each wall with its own offset.
  - The offsets used fixed axes whatever the box's orientation, and two side walls had their width
    and height swapped. The walls are now built from the normal and orientation.
  - `getNormalAt` ignored the offsets; it now uses the nearest wall.
  - Surface coordinates, which returned null and would have failed in `AbsorberHashSet`, now lay
    the six walls side by side.
  - The walls are rebuilt when a property changes. The `makeWalls(boolean)` call is gone, along
    with its parameter, which was an assignment (`complex = true`) that had no effect.
- **`Plane.intersect`** (already in common) checked each face hit at a point half a thickness
  *outside* the slab, so it never reported a hit. It now checks the point half way back towards
  the middle of the slab. This affects `AbsorberHashSet`'s distance queries and
  `PinholeCameraAbsorber`; `AbsorberHashSetTest`, `DefaultPhotonFieldTest`,
  `DefaultPhotonFieldConfigTest` and `AbsorptionPlaneGeometryTest` still pass.
- **`SpecularAbsorber`:**
  - `getEmitEnergy` cast each queued photon (an `Object[]`) to `double[][]`, so every emission
    threw. Photons are now typed.
  - It queued photons in the deprecated `org.almostrealism.utils.PriorityQueue`, whose comparator
    treats photons due at the same time as equal (so the `TreeSet` behind it dropped all but one)
    and overflows for gaps above about 0.2. It now uses `java.util.PriorityQueue`.
  - `setAbsorbDelay` set one absolute time for every later photon; the delay is now relative to
    each photon's arrival.
  - The colour range is documented in nanometres but was compared with wavelengths in
    micrometres, so any range discarded every photon. It now converts.
  - Without a volume it absorbs nothing. It used to absorb everything and then fail on emission,
    which needs a surface normal.
  - The demo `main()` and the random console output are gone.
- **`PhotonFieldAnimation`:**
  - `remove(int)` removed the member from the absorber set with `remove`, which finds nothing
    there; it now uses `removeAbsorber`.
  - `retainAll` was a stub, and `set`, `removeAll`, `removeIf` and the bulk additions now all keep
    the absorber set in step.

## `ReflectionRefractionRenderTest`

The only test that renders `ReflectionShader` and `RefractionShader`, which the ferrofluid plan's
rendering-quality phase depends on. A sphere carrying only one of the two shaders is rendered
over a lit diffuse floor, and the test asserts that its disc is not entirely black. Both cases are
`knownIssue`; run them with `AR_LONG_TESTS=enabled` and `AR_KNOWN_ISSUES=enabled`. Both fail while
`RayTracedScene.realize` builds the producer, before any pixel is evaluated:

- **Reflection:** the reflected ray's lighting aggregator intersects it with the floor, and
  `RayFeatures.origin` throws "Subset cannot be performed without a TraversalPolicy": the
  reflected ray producer has no shape. This is reached before the
  `LightingEngineAggregator.getAccelerated()` gap that the ferrofluid plan names.
- **Refraction:** `GeneratedColorProducer.getShape` throws a `ClassCastException` casting the
  lambda that `RefractionShader` returns to `Shape`.

The rings test it replaces also added its `ReflectionShader` and then cleared the shaders, so it
never rendered a reflection.

## After photonfield

The other rings modules hold nothing for ferrofluid. `gl` is a JOGL fixed-function renderer
with stub GLSL support; `GLSL_RENDER_OPTION.md` records GLSL-style per-pixel rendering as an
option for the ferrofluid work, and explains why none of `gl` would be moved for it.
