# Regular Grid Surfaces and Accelerated Mesh Intersection

This guide covers two related capabilities for getting a simulated surface into the ray
tracer at scale:

1. **Regular grid surface tessellation** — turning a 2D height field into renderable
   triangle geometry on the device, as a Producer graph, so a per-frame surface rebuild is
   one kernel rather than a host loop.
2. **Accelerated ray–triangle intersection** — a bounding volume hierarchy (BVH) that prunes
   the triangles a ray must be tested against, so intersection scales sub-linearly with
   triangle count, with the unaccelerated linear scan kept as the correctness reference.

Both are general: a "regular grid surface" is any single-valued height field `h(x, z)`, not
tied to a particular scenario.

## 1. Height-field tessellation

### The surface

A regular grid surface spans `nx` by `ny` nodes in the XZ plane, with height along Y. Node
`(i, j)` sits at

```
(originX + i * spacingX,  originY + h[i*ny + j],  originZ + j * spacingZ)
```

Each of the `(nx-1)` by `(ny-1)` cells is split into two triangles with a consistent winding
whose face normals point towards `+Y`: the first triangle spans the cell-relative node
offsets `(0,0) (1,1) (1,0)`, the second `(0,0) (0,1) (1,1)`. The triangle count is therefore
`2 * (nx-1) * (ny-1)`.

### Device path (per-frame rebuild)

`MeshData.gridSurface(Producer<PackedCollection> heights, nx, ny, Vector origin, spacingX, spacingZ)`
builds the packed triangle buffer the intersection path expects, in a single kernel. It is
driven by `TriangleFeatures.regularGridSurfacePoints(...)`, which emits the `(T, 3, 3)`
triangle-corner buffer directly from the height field as one
`DefaultTraversableExpressionComputation`, and then packs it into the `(T, 4, 3)` `MeshData`
layout with `TriangleFeatures.triangle(...)`. Because `heights` is a `Producer`, a simulation
can write new heights into the backing collection each frame and rebuild the `MeshData` with a
single `gridSurface` call — no host loop over cells.

### Per-vertex normals from the gradient

`TriangleFeatures.regularGridSurfaceNormals(heights, nx, ny, spacingX, spacingZ)` returns a
`(nx*ny, 3)` column of per-vertex unit normals for smooth shading. For the surface
`y = h(x, z)` the outward normal is `normalize(-dh/dx, 1, -dh/dz)`; the gradient is estimated
with a central difference in the interior and a one-sided difference at the edges, so for a
tilted plane the normals are exact at every node.

### Host path (inspection and GTS export)

`Mesh.gridSurface(double[][] heights, Vector origin, spacingX, spacingZ)` builds a host,
vertex-list `Mesh` with the same node layout and winding. This is the path for inspection,
for use as a test fixture, and for GTS export (`GtsResource.MeshTranscoder`), which reads a
mesh's vertex list and triangles. Use the device `MeshData.gridSurface` for the intersection
and per-frame path; use the host `Mesh.gridSurface` when a `Mesh` object is wanted.

## 2. Accelerated intersection

### Batched intersection kernel

`TriangleFeatures.meshIntersectAt(mesh, triangleCount, ray)` computes the Möller–Trumbore
intersection distance of a single ray against **every** triangle of a packed `(T, 4, 3)` mesh
in one kernel, returning a `(T, 1)` column of distances (`-1` where a triangle is missed). The
closest hit is then selected with `RankedChoiceEvaluable.highestRank`. `MeshData.evaluateIntersection`
(single ray) uses it.

`TriangleFeatures.meshIntersectAll(rays, rayCount, mesh, triangleCount)` is the batched
counterpart: it tests a whole `(R, 6)` ray batch against the mesh in one kernel, returning the
`(R, T)` grid of per-(ray, triangle) distances. `TriangleFeatures.meshClosestHit(...)` builds on
it and reduces that grid over the triangle axis — ranking a hit at distance `t` as `-t` so the
nearest hit is the per-row maximum and its `indexOfMax` is the hit triangle — to produce the
closest `(distance, triangleIndex)` per ray as an `(R, 2)` collection, entirely on-device with no
host loop over rays. `MeshData.evaluateIntersectionKernel` uses this; its result matches the
per-ray scan exactly (ties resolve to the lowest triangle index, matching `highestRank`). The
Möller–Trumbore distance expression is shared between the single-ray and batched kernels so they
cannot drift apart. `evaluateIntersectionAccelerated` / `evaluateIntersectionKernelAccelerated`
are the hierarchy-pruned counterparts (the accelerated batch still loops rays on the host to
gather each ray's candidate set).

### Bounding volume hierarchy

`BoundingVolumeHierarchy` is a host-built binary tree of axis-aligned boxes over a set of
triangles. Build it from a `Mesh` (`BoundingVolumeHierarchy.build(mesh)`) or from explicit
triangle vertex positions (`BoundingVolumeHierarchy.build(List<Vector[]>)`). A `MeshData` owns a
hierarchy over its own triangles and builds it on demand via `MeshData.getBoundingVolumeHierarchy()`,
reconstructing the triangle vertices from its packed edge data so the hierarchy's indices map
straight back to the mesh's rows. For a ray, `candidates(ox, oy, oz, dx, dy, dz)` returns the
indices of every triangle whose box the ray pierces (slab test, boxes inflated by a small
epsilon). The result is **conservative**: it always contains the truly-closest triangle, so
intersecting only the candidates reproduces the closest hit of a full scan.

`MeshData.evaluateIntersectionAccelerated(ray, args)` runs the accelerated query against the
mesh's own hierarchy: it gathers the ray's candidates, copies those triangle rows into a reusable
compact buffer, and runs the same `meshIntersectAt` kernel over the candidates. Because the
triangle math is the identical compiled kernel, the accelerated closest hit is bit-for-bit the
linear scan's closest hit — but far fewer triangles are tested. `CachedMeshIntersectionKernel`
takes an `accelerated` flag, and `Mesh.intersectAt` routes through the mesh data's hierarchy when
`Mesh.enableAcceleratedIntersection` is set (off by default, so the linear scan remains the
reference).

### Scaling

For a downward ray over a height field, the candidate count stays roughly constant as the grid
is refined (the ray crosses a near-constant number of cells), while the linear scan tests every
triangle. The accelerated work therefore grows sub-linearly with triangle count. Wall time is
backend-dependent: the linear kernel tests its triangles in parallel, so at a modest size such
as a `20x20` grid (722 triangles, 24 rays) both paths take tens of milliseconds, and the
triangle-test count is the measure that shows the gap widening as the mesh grows.

Every batched kernel here (`triangle`, `regularGridSurfacePoints`, `regularGridSurfaceNodes`,
`regularGridSurfaceNormals`, `meshIntersectAt`, `meshIntersectAll`) declares an output shape
traversed per triangle, per node, or per (ray, triangle) pair, for example
`new TraversalPolicy(t, 4, 3).traverse(1)`. Without the traversal axis the output has a count of
one, so the whole `t * 12` element buffer is unrolled into a single kernel body and compile time
grows linearly with the triangle count.

## Worked example

```java
// Define a height field (host array for the fixture; a device Producer for per-frame use).
int nx = 64, ny = 64;
double[][] h = new double[nx][ny];
for (int i = 0; i < nx; i++)
    for (int j = 0; j < ny; j++)
        h[i][j] = 0.3 * Math.sin(0.5 * i) * Math.cos(0.4 * j);

Vector origin = new Vector(0, 0, 0);
double spacing = 0.1;

// Device tessellation -> MeshData for intersection (one kernel; rebuildable per frame).
PackedCollection heights = PackedCollection.of(flatten(h)).reshape(shape(nx, ny));
MeshData md = MeshData.gridSurface(cp(heights), nx, ny, origin, spacing, spacing);

// Closest hit, accelerated and (reference) unaccelerated — identical result. The mesh data
// builds its own acceleration structure over its triangles on first accelerated query.
Evaluable<Ray> r = ray(vector(2.0, 5.0, 2.0), vector(0, -1, 0)).get();
Pair accelerated = md.evaluateIntersectionAccelerated(r, new Object[0]);
Pair linear      = md.evaluateIntersection(r, new Object[0]);
// accelerated.getA() == linear.getA()  (distance)
// accelerated.getB() == linear.getB()  (triangle index)

// Export a host mesh of the same surface for inspection.
Mesh mesh = Mesh.gridSurface(h, origin, spacing, spacing);
GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(mesh));
```

## Scope and follow-up

The BVH is host-built and its traversal runs on the host (per ray); the triangle math itself is
a compiled kernel. The non-accelerated batch path is now a single `meshClosestHit` dispatch over
the whole ray batch (no host loop), so the remaining per-ray host loop is the accelerated path's
candidate gathering. A fully device-resident BVH traversal — which would let the accelerated path
also run as one dispatch — is a possible future refinement. Separately, the existing
`SpacePartition` BSP traversal remains a stub; the acceleration here is a BVH (an object
partition) rather than a repair of that tree (a space partition) — see the class javadoc on
`BoundingVolumeHierarchy` for why the two are different structures.

## Related files

| File | Role |
|---|---|
| `domain/graph/.../graph/mesh/TriangleFeatures.java` | `regularGridSurfacePoints`, `regularGridSurfaceNodes`, `regularGridSurfaceNormals`, `meshIntersectAt`, `meshIntersectAll`, `meshClosestHit` |
| `domain/space/.../space/MeshData.java` | `gridSurface`, linear + accelerated intersection, `getBoundingVolumeHierarchy` |
| `domain/space/.../space/Mesh.java` | host `gridSurface`, `calculateBoundingSolid` |
| `domain/space/.../space/BoundingVolumeHierarchy.java` | the acceleration structure |
| `compute/geometry/.../geometry/BoundingSolid.java` | AABB with `of`, `expand`, `longestAxis`, `intersectsRay` |
| `engine/utils/.../space/test/GridSurfaceTessellationTest.java` | tessellation, normals, GTS round trip |
| `engine/utils/.../space/test/MeshAccelerationTest.java` | accelerated == linear closest hit |
| `engine/utils/.../space/test/MeshIntersectionScalingTest.java` | sub-linear scaling benchmark |
