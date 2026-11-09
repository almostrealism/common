# Migrating the rings `raytracer` module into common

**Scope:** the `raytracer` module of the rings repository (`ar-raytracer`), 33 main
classes and 7 tests in `com.almostrealism.network` and `com.almostrealism.raytracer`.

## Why

Most of rings was moved into common earlier. A few modules were left behind because they
contained Swing code, which does not belong in common, but that left non-UI library code
behind with it. `raytracer` is the most direct case: it depends only on common modules
(`ar-render`, `ar-graphpersist`, `ar-flowtreeapi`) and on rings' `ar-swing`. Some of what it
holds, notably the mesh and scene file formats, is needed by the ferrofluid
simulation-and-rendering work in common.

## Guiding rule

Anything that would be part of a *library* moves to common. What stays in rings is UI code
and code that is not library material: scene-construction helpers, demos with a `main()`,
and unidentified one-offs.

## Status

| Group | Classes | Decision | Status |
|---|---|---|---|
| Resource abstraction (prerequisite) | `Resource`, `ResourceAdapter`, `UnicodeResource`, `IOStreams`, `Permissions`, `ResourceTranscoder` | Move from `ar-graphpersist` to `ar-io` | **Done** |
| | `JsonResource` | Delete: it was an empty marker subclass | **Done** |
| 1. Mesh and scene file formats | see below | Move to `ar-space`, package `org.almostrealism.space.format` | **Done** |
| 2. Distributed ray-tracing jobs | `RayTracingJob`, `RayTracingJobFactory`, `RayTracingJobOutput`, `RayTracingOutputHandler` | New common module, done **last**; not needed for ferrofluid | Not started |
| 3. JavaScript code generation | `JavaScriptPrintWriter`, `JavaScriptLanguageOperations` | New module `ar-hardware-js` | **Done** |
| 4. Scene construction and demos | `SceneFactory`, `TestScene`, `Thing`, `RayTracingTest`, `GenerateScene` | Stay in rings | — |
| 5. Unidentified one-off | `SpanCalculator`, `Matrix3DSolutionOutput` (a 3D packing-puzzle solver) | Stay in rings | — |
| 6. UI | `RenderPanel`, `DebugOutputPanel`, `SurfaceUI`, `Settings`, `LegacyRayTracingEngine`, `RayTracedAnimation` | Stay in rings | — |

## The resource abstraction in `ar-io`

`Resource` and the classes it needs were in `ar-graphpersist`, above `ar-utils`. Every file
format implements `Resource`, so the formats could not live below graphpersist without
pulling in the whole persistence module. They now live in `ar-io`, keeping the package name
`io.almostrealism.resource`, so no imports changed:

- `IOStreams` moved rather than `Resource.load` being narrowed to a `DataInputStream`. Two
  implementations in `ar-flowtree-runtime` need both streams in `load` (and in `send`):
  `ImageResource.load` writes the requested clip region to `io.out` before reading pixels,
  and `DistributedResource.load` requests chunks by index the same way.
- `Resource` no longer extends `io.almostrealism.relation.Node`, since `ar-io` does not depend
  on `ar-relation`. Its only use was `ResourceDistributionTask` declaring `Graph<Resource>`,
  whose `neighbors` and `countNodes` only threw `UnsupportedOperationException`. That
  unimplemented `Graph` role was removed along with the two tests that asserted it throws.
- `JsonResource` was deleted. Its only users were the mesh-to-JSON transcoder and the
  JavaScript writer's conversion of resource variables to JSON, neither of which was live
  (see below). It can come back if resources are integrated with the JSON support elsewhere
  in common.

Only `ResourceVariable`, which needs `ar-code`'s scope types, remains in graphpersist's part
of the package.

## Group 1: the formats in `ar-space`

`org.almostrealism.space.format` holds:

- **Resources:** `MeshResource`, `SceneResource`.
- **File formats:** `GtsResource` (read and write), `PlyResource`, `ObjResource`,
  `RawResource` (read).
- **Decoding and encoding:** `ModelData` and `FileDecoder` (decode by encoding code),
  `FileEncoder` (XML), `FileSystemSceneLoader`, `MeshFile`.
- **Support:** a package-private `TextFormatReader` that the GTS, PLY, OBJ and RAW readers
  share: comment skipping, value parsing and line-numbered errors.

`MeshFormatTest` in `engine/utils` covers each format.

What changed in the move:

- **Removed with no live users:**
  - `ResourceTranscoderFactory`, whose only caller was the dead JSON path.
  - The OBJ, PLY and RAW "writers", which returned `null`.
  - `MeshResource.JsonTranscoder`.
  - `ObjPolygon` and the OBJ texture-coordinate parsing, which built data that was never used.
  - `FileDecoder.readRGBList`, which never advanced its column index.
- **GTS code unified:** `FileEncoder.GTSEncoding` was `3`, a code no reader understood. It
  now equals `ModelData.GTSEncoding`, so saving and loading GTS use the same code.
- **GTS writer fixed:** it wrote 0-based vertex and edge indices, but GTS, and this reader,
  are 1-based, so its output did not read back correctly.
- **Stubs:** `MeshResource` and `SceneResource` now throw `UnsupportedOperationException` for
  load, send, save and stream, instead of silently doing nothing. Their URI is now kept, and
  `SceneResource` reports default permissions instead of `null`.
- **Errors:**
  - Truncated GTS, PLY or RAW data throws an `IOException` naming the line, where it used to
    return `null` (which the callers then dereferenced).
  - Malformed OBJ faces and RAW triangle lines are still skipped with a warning.
  - Faces that are not triangles still use their first three corners, now with a warning.
  - A PLY read given an initial mesh now warns instead of throwing.
- **Constants:** all the encoding codes are now declared on `ModelData`; `FileDecoder.XMLEncoding`
  and the others still resolve, through inheritance.
- **`ui` parameter:** it no longer has any effect. The XML path's unwrapping of
  `ShadableSurfaceWrapper`s wrote into a copy of the surface array and never changed the scene,
  so it was removed. The parameter is kept so callers only change imports; removing it is a
  possible follow-up.

## Group 3: `ar-hardware-js`

`base/hardware-js`, package `org.almostrealism.hardware.js`. This is the first of the
per-backend modules (`ar-hardware-cl`, `ar-hardware-mtl`, `ar-hardware-cuda`, ...) that the
backends now inside `ar-hardware` are meant to be split into.

- It depends only on `ar-code`.
- The writer's conversion of `ResourceVariable`s to JSON was dropped: nothing creates a
  `ResourceVariable` (the only constructions are commented out), and the conversion would
  have tied the backend to the file formats and graphpersist. The unused `toJson` helpers
  went with it.
- Its tests (`JavaScriptPrintWriterTest`) are in `engine/utils`, which has a test-scoped
  dependency on it, so that they extend `TestSuiteBase` and run in the existing CI lanes. A
  test inside the new module would not run in CI, because the CI test lanes name their modules.

## Group 2: distributed ray-tracing jobs (last)

A new common module that depends on `ar-render` and `ar-flowtreeapi` (and `ar-graphpersist`,
for `RayTracingOutputHandler`'s database query handling). `ar-render` must not take a
flowtree dependency itself. Before the move:

- `RayTracingJob` turns on verbose output through `Settings` (rings, AWT-dependent) by
  setting its static output flags and print writers. Those flags have to move into something
  without AWT, either in the new module or as `ar-render` settings, leaving `Settings` with
  only its theme colours and splash-image paths.
- `RayTracingJobOutputDecodingTest` moves with the module.

## Still to decide

- **`Settings` split.** This is required before group 2.
- **`LegacyRayTracingEngine`.** Its render loop is not UI; only its `ProgressDisplay`
  parameter is Swing. The options are to keep it in rings, to delete it as superseded by
  `ar-render`'s engines, or to move it behind a non-Swing progress interface.
- **`RayTracedAnimation`.** It extends `space.Animation` and uses `ar-swing`. How deep the
  Swing use goes has not been checked.
- **Rendering tests.** `IntersectionCompactTest`, `KernelizedIntersectionTest` and
  `IntersectionalLightingEngineTest` exercise only `ar-render` code and could move to
  `ar-render`'s tests. `LightingEngineAggregatorTest` displays its output through Swing and
  would need that part removed.
- **The `ui` parameter** of `ModelData.decodeScene` and `FileDecoder.decodeSurfaceFile`,
  which no longer does anything.
