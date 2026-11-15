# Concept Map

This document records the concept families in the platform whose members are
mathematically related, so that a reader (or an agent about to introduce a new type) can
find the interface that already expresses a concept before writing a parallel one. For each
family it lists the interfaces and classes, their declared supertypes **as they are in the
source today**, the module each lives in, and the **known gaps**: places where two types
represent the same (or a specializing) concept and the type system does not yet say so, with
the reason each gap is still open.

A relationship here is only what the source declares (`extends`/`implements`). A gap is only
listed when closing it would not require a new module dependency *and* every member of the
proposed supertype would be correct for the subtype; where a link would need a new dependency
or cannot honour the contract, that is said explicitly. When something is unclear it is
written as unclear rather than guessed.

The families below are the seed set. Later concept-alignment work extends this map as it
surveys further families and as gaps are closed.

---

## 1. Spatial functions

Functions of a position in space. `Gradient` declares only a normal (`getNormalAt`), and
`Curve` adds a value (`getValueAt`) of an arbitrary type `T`; the specific relationship "the
normal is the normalized gradient of the value" is the contract of scalar implicit surfaces
(`ImplicitSurface` and below), not of every type in this family. The hierarchy starts at a marker (`Differentiable`) and narrows through `Gradient`
and `Curve`, then **branches**: `ImplicitSurface`, `ShadableCurve` and `Volume` are separate
extensions of `Curve`, and `SignedDistanceFunction` and `HeightField` specialize
`ImplicitSurface` below it.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Differentiable<T>` | interface (no supertype) | `compute/algebra` (`org.almostrealism.algebra`) | none — marker |
| `Gradient<T>` | `extends Differentiable<T>` | `compute/algebra` (`org.almostrealism.algebra`) | `getNormalAt(Producer)` |
| `Curve<T>` | `extends Gradient<T>, Node` | `compute/geometry` (`org.almostrealism.geometry`) | `getValueAt(Producer)` |
| `ImplicitSurface` | `extends Curve<PackedCollection>, VectorFeatures` | `compute/geometry` (`org.almostrealism.geometry`) | `lipschitzBound()`, covariant `getValueAt`/`getNormalAt` returning `CollectionProducer`, default `partialDerivative` |
| `SignedDistanceFunction` | `extends ImplicitSurface` (`@FunctionalInterface`) | `compute/geometry` (`org.almostrealism.geometry`) | `getValueAt(Producer)`; `lipschitzBound()` fixed at `1` |
| `HeightField` | `class implements ImplicitSurface` | `compute/geometry` (`org.almostrealism.geometry`) | bilinear height sampling, analytic normal and Lipschitz bound |
| `ShadableCurve` | `extends Curve<PackedCollection>, Shadable` | `domain/color` (`org.almostrealism.color`) | none of its own — colour value plus shading |
| `Volume<T>` | `extends Curve<T>` | `domain/physics` (`org.almostrealism.physics`) | `inside`, `intersect(Vector, Vector)`, `getSurfaceCoords`, `getSpatialCoords` |

Notes on the declarations:

- `Differentiable` carries no operations; its javadoc names the concept (a function with
  continuous derivatives) and points at `Gradient`. The operations that actually realize the
  concept — `getNormalAt`, `getValueAt` — are declared one and two levels down, on `Gradient`
  and `Curve`.
- `ImplicitSurface` narrows `getValueAt`/`getNormalAt` covariantly from `Producer<T>` to
  `CollectionProducer` and adds `lipschitzBound()`, a bound on the rate of change of the value
  (not itself a distance): dividing the value by the bound gives a safe ray marching step. Its
  javadoc states the realignment that motivated this map: an implicit
  surface *is* a `Curve` of scalar values whose normal is the normalized gradient.
- `Curve extends Node` (see family 3), so `Curve` and every type that extends it are also graph
  nodes. `Differentiable` and `Gradient` do not extend `Node`.

### Gaps

- **`Differentiable` is a marker with no members** — a concept documented by an interface that
  carries no operations. A type written against `Differentiable<T>` can do nothing with it; any differentiation
  operation lives on `Gradient`/`Curve` instead. Open because no member that belongs on
  `Differentiable` itself (a value, or a derivative, independent of "normal") has been defined,
  and adding one would reshape every implementor; it needs design, not a mechanical change.
- **`Volume.intersect(Vector, Vector)` is host-valued and parallels the intersection family.**
  It returns a host `double`, and a source comment on the method asks for it to be replaced with
  an intersectable; the producer-valued counterpart is `Intersectable.intersectAt` (family 2).
  `Volume` is in `domain/physics`, which reaches `compute/geometry` transitively through
  `domain/color` (`physics → color → geometry`), so the link would **not** need a new
  dependency. It is open because the signature is host-valued (`double`, and
  `getSurfaceCoords`/`getSpatialCoords` likewise return `double[]`, with a source comment on
  `getSurfaceCoords` asking for a `Producer<Pair>` return): the concept must be moved onto producers before
  `Volume` can satisfy `Intersectable`.

---

## 2. Intersection

Testing a ray against a surface, and the set of points-with-directions that results. This
family is producer-valued except for `DistanceEstimator`.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Intersectable<T>` | `extends Supplier<Operator<T>>` | `compute/geometry` (`org.almostrealism.geometry`) | `intersectAt(Producer) : ContinuousField`, `expect() : Operator<T>` |
| `DiscreteField` | `extends NodeList<Producer<PackedCollection>>` | `compute/geometry` (`org.almostrealism.geometry`) | none of its own — a list of `Ray` producers |
| `ContinuousField` | `extends DiscreteField, Gradient<PackedCollection>` | `compute/geometry` (`org.almostrealism.geometry`) | none of its own |
| `ShadableSurface` | `extends ShadableCurve, Intersectable<PackedCollection>` | `domain/color` (`org.almostrealism.color`) | `getShadeFront`, `getShadeBack`, `calculateBoundingSolid` |
| `DistanceEstimator` | interface (no supertype) | `domain/space` (`org.almostrealism.space`) | `estimateDistance(Ray) : double` |

Notes and the cross-links this family already carries:

- `ContinuousField` is the join between this family and family 1: it is both a `DiscreteField`
  (a set of sampled points) and a `Gradient` (whose only member, `getNormalAt`, makes the normal
  evaluable anywhere — `Gradient` does not declare a value operation; `getValueAt` lives one
  level down on `Curve`). `Intersectable` returns one.
- `ShadableSurface` is the join between family 1's shading branch (`ShadableCurve`) and this
  family (`Intersectable`): a shadable, intersectable 3D surface.
- `DiscreteField extends NodeList`, so this family is also rooted in the graph family (3).

### Gaps

- **`DistanceEstimator` is "Similar to `Intersectable`" (its own javadoc) but stands alone and
  is host-valued.** It returns a host `double estimateDistance(Ray r)` from a host `Ray`, while
  `Intersectable.intersectAt` is producer-valued. Conceptually its "distance that could lead to
  an intersection" is also what an `ImplicitSurface` yields (family 1), though neither of its
  methods provides it alone: `getValueAt` is not in general a distance and `lipschitzBound()` is
  a rate-of-change bound, and the conservative marching distance is the value divided by the
  bound. `DistanceEstimator` is in `domain/space`, which
  reaches `compute/geometry` transitively, so neither link would need a new dependency; the gap
  is the host-valued signature (`double` + host `Ray`), which must be moved onto producers
  before it can align with either `Intersectable` or `ImplicitSurface`.
- **The position-function family (1) and the ray-intersection family (2) are parallel surface
  descriptions that do not meet.** An `ImplicitSurface` can be ray-marched, but it does not
  implement `Intersectable`. The blocker is contract, not module: `Intersectable.intersectAt`
  specifies a `ContinuousField` of the `t` roots of `p = o + t d`, whereas marching an implicit
  surface yields a hit position, and `expect()` has no natural meaning for a signed-distance
  value. Bridging the two is a design question about the intersection contract — not an
  `instanceof`/adapter, which this round's rules forbid.

---

## 3. Graphs

The node/graph/tree vocabulary in `base/relation`, and the spatial types that build on it.
`Group` (enumerable) and `Graph` (neighbour relationships) are kept deliberately distinct;
their javadoc explains why one does not extend the other.

| Type | Declared as | Module (package) | Members |
|---|---|---|---|
| `Node` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | none — marker |
| `Group<T>` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | `children() : Stream<T>`, default `all()` |
| `NodeGroup<T extends Node>` | `extends Group<T>` | `base/relation` (`io.almostrealism.relation`) | none of its own |
| `NodeList<T extends Node>` | `extends NodeGroup<T>, List<T>` | `base/relation` (`io.almostrealism.relation`) | default `children()` over `List.stream()` |
| `Graph<T extends Node>` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | `neighbors(T)`, `countNodes()` |
| `Tree<T extends Tree>` | `extends Graph<T>, NodeGroup<T>, Parent<T>, Node` | `base/relation` (`io.almostrealism.relation`) | tree traversal and depth defaults |
| `WeightedGraph<T extends Node>` | `extends Graph<T>, Group<T>` | `base/relation` (`io.almostrealism.relation`) | enumerable graph |
| `IndexedGraph<T extends Node>` | `extends WeightedGraph<T>` | `base/relation` (`io.almostrealism.relation`) | indexed enumerable graph |
| `Parent<T>` | interface (no supertype) | `base/relation` (`io.almostrealism.relation`) | children-holding marker |
| `Path` | `extends Plural<Node>` | `base/relation` (`io.almostrealism.relation`) | an ordered run of nodes |
| `TreeRef<T extends Tree>` | `class implements Tree<T>` | `base/relation` (`io.almostrealism.relation`) | a reference-backed `Tree` |
| `Mesh` | `extends SpacePartition<Triangle> implements Graph<Vector>, ConsoleFeatures` | `domain/space` (`org.almostrealism.space`) | a graph whose nodes are vertices |

How the other families root here:

- `Producer` and `Curve` both `extend Node`; `MemoryData` (the supertype of
  `PackedCollection`, hence of `Vector`) extends `Node`. So producers, spatial-function curves
  and every `PackedCollection`-backed value are graph nodes, which is why `Mesh` can bind
  `Graph<Vector>`.
- `DiscreteField extends NodeList` (family 2).

### Gaps

- **`Mesh` declares `Graph<Vector>` (vertices as nodes), but does not implement it.**
  `Mesh.neighbors(Vector)` currently throws `UnsupportedOperationException`, so the vertex graph
  is a declared relationship whose required operation is missing; any code written against
  `Graph` fails on a `Mesh`. Implementing it needs vertex adjacency derived from the triangle
  index data, which is within `domain/space` and needs no new dependency.
- **Triangle-level adjacency is not expressed through the graph hierarchy.** A `Triangle` is
  already a `Node` transitively (`Triangle` → `AbstractSurface` → `ShadableSurface` →
  `ShadableCurve` → `Curve` → `Node`), and the triangles of a mesh are already held in a
  `NodeGroup`: `SpacePartition<Triangle>` extends `SurfaceGroup<Triangle>`, which implements
  `NodeGroup<T>`. The gap is only that triangle adjacency is not stated through
  `Graph`/`neighbors` (and the container is not a `NodeList`). Whether a triangle-level
  graph is wanted is **unclear** — it is recorded here as an observation, not a prescription; a
  round should confirm the need before aligning, because a `Graph` over triangles must honour
  `neighbors`/`countNodes` for every mesh.

---

## 4. Fields on grids

Values sampled on a structured grid, and the finite-difference calculus over them. This
family sits across two modules, and the word "field" is **overloaded** between it and family
2 (see the caution below).

| Type | Declared as | Module (package) | Role |
|---|---|---|---|
| `FieldCalculusFeatures` | `extends CollectionFeatures` (mixin) | `compute/algebra` (`org.almostrealism.calculus`) | finite-difference operators (`shift`, `gradient`, `divergence`, Laplacians, Poisson relaxation) on a grid collection, as producers |
| `GridBoundary` | `abstract class implements CollectionFeatures` | `compute/algebra` (`org.almostrealism.calculus`) | ghost-cell boundary conditions (`periodic`/`clamped`/`fixed`) used by the operators |
| `HeightField` | `class implements ImplicitSurface` | `compute/geometry` (`org.almostrealism.geometry`) | a 2D grid of heights presented as a 3D implicit surface |

### Gaps

- **`HeightField` is a 2D grid field, but does not use the grid field calculus.** It wraps an
  `[nx, nz]` height collection and, in its private `Sample`, computes slopes
  (`dh/dx`, `dh/dz`) by hand — a derivative-of-height operation related to, but not the same as,
  what `FieldCalculusFeatures.gradient` expresses. `HeightField` is in `compute/geometry`, which
  depends on `compute/algebra`, so a link would **not** need a new dependency. The gap is not a
  direct is-a, because the two differ in sampling contract: `HeightField` differentiates its
  bilinear interpolation of the four surrounding cell corners at arbitrary sub-cell positions,
  whereas `FieldCalculusFeatures.gradient` applies central-difference stencils to a whole-grid,
  node-aligned field. There is also **no interface** naming "a field
  sampled on a grid" that both a grid-calculus field and a `HeightField` implement — the two
  types share the meaning without a common interface naming it. Whether a shared abstraction is worth introducing is
  **unclear** given the different access patterns; it is recorded for a round to judge, not
  prescribed.

### Caution: "field" means two different things

`DiscreteField`/`ContinuousField` (family 2, `compute/geometry`) are a *list of `Ray`
producers* — sampled points each with a direction. The "field" of `FieldCalculusFeatures`
(this family, `compute/algebra`) is a *collection sampled on a structured grid*. They share
the word but not the concept: one is an enumerated set of point/direction samples, the other a
dense grid of values. This is coincidental naming, **not** a gap — a link between them would
join unrelated meanings.

---

## How to use this map

Before introducing a type with a `value`/`normal`/`getValueAt`/`getNormalAt` pair, a
ray-`intersect`, a `neighbors`/node structure, or a grid of sampled values, find the family
above and extend the existing interface rather than a `*Features` mixin alone. If the
alignment cannot be made because a member would be wrong for the new type, or because it would
need a new module dependency, record that reason as a new gap here instead of leaving the
concept orphaned.
