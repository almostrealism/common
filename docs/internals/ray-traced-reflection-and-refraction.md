# Ray-Traced Reflection and Refraction

This guide describes how `engine/render` traces secondary rays — mirror and glossy
reflection, and refraction through transparent solids — and the API a scene uses to get
them. Everything on this path is a `Producer` graph: a reflection or refraction compiles into
the kernel of the surface being shaded, and the closest hit along a secondary ray is selected
on the device.

## The render path

```
RayTracedScene.realize(params)
  └─ RayTracedScene.getProducer: camera.rayAt(v(shape(-1, 2), 0), pair(w, h))   // one ray per pixel
       └─ RayIntersectionEngine.trace(ray)
            └─ LightingEngineAggregator(ray, surfaces, lights, context, kernel = true)
                 ├─ one IntersectionalLightingEngine per (surface, light) pair
                 │    rank  = surface.intersectAt(ray).getDistance()
                 │    color = shadow × light.lightingCalculation(surface, context) → surface.shade(context)
                 │                                                 → each Shader.shade(context, intersection)
                 └─ initRankCache: every rank evaluated for every pixel in one kernel,
                    then the closest engine's colour is evaluated for each pixel
```

A shader that needs a secondary ray builds a new `LightingEngineAggregator` for that ray
inside its own `shade` method and embeds the aggregator's closest-hit colour in the colour it
returns.

### The pixel input is variable-count

`RayTracedScene.getProducer` declares the pixel position as `v(shape(-1, 2), 0)`: a
variable-count input, so one compiled graph serves both the rank kernel over every pixel and
the evaluation of a single pixel. Every value computed from it (a ray, a hit distance, a hit
point) is therefore a producer with a fixed shape but a variable count. Two consequences:

- A fixed-count pixel input (`v(shape(width * height, 2), 0)`) does not work: the camera ray
  takes 1-D subsets of a 2-D batch and fails in `TraversalPolicy.subset` while compiling the
  rank kernel ("Could not obtain operator").
- Broadcasting a smaller operand against a larger one relies on
  `CollectionTraversalAxisAlignment.expand`, which repeats an operand whose *shape* is fixed
  even when its count is variable. `direction(ray).multiply(distance)` — a `(3)` by a `(1)` —
  is the typical case. Operands whose own shape is variable (such as the pixel input itself)
  are not repeated unless `CollectionTraversalAxisAlignment.enableVariableRepeat` is set.
- A computation that reads a per-item operand must index it by item. In the rank kernel the
  output index runs over every pixel, so `TransformMatrixFeatures.transform` reads the vector
  of item `i / 3` and row `i % 3` of the shared matrix for output element `i`; reading the
  vector at a constant offset would give every pixel the rank of pixel 0.

## Closest-hit selection on the device

`RankedChoiceEvaluable.evaluate` picks the candidate with the smallest rank that is at least
the epsilon threshold by evaluating every rank on the host. The same selection as a graph is
`RankedChoiceEvaluableForMemoryData.getClosestChoice(int memLength)`:

- each candidate's key is `-rank` when `rank >= e`, otherwise a large negative miss key;
- the selection starts from a blank (all zero) value whose key lies between the two, so it is
  kept only when nothing is hit;
- an element-wise fold over the candidates replaces the selected value with a candidate's
  only when that candidate's key is strictly greater (so the first one wins a tie, like
  `evaluate`), and carries the larger key forward.

Every step is element-wise, so over a batch each item (each pixel of a render) selects from
its own ranks and values. An index-of-maximum followed by a `Choice` would not: `Choice`
reads a single decision for the whole output, so every pixel would take the first pixel's
selection.

`RankedChoiceEvaluableForRGB.getClosestColor()` is `getClosestChoice(3)`, and
`LightingEngineAggregator` inherits it. `getAccelerated(memLength, postprocessor)` compiles the
same graph into an `Evaluable`, converting its output with the given post-processor (for
example `Vector.postprocessor()`); `RankedChoiceEvaluableForRGB.getAccelerated()` and
`RankedChoiceEvaluableForVector.getAccelerated()` are the 3-element forms. With no candidates
the choice is a zero collection.

## Reflection

```java
ReflectionShader mirror = new ReflectionShader(0.9, white());  // reflectivity R0, tint
mirror.setBlur(0.0);                                            // 0 = perfect mirror
sphere.setShaders(new Shader[] { mirror });
```

`ReflectionShader.shade` builds, for each shaded side of the surface,

1. a `ReflectedRay(point, viewer, normal, blur)`: a shape-`(6)` computation (origin then
   direction, the layout of a `Ray`) whose direction is
   `GeometryFeatures.reflect(viewer, normal, blur)`;
2. a `LightingEngineAggregator` over the other surfaces (front side) or all surfaces (back
   side) for that ray, whose `getClosestColor()` is the colour seen in the mirror. The
   aggregator is given only the context's current light, the light the shader is invoked
   for, so it builds one candidate per surface;
3. that colour weighted by the tint and by Schlick's approximation
   `R0 + (1 - R0)(1 - cos(theta))^5`, with `cos(theta)` the cosine between the viewer and the
   normal on the shaded side.

The result is multiplied by the light's colour at the point, as for every per-light shader.
Recursion is bounded by `ReflectionShader.maxReflections`: past that many bounces of the
`ShaderContext`, the shader returns the reflective colour times the surface colour.

`blur` perturbs the reflected direction by a uniformly distributed amount in
`[-blur / 2, blur / 2)` along two axes perpendicular to the normal, using `rand(2)`.

### Direction conventions

`GeometryFeatures.reflect(vector, normal)` takes the vector from the surface **toward the
viewer** — the negation of the incident direction of travel — and returns the reflected
direction of travel. `ShadableIntersection.getNormalAt(point)` returns exactly that viewer
vector (`-normalize(incident)`), and `direction(intersection.get(0))` is the surface normal.

## Refraction

```java
RefractionShader glass = new RefractionShader(1.5);   // index of refraction
glass.setAttenuationFactors(0.95, 0.95, 0.98);        // per-channel transmission inside
sphere.setShaders(new Shader[] { glass });
```

`RefractionShader` treats the surface as the boundary of a closed solid of the material:

1. `GeometryFeatures.refract(viewer, normal, index)` refracts the ray into the material. It
   applies Snell's law with relative index `1 / index` when the viewer is on the side the
   normal points to and `index` otherwise, and returns the mirror reflection under total
   internal reflection.
2. The ray inside is intersected with the surface itself (`ShaderContext.getSurface()`, a
   `ShadableSurface`, so `intersectAt` is available without a cast) to find where it
   leaves, and refracted again there, out of the material.
3. The ray leaving the material is traced against the other surfaces with
   `LightingEngineAggregator.getClosestColor()`. That ray is lit only by the context's current
   light, the light the shader is invoked for, so the aggregator builds one candidate per
   surface.
4. The colour is weighted by the transmitted fraction `1 - R` (Schlick, with
   `R0 = ((n - 1) / (n + 1))^2`) and the attenuation factors.

Each secondary ray starts `RefractionShader.RAY_OFFSET` along its direction —
`RayFeatures.ray(origin, direction, offset)` — so it does not hit the surface at its own
origin. `RefractionShader.maxRefractions` bounds how many transparent objects are followed
through one another.

## Cost

A secondary ray embeds its parent ray's graph, so each bounce enlarges the kernel of the
surface that spawns it. At 32×32 a mirror sphere over a lit floor renders in about a minute on
Metal, most of it compiling. Keep test images small and recursion limits low.

## Known limits

- Refraction does not yet render within a test timeout: compiling the two-interface graph
  spends its time in `ScopeLifecycle.resetArguments`, which recurses into every input with no
  record of the nodes it has already visited, so a graph whose nodes are shared by many paths
  (the refracted direction reuses the viewer vector and the normal several times, twice over)
  is traversed once per path. `ReflectionRefractionRenderTest#refractiveSphereShowsFloor`
  remains a known issue until that traversal visits each node once.
- A mirror whose surface has a non-identity transform (for example a sphere of radius 2)
  produces a per-pixel expression too large to simplify ("Large expression not improved by
  simplification"); a unit sphere at the origin and a `Plane` both work.
- `SimpleRenderTest#renderTwoSpheres` (two diffuse spheres) still exceeds its 30 second
  timeout while compiling, so scenes with many surfaces remain expensive to compile.
- Each per-light lighting engine traces its own secondary rays, and the reflected colour is
  multiplied by that light's colour; with several lights the secondary rays are traced once
  per light.
- The primary `LightingEngineAggregator` built by `RayIntersectionEngine` holds one candidate
  per (surface, light) pair and selects the closest one rather than summing the lights of the
  closest surface. Candidates for the same surface tie, and a tie goes to the first, so a
  scene with several lights is rendered under its first light only.
- `RefractionShader` assumes a closed surface. An open surface (such as a `Plane`) gives no
  exit point for the ray inside it.
- `ReflectionShader`'s environment map (`setEnvironmentMap`) is not used: a reflected ray
  that misses every surface contributes black.

## Tests

- `ReflectionRefractionRenderTest` (`engine/render`): a mirror sphere shows the lit floor.
  The matching refractive-sphere test is still a known issue (it does not finish within
  its timeout, as described above), so CI skips it and refraction is not yet covered.
- `MirrorReflectionTest` (`engine/render`): a mirror sphere reflects a red plane behind the
  camera exactly where an analytic trace of the same pixels predicts.
- `SimpleRenderTest` (`engine/render`): single-sphere renders through `RayTracedScene`.
