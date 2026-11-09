# Per-Pixel Kernel Rendering

A fragment shader renders an image with one small program per pixel: build the camera ray
from the pixel coordinate, march it against a surface, shade the hit. The same structure
can be expressed as producer graphs over the batch of every pixel of an image and compiled
by the existing backends (Metal, OpenCL, native C) into one kernel that runs the program of
each pixel in its own thread. This document describes that capability:

- how a camera ray graph written for one pixel evaluates a whole batch of pixels,
- the `ImplicitSurface` abstraction (`SignedDistanceFunction`, `HeightField`),
- `ImplicitSurfaceRenderer`, which marches, refines and shades every pixel in one kernel,
- why the march is a list of per-pixel assignments rather than one nested expression,
- a worked example, measured cost, and known limits.

| Type | Module | Package |
|---|---|---|
| `ImplicitSurface`, `SignedDistanceFunction`, `HeightField` | `compute/geometry` | `org.almostrealism.geometry` |
| `ImplicitSurfaceRenderer` | `engine/render` | `org.almostrealism.render` |
| `SlicingFeatures.batchShape` / `repeatItems`, `VectorFeatures.scale` | `compute/algebra` | `org.almostrealism.collect`, `org.almostrealism.algebra` |

---

## 1. Camera rays for a batch of pixels

`PinholeCamera.rayAt(position, screenDimensions)` (through `ProjectionFeatures`) is written
for a single `(2)` screen position. Give it an `(n, 2)` batch of positions and it produces
the `(n, 6)` batch of rays:

```java
// CameraRayIntersectionTest.kernelMatchesPerPixel
PackedCollection pixels = new PackedCollection(shape(WIDTH * HEIGHT, 2));
// ... fill pixels with (x, y) per pixel ...
PackedCollection output = new PackedCollection(WIDTH * HEIGHT).traverse(1);
distance(shape(WIDTH * HEIGHT, 2)).get().into(output).evaluate(pixels);
```

This works because the shape operations the camera graph uses are batch-aware (see
[Shape and Traversal — Batches of Items](shape-and-traversal.md#batches-of-items)):

- `PairFeatures.l/r` and `RayFeatures.origin/direction` take a lower-rank `subset` of their
  input. A subset with fewer dimensions than its input is taken from every item: the
  `(1)` subset at position 0 of an `(n, 2)` input is the `(n, 1)` first component of every
  row, with count `n`.
- `pair`, `vector`, `rgb` and `ray` size their output from their inputs with
  `batchShape`, and `concat` repeats a single shared input (the camera location, say)
  for every item.

Before this, the lower-rank subset passed a one-dimensional position to the
two-dimensional input shape (`TraversalPolicy.index` threw `IllegalArgumentException`,
reported as "Could not obtain operator"), and even with that fixed, the `(1)`-shaped
results had count 1 and could not fill an `n`-element destination.

The batch must be fixed-count and traversed at its leading axis. A graph written for one
item needs no changes beyond receiving the batch.

---

## 2. Implicit surfaces

`ImplicitSurface` describes a surface as the zero set of a function: `f(p) > 0` outside,
`f(p) = 0` on the surface, `f(p) < 0` inside. Every method is a producer graph over a
`(3)` position or an `(n, 3)` batch of positions:

| Method | Result | Meaning |
|---|---|---|
| `value(position)` | `(n, 1)` | `f(p)` |
| `lipschitzBound()` | `(1)` | `L` with `|f(a) - f(b)| <= L |a - b|`; `1` for a `SignedDistanceFunction` |
| `normal(position)` | `(n, 3)` | unit outward normal; default: normalized central-difference gradient |

`f(p) / L` is a distance a ray can always advance without crossing the surface, which is
what makes marching conservative. The bound is a producer so that it can follow data that
changes between frames.

### SignedDistanceFunction

A signed distance (`L = 1`). The distance is its only abstract method, so a lambda over the
position is a signed distance function; a surface with an analytic normal implements the
interface and overrides `normal`:

```java
// the unit sphere, with normals estimated from the gradient of the distance
SignedDistanceFunction sphere = p -> length(p).subtract(c(1.0));

// the unit sphere, with its analytic normal
class UnitSphere implements SignedDistanceFunction {
    public CollectionProducer value(Producer<PackedCollection> p) { return length(p).subtract(c(1.0)); }
    public CollectionProducer normal(Producer<PackedCollection> p) { return normalize(p); }
}
```

The distance must accept an `(n, 3)` batch. Combine it with a constant `(3)` vector through
`repeatItems(shape(p), v(center))` so the constant is repeated for every position.

### HeightField

The surface `y = h(x, z)` of an `[nx, nz]` grid of heights, as `f(p) = y - h(x, z)`:

```java
// node (i, j) is the point (x0 + i dx, z0 + j dz)
HeightField surface = new HeightField(cp(heights), x0, z0, dx, dz);
```

- **Sampling** is bilinear within a cell, using device gathers of the four corner heights;
  outside the grid the height is that of the nearest edge.
- **Normals** are analytic: `normalize(-dh/dx, 1, -dh/dz)` of the bilinear surface.
- **Lipschitz bound**: within a cell, `dh/dx` is a weighted average of the slopes of the
  two cell edges along `x` (likewise `z`), so `|grad f| <= sqrt(1 + sx^2 + sz^2)` where
  `sx`, `sz` are the largest slopes between neighbouring nodes. These are computed on the
  device with `max` reductions over the heights, every frame.
- The heights are read through the producer, so a collection that a simulation updates in
  place renders its current state without rebuilding anything.

---

## 3. ImplicitSurfaceRenderer

```java
ImplicitSurfaceRenderer renderer = new ImplicitSurfaceRenderer(surface, camera, width, height);
renderer.addLight(new PointLight(new Vector(3, 4, 5), 1.0, new RGB(1, 1, 1)));
Runnable frame = renderer.frame().get();   // compile once
frame.run();                               // render a frame
PackedCollection image = renderer.getImage();         // (height, width, 3), row 0 at the top
PackedCollection distance = renderer.getDistance();   // (height, width), -1 for a miss
PackedCollection fresnel = renderer.getReflectance(); // (height, width), 0 for a miss
```

Configuration (all optional): `setAmbientLight`, `setBaseColor`, `setEnvironment(horizon,
zenith)`, `setNormalReflectance` (F0, default 0.04), `setSpecular(strength, shininess)`,
`setMarchSteps` (64), `setRefinementSteps` (8), `setMinimumStep` (1e-3),
`setMaximumDistance` (100). Configure before calling `frame()`: the operations capture the
configuration, lights and colors when they are built, so call `frame()` again (and compile
the result) after changing them. The surface data, by contrast, is read when each frame
runs.

The renderer owns its per-pixel state and image and is `Destroyable`: `destroy()` (or
try-with-resources) releases them, after which the views returned by `getImage`,
`getDistance` and `getReflectance` are no longer valid.

### The per-pixel program

For every pixel `k` (column `k % width`, row `k / width` from the top):

1. **Ray.** `camera.rayAt(...)` on the `(n, 2)` batch of screen positions, stored in an
   `(n, 6)` collection.
2. **March** (`marchSteps` times). Each pixel keeps a bracket `[near, far]` along its ray,
   with `f > 0` at `near`, and a probe distance. Evaluate `f` at the probe; if `f <= 0`
   close the bracket there (`far = probe`); otherwise move `near` to the probe and advance
   the probe by `max(f / L, minimumStep)`, clamped to `maximumDistance`. Once the bracket is
   closed, nothing changes.
3. **Hit flag.** A pixel hits if its bracket closed before `maximumDistance`.
4. **Bisection** (`refinementSteps` times) of `[near, far]`.
5. **Secant step**: the zero of the line through `f(near)` and `f(far)`, which is the hit
   distance (or -1 for a miss).
6. **Shading**, written to the image and reflectance.

### Shading model

With `n` the normal, `d` the ray direction, `r = d - 2 (d.n) n` the reflected direction,
`l` the direction to a point light and `h = normalize(l - d)`:

```
F       = F0 + (1 - F0) (1 - max(-d.n, 0))^5          Schlick Fresnel
diffuse = base * (ambient + sum(light * max(n.l, 0)))
color   = (1 - F) diffuse + F environment(r) + sum(light * specular * max(n.h, 0)^shininess)
miss    = environment(d)
```

`environment(dir)` blends from the horizon color (at and below the horizon) to the zenith
color as `dir.y` goes from 0 to 1. Lights cast no shadows and do not attenuate. The result
is clamped to `[0, 1]`. The model is deliberately small; a different look is a different
`addShading` (the step methods are `protected`).

### Saving the image

`getImage()` has the `(height, width, 3)` layout the image writers expect:

```java
saveRgb("results/frame.png", cp(renderer.getImage())).get().run();
```

---

## 4. Why the march is a list of assignments

A ray march written as one producer expression,
`t[k+1] = t[k] + f(o + t[k] d)`, nests the expression of every step inside the next, and
each step uses `t[k]` several times, so the expression grows exponentially with the step
count. Measured on an Apple-silicon Mac: 4 unrolled steps over a 32x32 batch took 7.8 s
to compile, and 8 steps did not finish in 10 minutes. **Do not unroll dependent steps into
one expression.**

Instead the renderer keeps the march state of every pixel in `(n, 1)` collections it owns
(probe, near, far, the last value, the hit flag) and writes each step as assignments,
`a(cp(state), expression reading cp(state))`, that read and write only their own pixel's
state. Every expression is then the size of one step. Every one of these assignments has
count `n`, so the list is *uniform*, and `OperationList.get()` compiles a uniform list of
computations into a single kernel whose statements run in order for each pixel (see
[Simulating a Spiking Neural Population — How the runner dispatches the step](neural-population-simulation.md#how-the-runner-dispatches-the-step)
for the uniform/non-uniform rule). The test asserts the per-pixel list `isUniform()` and
`isComputation()`.

The Lipschitz bound has count 1 and may contain reductions, so `frame()` returns an outer
list of two operations: the bound (optimized with `Process.optimized` so its reductions are
isolated in kernels of their own; without that the `max` is unrolled into its consumer and
compilation fails with "Expression too deep") followed by the per-pixel kernel. Keep any
whole-surface reduction outside the per-pixel list for the same reason.

The same pattern — per-element state collections plus a uniform list of element-local
assignments — applies to any fixed-iteration per-element algorithm (Newton iterations,
fixed-point solves, path tracing bounces).

---

## 5. Worked example: a height field rendered every frame

```java
public class HeightFieldFrames extends TestSuiteBase implements RGBFeatures {
    @Test(timeout = 900000)
    public void render() {
        int n = 129;
        PackedCollection heights = new PackedCollection(shape(n, n));
        // ... a simulation writes heights in place on the device ...

        HeightField surface = new HeightField(cp(heights), -8.0, -8.0, 0.125, 0.125);
        PinholeCamera camera = new PinholeCamera(new Vector(0.0, 3.0, 6.0),
                new Vector(0.0, -0.45, -1.0), new Vector(0.0, 1.0, 0.0), 1.0, 1.0, 1.0);

        ImplicitSurfaceRenderer renderer = new ImplicitSurfaceRenderer(surface, camera, 128, 128);
        renderer.addLight(new PointLight(new Vector(3.0, 4.0, 5.0), 1.0, new RGB(1.0, 1.0, 1.0)));
        renderer.setBaseColor(new RGB(0.05, 0.05, 0.06));   // dark, glossy
        renderer.setSpecular(0.8, 128.0);

        Runnable frame = renderer.frame().get();             // compile once
        Supplier<Runnable> save = saveRgb("results/frame.png", cp(renderer.getImage()));

        frame.run();                                         // per frame, at the host boundary
        save.get().run();
    }
}
```

`ImplicitSurfaceRendererTest.frameTime` is this example with a sinusoidal height field.
Driving frames from a simulation belongs to whatever owns the simulation loop (see
[Simulation Loop Examples](simulation-loop-examples.md)); rendering a frame is a host
boundary like recording, and `frame.run()` is the only call it needs.

### Measured cost

On an Apple-silicon Mac with the Metal backend (`ImplicitSurfaceRendererTest.frameTime`,
sinusoidal 129x129 height field, 64 march steps, 8 bisections, one point light):

| Resolution | Compile (once) | First frame | Per frame |
|---|---|---|---|
| 128 x 128 | ~8 s | ~0.7 s | ~3.8 ms |

The test logs these numbers and does not assert them.

---

## 6. Verification

`ImplicitSurfaceRendererTest` (`engine/render`) compares the renderer with analytic
intersections of the same camera rays, each evaluated one pixel at a time:

| Test | Checks |
|---|---|
| `sphereSilhouette` | hit set matches the analytic projection of a unit sphere to within one pixel (0 mismatches at 32x32); hit distances within 1e-3; centre lit; corners miss; per-pixel list is one kernel |
| `flatHeightField` | hit distances on a flat height field within 1e-3 of the plane intersection |
| `sinusoidalHeightField` | hit distances on `0.2 sin(x) cos(z)` (257x257 grid) within 1e-3 of the analytic surface |
| `fresnelGrazingExceedsNormal` | reflectance is F0 at the centre and matches analytic Schlick at the silhouette, which exceeds it |
| `frameTime` | logs the compile and per-frame time and writes `results/implicit-surface-height-field.png` |

`CameraRayIntersectionTest.kernelMatchesPerPixel` checks that the batched camera ray graph
gives the same intersection distances as evaluating each pixel on its own.

---

## 7. Known limits

- **Fixed step count.** A ray that needs more than `marchSteps` steps to close its bracket
  is reported as a miss. Rays that graze a height field take many small steps; raise
  `marchSteps` or `minimumStep` (a larger minimum step can skip a thin crossing).
- **Height fields outside the grid** take the height of the nearest edge, so the surface
  extends as flat skirts; keep the camera's view of the grid inside its footprint, or put
  the footprint where the skirts do not matter.
- **Bilinear sampling** follows the samples, not the function they came from: between
  nodes the surface is within about `|h''| dx^2 / 8` of a smooth function.
- **Normal estimate.** The default `ImplicitSurface.normal` evaluates the function six
  times per pixel; override `normal` in a `SignedDistanceFunction` whose normal is known analytically.
- **No shadows, inter-reflection or light attenuation**, and the environment is a gradient,
  not a map. The reflection term samples only the environment.
- **One surface.** Combine several surfaces in one `SignedDistanceFunction` with `min`
  (union) or `max` (intersection) of their distances.
