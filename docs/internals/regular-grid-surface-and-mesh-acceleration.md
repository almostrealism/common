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

### Mesh path (inspection and GTS export)

`Mesh.gridSurface(PackedCollection heights, Vector origin, spacingX, spacingZ)` builds a
vertex-list `Mesh` with the same node layout and winding. Its node positions are computed by
one kernel, `TriangleFeatures.regularGridSurfaceNodes(...)`, into a single `(nx*ny, 3)` tensor
whose rows become the mesh's vertices (as `Vector` views, not copies); only the triangle
connectivity is built on the host. This is the path for inspection,
for use as a test fixture, and for GTS export (`GtsResource.MeshTranscoder`), which reads a
mesh's vertex list and triangles. Use the device `MeshData.gridSurface` for the intersection
and per-frame path; use the host `Mesh.gridSurface` when a `Mesh` object is wanted.

## 2. Accelerated intersection

### Intersection as a Producer graph

Mesh intersection is expressed entirely as `CollectionProducer` compositions in
`TriangleFeatures`, so a batch of rays is intersected with a whole mesh in one evaluation, with
no host loop over rays or triangles:

- `meshIntersectAt(mesh, triangleCount, rays)` — the Möller–Trumbore distance of every ray
  against every triangle of a packed `(T, 4, 3)` mesh, as an `(R, T)` grid (`-1` where a ray
  misses a triangle). Rays are `(R, 6)` (origin, direction), or a single `(6)` ray.
- `closestMeshIntersection(mesh, triangleCount, rays)` — the closest hit per ray as `(R, 2)`
  (distance, triangle index), or `(-1, -1)` for a miss. Each (ray, triangle) pair is scored with
  its negated distance (a miss scores far below any hit), and the per-ray `max` and `indexOfMax`
  reductions over the triangle axis pick the closest; ties resolve to the lowest triangle index.
- `meshNormalAt(mesh, hits)` — gathers the face normal of each hit triangle, `(R, 3)`, with the
  zero vector for a miss.
- `meshTriangleBounds(mesh, triangleCount)` — the `(T, 6)` axis-aligned bounds of every triangle
  (minimum X, Y, Z then maximum X, Y, Z), the input to the hierarchy below.

`MeshData` compiles `closestMeshIntersection` against itself once per ray-batch size and
evaluates it; `evaluateIntersection` (one ray) and `evaluateIntersectionKernel` (a whole ray
bank, one dispatch) are the linear-scan entry points, with `evaluateIntersectionAccelerated` /
`evaluateIntersectionKernelAccelerated` the hierarchy-pruned counterparts.

### Bounding volume hierarchy

`BoundingVolumeHierarchy` is a host-built binary tree of axis-aligned boxes over a set of
triangles, built from a `(T, 6)` tensor of per-triangle bounds
(`BoundingVolumeHierarchy.build(PackedCollection)`). A `MeshData` owns a hierarchy over its own
triangles and builds it on demand via `MeshData.getBoundingVolumeHierarchy()`, computing the
bounds with the `meshTriangleBounds` kernel, so the hierarchy's indices map straight back to the
mesh's rows. For a ray, `candidates(ox, oy, oz, dx, dy, dz)` returns the
indices of every triangle whose box the ray pierces (slab test, boxes inflated by a small
epsilon). The result is **conservative**: it always contains the truly-closest triangle, so
intersecting only the candidates reproduces the closest hit of a full scan.

`MeshData.evaluateIntersectionAccelerated(ray, args)` runs the accelerated query against the
mesh's own hierarchy: it gathers the ray's candidates, copies those triangle rows into a reusable
compact buffer, and runs the same `closestMeshIntersection` graph over the candidates. Because
the triangle math is the identical compiled graph, the accelerated closest hit is bit-for-bit the
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

Every batched kernel here (`triangle`, `regularGridSurfacePoints`, `regularGridSurfaceNormals`,
`regularGridSurfaceNodes`, `meshIntersectAt`, `meshNormalAt`, `meshTriangleBounds`) declares an
output shape traversed per element, per triangle or per node, for example
`new TraversalPolicy(t, 4, 3).traverse(1)`. Without the traversal axis the output has a count of
one, so the whole `t * 12` element buffer is unrolled into a single kernel body and compile time
grows linearly with the triangle count.

## Worked example

```java
// An (nx, ny) height field, for example written by a simulation step each frame.
int nx = 64, ny = 64;
PackedCollection heights = new PackedCollection(shape(nx, ny));

Vector origin = new Vector(0, 0, 0);
double spacing = 0.1;

// Device tessellation -> MeshData for intersection (one kernel; rebuildable per frame).
MeshData md = MeshData.gridSurface(cp(heights), nx, ny, origin, spacing, spacing);

// Closest hit, accelerated and (reference) unaccelerated — identical result. The mesh data
// builds its own acceleration structure over its triangles on first accelerated query.
Evaluable<Ray> r = ray(vector(2.0, 5.0, 2.0), vector(0, -1, 0)).get();
Pair accelerated = md.evaluateIntersectionAccelerated(r, new Object[0]);
Pair linear      = md.evaluateIntersection(r, new Object[0]);
// accelerated.getA() == linear.getA()  (distance)
// accelerated.getB() == linear.getB()  (triangle index)

// A whole batch of rays (shape (R, 6)) in one evaluation -> (R, 2) closest hits and (R, 3) normals.
CollectionProducer hits = closestMeshIntersection(cp(md), md.getCount(), cp(rayBank));
CollectionProducer normals = meshNormalAt(cp(md), hits);

// Export a host mesh of the same surface for inspection.
Mesh mesh = Mesh.gridSurface(heights, origin, spacing, spacing);
GtsResource gts = new GtsResource.MeshTranscoder().transcode(new MeshResource(mesh));
```

## Scope and follow-up

The linear scan is fully device-side for a whole ray batch. The BVH is host-built and its
traversal runs on the host (per ray); the triangle math itself is the compiled graph. A
device-resident traversal is a possible future refinement, as is routing `Mesh.intersectAt`
straight through the Producer graph (distance and normal as producers of the ray, the way
`Sphere` does) so that `CachedMeshIntersectionKernel` is no longer needed.

The hierarchy is deliberately not a `SpacePartition`. `SpacePartition` is a `SurfaceGroup` — a
container of `ShadableSurface` objects that are intersected one by one — organised as a BSP tree
whose nodes split space with axis-aligned planes and store surfaces that span a plane. The BVH
instead indexes rows of a packed triangle tensor and partitions the *triangles*, not space: sibling
boxes may overlap, so there is no splitting plane to classify a ray against, and a ray must visit
every child box it pierces. Its traversal yields candidate row indices for the batched kernel
rather than intersecting surfaces itself. The existing `SpacePartition` traversal remains a stub.

## Related files

| File | Role |
|---|---|
| `domain/graph/.../graph/mesh/TriangleFeatures.java` | `regularGridSurfacePoints`, `regularGridSurfaceNodes`, `regularGridSurfaceNormals`, `meshIntersectAt`, `closestMeshIntersection`, `meshNormalAt`, `meshTriangleBounds` |
| `domain/space/.../space/MeshData.java` | `gridSurface`, linear + accelerated intersection, `getBoundingVolumeHierarchy` |
| `domain/space/.../space/Mesh.java` | host `gridSurface`, `calculateBoundingSolid` |
| `domain/space/.../space/BoundingVolumeHierarchy.java` | the acceleration structure |
| `compute/geometry/.../geometry/BoundingSolid.java` | AABB with `of`, `expand`, `longestAxis`, `intersectsRay` |
| `engine/utils/.../space/test/GridSurfaceTessellationTest.java` | tessellation, normals, GTS round trip |
| `engine/utils/.../space/test/MeshAccelerationTest.java` | accelerated == linear closest hit, batched == per-ray, normal gather, bounds |
| `engine/utils/.../space/test/MeshIntersectionScalingTest.java` | sub-linear scaling benchmark |
