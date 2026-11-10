---
status: proposed
created: 2026-11-08
closed:
tracker: []
---

# Option: rendering through GLSL-style per-pixel programs

**Status:** an option to weigh alongside the ferrofluid rendering plan, not a decision. The
current plan renders with the existing ray tracer (`engine/render`). This note records a
possibly faster route to a first moving picture, so that it is considered when the rendering
approach is chosen. Nothing here has been built or run; claims marked *unverified* are reasoning
from the code.

## The idea

A fragment shader runs one small program per pixel, so a complete renderer can be written as a
single per-pixel function: build the camera ray from the pixel coordinate, march it against a
signed distance function (SDF) until it reaches the surface, then shade the hit with a few lights,
a Fresnel term and an environment lookup. Minimal ray tracers written this way are a few hundred
lines of shader code and run at interactive rates.

For the ferrofluid this fits Option C of the plan's surface-representation choice (implicit
surface, SDF ray marching), and it fits the height-field slice especially well: a height field
`h(x, z)` is the implicit surface `y - h(x, z) = 0`, which a ray marcher can step against directly,
with no meshing. The glossy look comes from shading code in the same function, so it does not
depend on `ReflectionShader`, whose render path is currently broken (see
`ReflectionRefractionRenderTest` in `engine/render` for the exact failures).

## Two ways to get it

### 1. Per-pixel kernels on the existing backends (no GLSL)

What makes a shader ray tracer fast is that it is one compiled program per pixel. Common already
compiles producer graphs to per-element kernels for Metal, OpenCL and native C, so the same
renderer could be written as a producer graph over a batch of pixel coordinates (ray from pixel,
a fixed number of march steps, shade) and evaluated as one kernel.

- *Pros:* no new backend or dependency; runs in CI wherever kernels run; the result is a
  `PackedCollection` image that the existing image writing handles.
- *Cons:* not interactive; a fixed step count must be unrolled or looped in the graph.
- *Caution (verified failure):* evaluating the existing camera-ray producers over a batch of
  pixels fails today (`CameraRayIntersectionTest#kernelMatchesPerPixel`, a known issue: the ray
  direction takes a one-dimensional subset of the two-dimensional batch in
  `TraversalPolicy#subset`). A ray marcher that computes its ray directions directly from the
  pixel coordinates would not use that code, but whether such a graph compiles cleanly is
  *unverified*.

### 2. A GLSL backend

A new code-generation backend, following the pattern of the existing ones:

| Piece | Existing pattern | For GLSL |
|---|---|---|
| Language rules | `MetalLanguageOperations`, `OpenCLLanguageOperations`, `JavaScriptLanguageOperations` | `GLSLLanguageOperations`: types, built-ins, the kernel index taken from the fragment coordinate |
| Code writer | `MetalPrintWriter`, `JavaScriptPrintWriter` (both extend `CPrintWriter`) | `GLSLPrintWriter`: a fragment shader `main()` that writes the kernel's output to the pixel |
| Execution | `MetalComputeContext` and friends (JNI) | One of the options below |

The text side is modest; the Metal language and writer together are under 400 lines. The
JavaScript backend (`base/hardware-js`) is the precedent for a text-only backend in its own module.
GLSL differs from the C-family targets in ways the writer has to handle: no pointers, so kernel
arguments become uniforms or textures; single precision in practice; no recursion.

Execution is the larger question:

- **A WebGL page.** Emit an HTML page that holds the generated shader and uploads the inputs (for
  example the height field) as textures. No native dependency, interactive camera, quickest to
  look at. Not usable from tests.
- **Desktop OpenGL through a Java binding.** A new native dependency. macOS's OpenGL has no compute
  shaders or storage buffers, so only the fragment-shader route is available there.
- **Text only.** Generate shaders for use elsewhere.

What already exists for this is negligible. The rings repository's `gl` module has a
`GLSLPrintWriter` that is an empty subclass of `CPrintWriter`, shader classes that are stubs, and
two disabled or empty tests. Its working code is an OpenGL fixed-function renderer through a Java
binding. None of it is worth moving; a backend would be written fresh. A new module needs the
owner's approval.

## Weighing it against the plan

- **For:** the quickest route to a moving, glossy picture of a height field; interactive viewing;
  avoids the two blocked shading paths; the producer-graph form (route 1) is reusable, and could
  later be emitted as GLSL (route 2) if interactivity matters.
- **Against:** a second render path beside the ray tracer, which the plan already counts against
  Option C; single precision; GLSL execution would not run in CI; the plan's recommended path
  (height-field mesh through the tested triangle intersection) reuses more working code.

A reasonable way to keep it open: take route 1 as an experiment once the height field exists,
rendering the same surface the mesh path renders, and compare the two pictures and their
cost. Route 2 is worth it only if interactive viewing becomes a goal.
